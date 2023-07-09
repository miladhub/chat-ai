package chatai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.bind.Jsonb;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.*;

public class Chat
{
    private static final Logger LOG = LoggerFactory.getLogger(Chat.class);

    private static final String EMB_MODEL = "text-embedding-ada-002";

    private static final boolean USE_EMBEDDINGS =
            "true".equals(System.getenv("USE_EMBEDDINGS"));

    private static final String PG_URL =
            System.getenv("PG_URL") != null
                ? System.getenv("PG_URL")
                : "jdbc:postgresql://localhost:5432/quests";
    private static final String PG_USER =
            System.getenv("PG_USER") != null
                    ? System.getenv("PG_USER")
                    : "quests";
    private static final String PG_PSW =
            System.getenv("PG_PSW") != null
                    ? System.getenv("PG_PSW")
                    : "quests";

    private static final EncodingRegistry registry =
            Encodings.newDefaultEncodingRegistry();
    private static final Encoding tokenEncoder =
            registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);
    private static final int MAX_TOKENS = 4096;

    public static void main(String[] args)
    throws Exception {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            String prompt = reader.readLine();
            if (prompt != null) {
                String response = askOpenAi(System.getenv("OPENAI_API_KEY"),
                        prompt, "gpt-3.5-turbo");
                System.out.println("Model> " + response);
            } else {
                System.out.println("Bye");
                System.exit(0);
            }
        }
    }

    public static String askOpenAi(
            String apiKey,
            String prompt,
            String model
    )
    throws Exception {
        if (USE_EMBEDDINGS) {
            List<OpenAiMessage> messages =
                augmentPrompt(
                    apiKey,
                    prompt);
            try (Jsonb jsonb = Json.jsonb()) {
                String messagesJson = jsonb.toJson(messages);
                String json = String.format(
                        """
                                {
                                    "model": "%s",
                                    "messages": %s,
                                    "temperature": 0.7
                                }
                                """,
                        model,
                        messagesJson);
                LOG.info("Sending:\n" + json);
                String completion = chatCompletion(
                        apiKey,
                        json);
                OpenAiEmbedding embedding =
                        embed(apiKey, completion);
                saveUniquePrompt("system", completion, embedding);
                return completion;
            }
        } else {
            return chatCompletion(
                    apiKey,
                    String.format(
                            """
                            {
                                "model": "%s",
                                "messages": [{"role": "user", "content": "%s"}],
                                "temperature": 0.7
                            }
                            """,
                            model,
                            prompt
                    )
            );
        }
    }

    private static String chatCompletion(
            String apiKey,
            String json
    )
    throws Exception {
        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + apiKey);
        con.setConnectTimeout(300_000);
        con.setReadTimeout(300_000);
        try (OutputStream os = con.getOutputStream()) {
            LOG.debug("Sending JSON:\n" + json);
            byte[] bytes = json.getBytes(UTF_8);
            os.write(bytes, 0, bytes.length);
        }
        try (InputStream is = con.getInputStream();
             Jsonb jsonb = Json.jsonb()
        ) {
            String res = new String(is.readAllBytes(), UTF_8);
            OpenAiResponse resp = jsonb.fromJson(res, OpenAiResponse.class);
            return resp.choices().get(0).message().content();
        }
    }

    /**
     * Enforcing 4,096 token limit for gpt-3.5-turbo
     * @see <a href="https://platform.openai.com/docs/models/gpt-3-5"/>
     * @see <a href="https://github.com/knuddelsgmbh/jtokkit"/>
     */
    private static List<OpenAiMessage> augmentPrompt(
            String apiKey,
            String prompt
    )
    throws Exception {
        OpenAiEmbedding promptEmbedding = embed(apiKey, prompt);
        List<OpenAiMessage> similarPrompts = semanticSearch(promptEmbedding);
        saveUniquePrompt("user", prompt, promptEmbedding);
        return addPromptAndEnforceLimit(similarPrompts, prompt);
    }

    private static List<OpenAiMessage> addPromptAndEnforceLimit(
            List<OpenAiMessage> similarPrompts,
            String prompt
    ) {
        List<OpenAiMessage> messages = new ArrayList<>();
        int tot = tokenEncoder.countTokens(prompt);
        for (OpenAiMessage msg : similarPrompts) {
            int count = tokenEncoder.countTokens(msg.content());
            if (tot + count < MAX_TOKENS) {
                messages.add(msg);
                tot += count;
            }
        }
        messages.add(new OpenAiMessage("user", prompt));
        return messages;
    }

    public static void saveUniquePrompt(
            String role,
            String prompt,
            OpenAiEmbedding embedding
    )
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement find = conn.prepareStatement(
                     """
                     select count(*) from prompts where prompt = ?
                     """);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into prompts (role, prompt, embedding)
                     values (?, ?, ?)
                     """)
        ) {
            find.setString(1, prompt);
            ResultSet rs = find.executeQuery();
            rs.next();
            int count = rs.getInt(1);
            if (count > 0)
                return;

            insert.setString(1, role);
            insert.setString(2, prompt);
            float[] floats = toFloatArray(embedding);
            insert.setObject(3, new PGvector(floats));
            insert.execute();
        }
    }

    private static String clean(String prompt) {
        return prompt
                .replace("\n", " ")
                .replace("'", "''")
                .replace("\"", "\\\"")
                .replaceAll("\s+", " ")
                .trim();
    }

    private static List<OpenAiMessage> semanticSearch(OpenAiEmbedding e)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_PSW);
             PreparedStatement ps = conn.prepareStatement(
                     """
                     select role, prompt, embedding <=> ? as dist
                     from prompts
                     order by embedding <=> ?
                     limit 100
                     """)
        ) {
            float[] floats = toFloatArray(e);
            ps.setObject(1, new PGvector(floats));
            ps.setObject(2, new PGvector(floats));
            ResultSet rs = ps.executeQuery();
            List<OpenAiMessage> messages = new ArrayList<>();
            while (rs.next()) {
                String role = rs.getString(1);
                String prompt = rs.getString(2);
                float dist = rs.getFloat(3);
                LOG.info(String.format("distance %f - %s\n", dist, prompt));
                messages.add(new OpenAiMessage(role, prompt));
            }
            return messages;
        }
    }

    private static float[] toFloatArray(OpenAiEmbedding e) {
        float[] floats = new float[e.embeddings().size()];
        for (int i = 0; i < e.embeddings().size(); i++) {
            floats[i] = e.embeddings().get(i);
        }
        return floats;
    }

    public static OpenAiEmbedding embed(
            String apiKey,
            String value
    ) throws Exception {
        URL url = new URL("https://api.openai.com/v1/embeddings");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + apiKey);
        con.setConnectTimeout(300_000);
        con.setReadTimeout(300_000);
        try (OutputStream os = con.getOutputStream()) {
            String json = String.format("""
                    {
                        "model": "%s",
                        "input": "%s"
                    }
                    """, EMB_MODEL, clean(value));
            LOG.debug("Sending JSON:\n" + json);
            byte[] bytes = json.getBytes(UTF_8);
            os.write(bytes, 0, bytes.length);
        }
        try (InputStream is = con.getInputStream();
             Jsonb jsonb = Json.jsonb()
        ) {
            String res = new String(is.readAllBytes(), UTF_8);
            OpenAiEmbeddingsResponse resp =
                    jsonb.fromJson(res, OpenAiEmbeddingsResponse.class);
            OpenAiEmbeddingResponse emb =
                    resp.data().get(0);
            return new OpenAiEmbedding(emb.embedding());
        }
    }
}

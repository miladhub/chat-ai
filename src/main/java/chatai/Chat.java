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

/**
 * Enforcing 4,096 token limit for gpt-3.5-turbo.
 *
 * @see <a href="https://platform.openai.com/docs/models/gpt-3-5"/>
 * @see <a href="https://github.com/knuddelsgmbh/jtokkit"/>
 */
public class Chat
{
    private static final Logger LOG = LoggerFactory.getLogger(Chat.class);

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String OPENAI_CHAT_MODEL = "gpt-3.5-turbo";
    private static final int OPENAI_CHAT_MODEL_MAX_TOKEN = 4096;
    private static final String OPENAI_EMB_MODEL = "text-embedding-ada-002";

    private static final EncodingRegistry registry =
            Encodings.newDefaultEncodingRegistry();
    private static final Encoding tokenEncoder =
            registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);

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

    public static void main(String[] args)
    throws Exception {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Hit Ctrl-D to exit, enjoy!");
        while (true) {
            System.out.print("> ");
            String prompt = reader.readLine();
            if (prompt != null) {
                String response = askOpenAi(prompt);
                System.out.println("Model> " + response);
            } else {
                System.out.println("Bye");
                System.exit(0);
            }
        }
    }

    public static String askOpenAi(String prompt)
    throws Exception {
        Prompt promptEmbedding = embed(prompt);
        List<OpenAiMessage> similarPrompts = semanticSearch(
                promptEmbedding.embeddings());
        saveUniquePrompt("user", promptEmbedding);
        List<OpenAiMessage> messages =
                addPromptEnforcingLimit(similarPrompts, prompt);
        String completion = chatCompletion(messages);
        Prompt completionEmbedding = embed(completion);
        saveUniquePrompt("system", completionEmbedding);
        return completion;
    }

    private static List<OpenAiMessage> addPromptEnforcingLimit(
            List<OpenAiMessage> similarPrompts,
            String prompt
    ) {
        List<OpenAiMessage> messages = new ArrayList<>();
        int tot = tokenEncoder.countTokens(prompt);
        for (OpenAiMessage msg : similarPrompts) {
            int count = tokenEncoder.countTokens(msg.content());
            if (tot + count < OPENAI_CHAT_MODEL_MAX_TOKEN) {
                messages.add(msg);
                tot += count;
            }
        }
        messages.add(new OpenAiMessage("user", prompt));
        return messages;
    }

    private static String chatCompletion(List<OpenAiMessage> messages)
    throws Exception {
        try (Jsonb jsonb = Json.jsonb()) {
            String json = String.format(
                    """
                    {
                        "model": "%s",
                        "messages": %s,
                        "temperature": 0.7
                    }
                    """,
                    OPENAI_CHAT_MODEL,
                    jsonb.toJson(messages));
            URL url = new URL("https://api.openai.com/v1/chat/completions");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + OPENAI_API_KEY);
            con.setConnectTimeout(300_000);
            con.setReadTimeout(300_000);
            try (OutputStream os = con.getOutputStream()) {
                LOG.debug("Sending JSON to chat completion API:\n" + json);
                byte[] bytes = json.getBytes(UTF_8);
                os.write(bytes, 0, bytes.length);
            }
            try (InputStream is = con.getInputStream()) {
                String res = new String(is.readAllBytes(), UTF_8);
                OpenAiResponse resp = jsonb.fromJson(res, OpenAiResponse.class);
                return resp.choices().get(0).message().content();
            }
        }
    }

    private static void saveUniquePrompt(
            String role,
            Prompt prompt
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
            find.setString(1, prompt.contents());
            ResultSet rs = find.executeQuery();
            rs.next();
            int count = rs.getInt(1);
            if (count > 0)
                return;

            insert.setString(1, role);
            insert.setString(2, prompt.contents());
            float[] floats = toFloatArray(prompt.embeddings());
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

    private static List<OpenAiMessage> semanticSearch(List<Float> embeddings)
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
            float[] floats = toFloatArray(embeddings);
            ps.setObject(1, new PGvector(floats));
            ps.setObject(2, new PGvector(floats));
            ResultSet rs = ps.executeQuery();
            List<OpenAiMessage> messages = new ArrayList<>();
            while (rs.next()) {
                String role = rs.getString(1);
                String prompt = rs.getString(2);
                float dist = rs.getFloat(3);
                LOG.debug(String.format("Distance %f - %s\n", dist, prompt));
                messages.add(new OpenAiMessage(role, prompt));
            }
            return messages;
        }
    }

    private static float[] toFloatArray(List<Float> floatObjects) {
        float[] floats = new float[floatObjects.size()];
        for (int i = 0; i < floatObjects.size(); i++) {
            floats[i] = floatObjects.get(i);
        }
        return floats;
    }

    private static Prompt embed(String value) throws Exception {
        URL url = new URL("https://api.openai.com/v1/embeddings");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + OPENAI_API_KEY);
        con.setConnectTimeout(300_000);
        con.setReadTimeout(300_000);
        try (OutputStream os = con.getOutputStream()) {
            String json = String.format("""
                    {
                        "model": "%s",
                        "input": "%s"
                    }
                    """, OPENAI_EMB_MODEL, clean(value));
            LOG.debug("Sending JSON to embeddings API:\n" + json);
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
            return new Prompt(value, emb.embedding());
        }
    }
}

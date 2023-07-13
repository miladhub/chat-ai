package chatai;

import chatai.ChatResponse.FunctionCallChatResponse;
import chatai.ChatResponse.MessageChatResponse;
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
import java.nio.file.Files;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.*;

/**
 * Enforcing 4,096 token limit for gpt-3.5-turbo.
 *
 * @see <a href="https://platform.openai.com/docs/models/gpt-3-5"/>
 * @see <a href="https://github.com/knuddelsgmbh/jtokkit"/>
 * @see <a href="https://help.openai.com/en/articles/7042661-chatgpt-api-transition-guide/>
 * @see <a href="https://platform.openai.com/docs/guides/gpt/function-calling"/>
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
                : "jdbc:postgresql://localhost:5432/chat";
    private static final String PG_USER =
            System.getenv("PG_USER") != null
                    ? System.getenv("PG_USER")
                    : "chat";
    private static final String PG_PSW =
            System.getenv("PG_PSW") != null
                    ? System.getenv("PG_PSW")
                    : "chat";

    public static void main(String[] args)
    throws Exception {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        System.out.println(
                """
                Hit Ctrl-D to exit.
                To save or update context entries, type:
                :context (save|delete) <entry-name> [<entry-value>]
                To save or update functions, type:
                :function (save|delete) <fn-name> [<fn-descr> <fn-params-json-schema-file>]
                Enjoy!""");
        while (true) {
            System.out.print("> ");
            String prompt = reader.readLine();
            if (prompt != null) {
                if (prompt.startsWith(":context ")) {
                    String[] context = prompt.split("\s+", 4);
                    updateContext(
                            Command.valueOf(context[1]),
                            context[2],
                            context.length >= 4? context[3] : null);
                    System.out.println("Model> context updated.");
                } else if (prompt.startsWith(":function ")) {
                    String[] function = prompt.split("\s+", 4);
                    updateFunction(
                            Command.valueOf(function[1]),
                            function[2],
                            function.length >= 4? function[3] : null);
                    System.out.println("Model> function  updated.");
                } else {
                    String response = askCompletion(prompt);
                    System.out.println("Model> " + response);
                }
            } else {
                System.out.println("Bye");
                System.exit(0);
            }
        }
    }

    public static String askCompletion(String prompt)
    throws Exception {
        Embedding promptEmb = embed(prompt);
        saveMessage(new Message(Role.user, prompt), promptEmb);

        List<Message> similarMessages = semanticSearch(prompt, promptEmb);
        List<Message> ctx = contextMessages();
        List<Message> messages = composeMessages(similarMessages, ctx, prompt);
        List<ModelFunction> functions = functions();
        ChatResponse response = chatCompletion(messages, functions);

        switch (response) {
            case MessageChatResponse msg -> {
                Embedding completionEmb = embed(msg.content());
                saveMessage(new Message(Role.assistant, msg.content()), completionEmb);
                return msg.content();
            }
            case FunctionCallChatResponse fn -> {
                return fn.name() + "( " + fn.arguments() + " )";
            }
            default -> throw new IllegalStateException();
        }
    }

    private static List<ModelFunction> functions()
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement select = conn.prepareStatement(
                     """
                     select name, body from model_functions
                     """)
        ) {
            ResultSet rs = select.executeQuery();
            List<ModelFunction> messages = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                String body = rs.getString(2);
                messages.add(new ModelFunction(name, body));
            }
            return messages;
        }
    }

    public static void updateContext(
            Command command,
            String name,
            String value
    )
    throws Exception {
        switch (command) {
            case save -> saveContext(new Context(name, value));
            case delete -> deleteContext(name);
        }
    }

    public static void updateFunction(
            Command command,
            String name,
            String bodyFile
    )
    throws Exception {
        switch (command) {
            case save -> {
                String body = Files.readString(new File(bodyFile).toPath());
                saveFunction(new ModelFunction(name, body));
            }
            case delete -> deleteFunction(name);
        }
    }

    private static List<Message> composeMessages(
            List<Message> similarPrompts,
            List<Message> context,
            String prompt
    ) {
        List<Message> messages = new ArrayList<>(context);
        int promptTokens = tokenEncoder.countTokens(prompt);
        int contextTokens = context.stream()
                .map(Message::content)
                .mapToInt(tokenEncoder::countTokens)
                .sum();
        int tot = promptTokens + contextTokens;
        for (Message msg : similarPrompts) {
            int count = tokenEncoder.countTokens(msg.content());
            if (tot + count < OPENAI_CHAT_MODEL_MAX_TOKEN) {
                messages.add(msg);
                tot += count;
            }
        }
        messages.add(new Message(Role.user, prompt));
        return messages;
    }
    
    private static ChatResponse chatCompletion(
            List<Message> messages,
            List<ModelFunction> functions
    )
    throws Exception {
        try (Jsonb jsonb = Json.jsonb()) {
            String json = toJson(messages, functions);
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
                OpenAiMessage msg = resp.choices().get(0).message();
                if (msg.function_call() != null)
                    return new FunctionCallChatResponse(
                            msg.function_call().name(),
                            msg.function_call().arguments()
                    );
                else return new MessageChatResponse(msg.content());
            }
        }
    }

    private static String toJson(
            List<Message> messages,
            List<ModelFunction> functions
    )
    throws Exception {
        try (Jsonb jsonb = Json.jsonb()) {
            if (functions.isEmpty()) {
                return String.format(
                        """
                        {
                            "model": "%s",
                            "messages": %s,
                            "temperature": 0.7
                        }
                        """,
                        OPENAI_CHAT_MODEL,
                        jsonb.toJson(messages));
            } else {
                return String.format(
                        """
                        {
                            "model": "%s",
                            "messages": %s,
                            "functions": %s,
                            "temperature": 0.7
                        }
                        """,
                        OPENAI_CHAT_MODEL,
                        jsonb.toJson(messages),
                        "[" + functions.stream()
                                .map(ModelFunction::body)
                                .collect(Collectors.joining(",\n")) + "]");
            }
        }
    }

    private static List<Message> contextMessages()
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement select = conn.prepareStatement(
                     """
                     select value from contexts
                     """)
        ) {
            ResultSet rs = select.executeQuery();
            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                String value = rs.getString(1);
                messages.add(new Message(Role.system, value));
            }
            return messages;
        }
    }

    private static void saveMessage(Message message, Embedding embedding)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into messages (role, contents, embedding)
                     values (?, ?, ?)
                     on conflict (contents) do nothing
                     """)
        ) {
            insert.setString(1, message.role().name());
            insert.setString(2, message.content());
            float[] floats = toFloatArray(embedding.embeddings());
            insert.setObject(3, new PGvector(floats));
            insert.execute();
        }
    }

    private static void saveContext(Context ctx)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into contexts (name, value)
                     values(?, ?)
                     on conflict (name) do update set value = ?
                     """)
        ) {
            insert.setString(1, ctx.name());
            insert.setString(2, ctx.value());
            insert.setString(3, ctx.value());
            insert.execute();
        }
    }

    private static void deleteContext(String name)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement delete = conn.prepareStatement(
                     """
                     delete from contexts
                     where name = ?
                     """)
        ) {
            delete.setString(1, name);
            delete.execute();
        }
    }

    private static void saveFunction(ModelFunction fn)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into model_functions (name, body)
                     values(?, ?::jsonb)
                     on conflict (name) do update set body = ?::jsonb
                     """)
        ) {
            insert.setString(1, fn.name());
            insert.setString(2, fn.body());
            insert.setString(3, fn.body());
            insert.execute();
        }
    }

    private static void deleteFunction(String name)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement delete = conn.prepareStatement(
                     """
                     delete from model_functions
                     where name = ?
                     """)
        ) {
            delete.setString(1, name);
            delete.execute();
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

    private static List<Message> semanticSearch(
            String prompt,
            Embedding embedding)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_PSW);
             PreparedStatement ps = conn.prepareStatement(
                     """
                     select role, contents, embedding <=> ? as dist
                     from messages
                     where contents <> ?
                     order by embedding <=> ?
                     limit 100
                     """)
        ) {
            float[] floats = toFloatArray(embedding.embeddings());
            ps.setObject(1, new PGvector(floats));
            ps.setString(2, prompt);
            ps.setObject(3, new PGvector(floats));
            ResultSet rs = ps.executeQuery();
            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                String role = rs.getString(1);
                String contents = rs.getString(2);
                float dist = rs.getFloat(3);
                LOG.debug(String.format("Distance %f - %s\n", dist, contents));
                messages.add(new Message(Role.valueOf(role), contents));
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

    private static Embedding embed(String value) throws Exception {
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
            return new Embedding(emb.embedding());
        }
    }
}

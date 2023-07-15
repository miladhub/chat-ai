package chatai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.bind.Jsonb;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class HttpUrlConnectionOpenAiClient
        implements OpenAiClient
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiClient.class);

    private static final String OPENAI_CHAT_MODEL = "gpt-3.5-turbo";
    private static final String OPENAI_EMB_MODEL = "text-embedding-ada-002";

    @Override
    public Embedding embed(
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

    @Override
    public ChatResponse chatCompletion(
            String apiKey,
            List<OpenAiRequestMessage> messages,
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
            con.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                OpenAiResponseMessage msg = resp.choices().get(0).message();
                if (msg.function_call() != null)
                    return new ChatResponse.FunctionCallChatResponse(
                            msg.function_call().name(),
                            msg.function_call().arguments()
                    );
                else return new ChatResponse.MessageChatResponse(msg.content());
            }
        }
    }

    private String clean(String prompt) {
        return prompt
                .replace("\n", " ")
                .replace("'", "''")
                .replace("\"", "\\\"")
                .replaceAll("\s+", " ")
                .trim();
    }

    private String toJson(
            List<OpenAiRequestMessage> messages,
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
}

package chatai;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

@SuppressWarnings("ALL")
public class JsonTest
{
    @Test
    public void embedding_from_json() {
        OpenAiEmbeddingsResponse emb = Json.jsonb().fromJson(
                """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": [
                        0.002264385,
                        -0.009305084,
                        0.015766595,
                        -0.0077627,
                        -0.0047667925
                      ]
                    }
                  ],
                  "model": "text-embedding-ada-002-v2",
                  "usage": {
                    "prompt_tokens": 8,
                    "total_tokens": 8
                  }
                }
                """,
                OpenAiEmbeddingsResponse.class
        );
        assertEquals(
                new OpenAiEmbeddingsResponse(
                        List.of(
                                new OpenAiEmbeddingResponse(
                                        List.of(
                                                0.002264385f,
                                                -0.009305084f,
                                                0.015766595f,
                                                -0.0077627f,
                                                -0.0047667925f
                                        )
                                )
                        )
                ),
                emb
        );
    }

    @Test
    public void messages_to_json() {
        List<OpenAiMessage> msgs = List.of(
                new OpenAiMessage("user", "foo"),
                new OpenAiMessage("system", "bar")
        );
        String json = Json.jsonb().toJson(msgs);
        assertEquals(
                msgs,
                Arrays.asList(Json.jsonb().fromJson(json,
                        OpenAiMessage[].class)));
    }
}

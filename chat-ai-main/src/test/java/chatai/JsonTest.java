package chatai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        List<OpenAiRequestMessage> msgs = List.of(
                new OpenAiRequestMessage(Role.user, "foo"),
                new OpenAiRequestMessage(Role.system, "bar")
        );
        String json = Json.jsonb().toJson(msgs);
        assertEquals(
                msgs,
                Arrays.asList(Json.jsonb().fromJson(json,
                        OpenAiRequestMessage[].class)));
    }

    @Test
    void response_from_json() {
        //language=JSON
        String json =
                """
                {
                  "id": "chatcmpl-7fp29q2jX7MEaiu6ic2vjaiEPyrDJ",
                  "object": "chat.completion",
                  "created": 1690201977,
                  "model": "gpt-3.5-turbo-0613",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "some response"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 3736,
                    "completion_tokens": 361,
                    "total_tokens": 4097
                  }
                }
                """;

        OpenAiResponse resp = Json.jsonb().fromJson(json,
                OpenAiResponse.class);

        assertEquals(
                new OpenAiResponse(
                        List.of(
                                new OpenAiChoice(
                                        new OpenAiResponseMessage(
                                                Role.assistant,
                                                "some response",
                                                null
                                        ),
                                        "stop"
                                )
                        ),
                        new OpenAiUsage(
                                3736,
                                361,
                                4097
                        )
                ),
                resp
        );
    }
}

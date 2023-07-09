package chatai;

import java.util.List;

public record OpenAiEmbeddingsResponse(
        List<OpenAiEmbeddingResponse> data
)
{
}

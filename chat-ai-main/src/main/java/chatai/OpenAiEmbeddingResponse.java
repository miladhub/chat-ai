package chatai;

import java.util.List;

public record OpenAiEmbeddingResponse(
        List<Float> embedding
)
{
}

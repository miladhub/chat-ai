package chatai;

import java.util.List;

public record Prompt(String value, List<Float> embeddings)
{
}

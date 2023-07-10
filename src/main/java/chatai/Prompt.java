package chatai;

import java.util.List;

public record Prompt(String contents, List<Float> embeddings)
{
}

package chatai;

public record OpenAiUsage(
        double prompt_tokens,
        double completion_tokens,
        double total_tokens
)
{
}

package chatai;

public record OpenAiUsage(
        int prompt_tokens,
        int completion_tokens,
        int total_tokens
)
{
}

package chatai;

public record OpenAiChoice(
        OpenAiResponseMessage message,
        String finish_reason
)
{
}

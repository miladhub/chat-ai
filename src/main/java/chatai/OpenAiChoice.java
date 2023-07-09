package chatai;

public record OpenAiChoice(
        OpenAiMessage message,
        String finish_reason,
        double index
)
{
}

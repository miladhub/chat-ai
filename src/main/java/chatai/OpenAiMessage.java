package chatai;

public record OpenAiMessage(
        Role role,
        String content,
        OpenAiFunctionCall function_call
)
{
}

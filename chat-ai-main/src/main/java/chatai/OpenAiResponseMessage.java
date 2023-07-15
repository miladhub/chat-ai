package chatai;

public record OpenAiResponseMessage(
        Role role,
        String content,
        OpenAiFunctionCall function_call
)
{
}

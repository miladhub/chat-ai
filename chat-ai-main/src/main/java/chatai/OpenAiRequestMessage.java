package chatai;

public record OpenAiRequestMessage(
        Role role,
        String content
)
{
}

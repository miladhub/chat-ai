package chatai;

public sealed interface ChatResponse
        permits ChatResponse.MessageChatResponse,
                ChatResponse.FunctionCallChatResponse
{
    record MessageChatResponse(String content) implements ChatResponse {}
    record FunctionCallChatResponse(String name, String arguments) implements ChatResponse {}
}

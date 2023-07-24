package chatai;

import java.util.List;

public interface OpenAiClient
{
    Embedding embed(
            String apiKey,
            String promptTxt
    ) throws Exception;

    ChatResponse chatCompletion(
            String apiKey,
            List<OpenAiRequestMessage> messages,
            List<ModelFunction> functions,
            int maxTokens
    ) throws Exception;
}

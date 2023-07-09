package chatai;

import java.util.List;

public record OpenAiResponse(
        String id,
        String object,
        long created,
        String model,
        OpenAiUsage usage,
        List<OpenAiChoice> choices
)
{
}

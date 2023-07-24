package chatai;

import java.util.List;

public record OpenAiResponse(
        List<OpenAiChoice> choices,
        OpenAiUsage usage
)
{
}

package chatai;

import java.time.Instant;

public record Message(
        Role role,
        String content,
        Instant timestamp
)
{
}

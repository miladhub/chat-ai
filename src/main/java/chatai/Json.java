package chatai;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;

public class Json
{
    private static final JsonbConfig jsonbConfig = new JsonbConfig()
            .withFormatting(true);

    public static Jsonb jsonb() {
        return JsonbBuilder.create(jsonbConfig);
    }
}

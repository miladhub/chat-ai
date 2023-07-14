package chatai.rest;

import chatai.Chat;
import chatai.ChatRequest;
import chatai.ChatResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@Path("/chat")
public class ChatController
{
    @POST
    @Produces("application/json")
    @Consumes("application/json")
    public ChatResponse askCompletion(ChatRequest request)
    throws Exception {
        return Chat.askCompletion(request);
    }
}

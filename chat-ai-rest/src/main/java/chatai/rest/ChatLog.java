package chatai.rest;

import org.jboss.logging.Logger;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ChatLog
        implements ExceptionMapper<Exception>
{
    private static final Logger LOG =
            Logger.getLogger(ChatLog.class);

    @Override
    public Response toResponse(Exception e) {
        LOG.error(e);
        return Response
                .serverError()
                .build();
    }
}

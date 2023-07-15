package chatai;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PgVectorPromptRepository
        implements PromptRepository
{
    private static final Logger LOG = LoggerFactory.getLogger(PgVectorPromptRepository.class);

    private static final String PG_URL =
            System.getenv("PG_URL") != null
                    ? System.getenv("PG_URL")
                    : "jdbc:postgresql://localhost:5432/chat";
    private static final String PG_USER =
            System.getenv("PG_USER") != null
                    ? System.getenv("PG_USER")
                    : "chat";
    private static final String PG_PSW =
            System.getenv("PG_PSW") != null
                    ? System.getenv("PG_PSW")
                    : "chat";

    @Override
    public  void saveContext(Context ctx)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into contexts (name, value)
                     values(?, ?)
                     on conflict (name) do update set value = ?
                     """)
        ) {
            insert.setString(1, ctx.name());
            insert.setString(2, ctx.value());
            insert.setString(3, ctx.value());
            insert.execute();
        }
    }

    @Override
    public void deleteContext(String name)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement delete = conn.prepareStatement(
                     """
                     delete from contexts
                     where name = ?
                     """)
        ) {
            delete.setString(1, name);
            delete.execute();
        }
    }

    @Override
    public void saveFunction(ModelFunction fn)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into model_functions (name, body)
                     values(?, ?::jsonb)
                     on conflict (name) do update set body = ?::jsonb
                     """)
        ) {
            insert.setString(1, fn.name());
            insert.setString(2, fn.body());
            insert.setString(3, fn.body());
            insert.execute();
        }
    }

    @Override
    public void deleteFunction(String name)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement delete = conn.prepareStatement(
                     """
                     delete from model_functions
                     where name = ?
                     """)
        ) {
            delete.setString(1, name);
            delete.execute();
        }
    }

    @Override
    public List<Message> semanticSearch(
            String prompt,
            Embedding embedding)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_PSW);
             PreparedStatement ps = conn.prepareStatement(
                     """
                     select role, contents, message_ts, embedding <=> ? as dist
                     from messages
                     where contents <> ?
                     order by embedding <=> ?
                     limit 100
                     """)
        ) {
            float[] floats = toFloatArray(embedding.embeddings());
            ps.setObject(1, new PGvector(floats));
            ps.setString(2, prompt);
            ps.setObject(3, new PGvector(floats));
            ResultSet rs = ps.executeQuery();
            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                String role = rs.getString(1);
                String contents = rs.getString(2);
                Timestamp timestamp = rs.getTimestamp(3);
                float dist = rs.getFloat(4);
                LOG.debug(String.format("Distance %f - %s\n", dist, contents));
                messages.add(new Message(
                        Role.valueOf(role),
                        contents,
                        timestamp.toInstant()
                ));
            }
            return messages;
        }
    }

    @Override
    public List<Context> contextMessages()
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement select = conn.prepareStatement(
                     """
                     select name, value from contexts
                     """)
        ) {
            ResultSet rs = select.executeQuery();
            List<Context> contexts = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                String value = rs.getString(2);
                contexts.add(new Context(name, value));
            }
            return contexts;
        }
    }

    @Override
    public List<ModelFunction> functions()
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement select = conn.prepareStatement(
                     """
                     select name, body from model_functions
                     """)
        ) {
            ResultSet rs = select.executeQuery();
            List<ModelFunction> messages = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString(1);
                String body = rs.getString(2);
                messages.add(new ModelFunction(name, body));
            }
            return messages;
        }
    }

    @Override
    public void saveMessage(Message msg, Embedding embedding)
    throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                PG_URL, PG_USER, PG_USER);
             PreparedStatement insert = conn.prepareStatement(
                     """
                     insert into messages (role, contents, embedding, message_ts)
                     values (?, ?, ?, ?)
                     on conflict (contents) do nothing
                     """)
        ) {
            insert.setString(1, msg.role().name());
            insert.setString(2, msg.content());
            float[] floats = toFloatArray(embedding.embeddings());
            insert.setObject(3, new PGvector(floats));
            insert.setTimestamp(4, new Timestamp(msg.timestamp().toEpochMilli()));
            insert.execute();
        }
    }

    private float[] toFloatArray(List<Float> floatObjects) {
        float[] floats = new float[floatObjects.size()];
        for (int i = 0; i < floatObjects.size(); i++) {
            floats[i] = floatObjects.get(i);
        }
        return floats;
    }
}

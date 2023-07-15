package chatai;

import java.sql.SQLException;
import java.util.List;

public interface PromptRepository
{
    void saveContext(Context context) throws SQLException;

    void deleteContext(String name) throws SQLException;

    void saveFunction(ModelFunction function) throws SQLException;

    void deleteFunction(String name) throws SQLException;

    List<Message> semanticSearch(
            String promptTxt,
            Embedding promptEmb
    ) throws SQLException;

    List<Context> contextMessages() throws SQLException;

    List<ModelFunction> functions() throws SQLException;

    void saveMessage(
            Message message,
            Embedding embedding
    ) throws SQLException;
}

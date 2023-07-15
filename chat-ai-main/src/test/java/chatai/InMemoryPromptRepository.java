package chatai;

import java.util.*;
import java.util.stream.IntStream;

public class InMemoryPromptRepository
        implements PromptRepository
{
    private final Map<String, Context> contexts = new HashMap<>();
    private final Map<String, ModelFunction> functions = new HashMap<>();
    private final List<MessageWithEmbedding> messages = new ArrayList<>();

    @Override
    public void saveContext(Context context) {
        contexts.put(context.name(), context);
    }

    @Override
    public void deleteContext(String name) {
        contexts.remove(name);
    }

    @Override
    public List<Context> contextMessages() {
        return new ArrayList<>(contexts.values());
    }

    @Override
    public void saveFunction(ModelFunction function) {
        functions.put(function.name(), function);
    }

    @Override
    public void deleteFunction(String name) {
        functions.remove(name);
    }

    @Override
    public List<ModelFunction> functions() {
        return new ArrayList<>(functions.values());
    }

    @Override
    public void saveMessage(
            Message message,
            Embedding embedding
    ) {
        messages.add(new MessageWithEmbedding(message, embedding));
    }

    @Override
    public List<Message> semanticSearch(
            String promptTxt,
            Embedding promptEmb
    ) {
        return messages.stream()
                .filter(m -> !m.message().content().equals(promptTxt))
                .map(m -> new MessageSimilarity(
                        m.message(),
                        dotProduct(promptEmb, m.embedding()) // Approximating
                ))
                // From the most to the least similar
                .sorted(Comparator.comparing(MessageSimilarity::similarity).reversed())
                .map(MessageSimilarity::message)
                .toList();
    }

    private double dotProduct(
            Embedding l,
            Embedding r
    ) {
        return IntStream.range(0, l.embeddings().size())
                .mapToDouble(i -> l.embeddings().get(i) * r.embeddings().get(i))
                .sum();
    }

    public void addMessageWithEmbedding(MessageWithEmbedding msg) {
        messages.add(msg);
    }

    record MessageWithEmbedding(
            Message message,
            Embedding embedding
    ) {}

    record MessageSimilarity(
            Message message,
            double similarity
    ) {}
}

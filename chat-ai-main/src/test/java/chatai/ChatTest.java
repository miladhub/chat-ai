package chatai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.jmock.Expectations;
import org.jmock.junit5.JUnit5Mockery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static chatai.Role.user;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatTest
{
    private static final String OPEN_API_KEY = "my-key";
    private static final int TOKEN_LIMIT = 10;
    private static final EncodingRegistry registry =
            Encodings.newDefaultEncodingRegistry();
    private final Encoding tokens =
            registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);

    @RegisterExtension
    JUnit5Mockery context = new JUnit5Mockery();

    private final OpenAiClient client = context.mock(OpenAiClient.class);

    private final InMemoryPromptRepository repository =
            new InMemoryPromptRepository();

    private final Chat chat = new Chat(repository, client, TOKEN_LIMIT);

    @Test
    void sends_prompt_to_openai()
    throws Exception {
        context.checking(new Expectations() {{
            allowing(client).embed(with(any(String.class)), with(any(String.class)));
                will(returnValue(new Embedding(List.of(1f, 2f, 3f))));

            oneOf(client).chatCompletion(
                    OPEN_API_KEY,
                    List.of(new OpenAiRequestMessage(user, "hello")),
                    List.of(), 300);
                will(returnValue(new ChatResponse.MessageChatResponse("hi")));
        }});

        chat.askCompletion(new ChatRequest(OPEN_API_KEY, "hello"));
    }

    @Test
    void sends_most_relevant_messages_within_limit()
    throws Exception {
        String first_and_most_similar = "first and most similar";
        String middle_and_least_similar = "middle and least similar";
        String last_and_middle_similar = "last and middle similar";

        assertEquals(4, tokens.countTokens(first_and_most_similar));
        assertEquals(4, tokens.countTokens(middle_and_least_similar));
        assertEquals(4, tokens.countTokens(last_and_middle_similar));

        context.checking(new Expectations() {{
            allowing(client).embed(with(any(String.class)), with("hello"));
                will(returnValue(new Embedding(List.of(1f, 0f, 0f))));
            ignoring(client).embed(with(any(String.class)), with("hi"));

            oneOf(client).chatCompletion(
                    with(OPEN_API_KEY),
                    with(listNotExceedingLimit()),
                    with(List.of()),
                    with(any(Integer.class)));
            will(returnValue(new ChatResponse.MessageChatResponse("hi")));
        }});

        addMessage(
                "2007-12-03T10:15:30",
                first_and_most_similar,
                List.of(1f, 0f, 0f));
        addMessage(
                "2007-12-03T10:15:31",
                middle_and_least_similar,
                List.of(0f, 0f, 1f));
        addMessage(
                "2007-12-03T10:15:32",
                last_and_middle_similar,
                List.of(0.5f, 0f, 0f));

        chat.askCompletion(new ChatRequest(OPEN_API_KEY, "hello"));
    }

    private Matcher<List<OpenAiRequestMessage>> listNotExceedingLimit() {
        return new TypeSafeMatcher<>()
        {
            @Override
            protected boolean matchesSafely(List<OpenAiRequestMessage> ms) {
                return ms.stream()
                        .mapToInt(m -> tokens.countTokens(m.content()))
                        .sum() <= TOKEN_LIMIT;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("list with sum of tokens < " + TOKEN_LIMIT);
            }
        };
    }

    @Test
    void sends_messages_preserving_order()
    throws Exception {
        String first_and_middle_similar = "first and middle similar";
        String last_and_most_similar = "last and most similar";

        context.checking(new Expectations() {{
            allowing(client).embed(with(any(String.class)), with("hello"));
            will(returnValue(new Embedding(List.of(1f, 0f, 0f))));
            ignoring(client).embed(with(any(String.class)), with("hi"));

            oneOf(client).chatCompletion(
                    with(OPEN_API_KEY),
                    with(partialOrder(
                            first_and_middle_similar,
                            last_and_most_similar,
                            "hello"
                    )),
                    with(List.of()),
                    with(any(Integer.class)));
            will(returnValue(new ChatResponse.MessageChatResponse("hi")));
        }});

        addMessage(
                "2007-12-03T10:15:30",
                first_and_middle_similar,
                List.of(0.5f, 0f, 0f));
        addMessage(
                "2007-12-03T10:15:32",
                last_and_most_similar,
                List.of(1f, 0f, 0f));

        chat.askCompletion(new ChatRequest(OPEN_API_KEY, "hello"));
    }

    private Matcher<List<OpenAiRequestMessage>> partialOrder(
            String... contents
    ) {
        return new TypeSafeMatcher<>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("partial order " + Arrays.toString(contents));
            }

            @Override
            protected boolean matchesSafely(List<OpenAiRequestMessage> ms) {
                List<Integer> indexes = Arrays.stream(contents)
                        .map(c -> ms.stream().map(OpenAiRequestMessage::content).toList().indexOf(c))
                        .toList();
                for (int i = 1; i < indexes.size(); i++) {
                    if (indexes.get(i) < indexes.get(i - 1))
                        return false;
                }
                return true;
            }
        };
    }

    private void addMessage(
            String when,
            String msg,
            List<Float> embedding
    ) {
        repository.addMessageWithEmbedding(
                new InMemoryPromptRepository.MessageWithEmbedding(
                        new Message(user, msg, Instant.parse(when + ".00Z")),
                        new Embedding(embedding))
        );
    }
}
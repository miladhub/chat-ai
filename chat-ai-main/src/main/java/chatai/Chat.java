package chatai;

import chatai.ChatResponse.FunctionCallChatResponse;
import chatai.ChatResponse.MessageChatResponse;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enforcing 4,096 token limit for gpt-3.5-turbo.
 *
 * @see <a href="https://platform.openai.com/docs/models/gpt-3-5"/>
 * @see <a href="https://github.com/knuddelsgmbh/jtokkit"/>
 * @see <a href="https://help.openai.com/en/articles/7042661-chatgpt-api-transition-guide/>
 * @see <a href="https://platform.openai.com/docs/guides/gpt/function-calling"/>
 */
public class Chat
{
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");

    // While OpenAI allows 4,096 tokens, this project breaks at ~3500
    // probably due to the token encoder not being OpenAI's
    private static final int OPENAI_CHAT_MODEL_MAX_TOKENS = 3500;

    private static final EncodingRegistry registry =
            Encodings.newDefaultEncodingRegistry();
    private static final Encoding tokenEncoder =
            registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);

    private final PromptRepository repository;
    private final OpenAiClient client;
    private final int tokenLimit;

    public static Chat create() {
        return new Chat(
                new PgVectorPromptRepository(),
                new HttpUrlConnectionOpenAiClient(),
                OPENAI_CHAT_MODEL_MAX_TOKENS);
    }

    public static void main(String[] args)
    throws Exception {
        Chat chat = create();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        System.out.println(
                """
                Hit Ctrl-D to exit.
                To save or update context entries, type:
                :context (save|delete) <entry-name> [<entry-value>]
                To save or update functions, type:
                :function (save|delete) <fn-name> [<fn-descr> <fn-params-json-schema-file>]
                Enjoy!""");
        while (true) {
            System.out.print("> ");
            String prompt = reader.readLine();
            if (prompt != null) {
                if (prompt.startsWith(":context ")) {
                    String[] context = prompt.split("\s+", 4);
                    chat.updateContext(
                            Command.valueOf(context[1]),
                            context[2],
                            context.length >= 4? context[3] : null);
                    System.out.println("Model> context updated.");
                } else if (prompt.startsWith(":function ")) {
                    String[] function = prompt.split("\s+", 4);
                    chat.updateFunction(
                            Command.valueOf(function[1]),
                            function[2],
                            function.length >= 4? function[3] : null);
                    System.out.println("Model> function  updated.");
                } else {
                    ChatResponse response = chat.askCompletion(new ChatRequest(
                            OPENAI_API_KEY, prompt
                    ));
                    switch (response) {
                        case MessageChatResponse msg ->
                                System.out.println("Model> " + msg.content());
                        case FunctionCallChatResponse fn ->
                                System.out.println("Model> " + fn.name() + "( " + fn.arguments() + " )");
                        default -> throw new IllegalStateException();
                    }
                }
            } else {
                System.out.println("Bye");
                System.exit(0);
            }
        }
    }

    public Chat(
            PromptRepository repository,
            OpenAiClient client,
            int tokenLimit
    ) {
        this.repository = repository;
        this.client = client;
        this.tokenLimit = tokenLimit;
    }

    public ChatResponse askCompletion(ChatRequest request)
    throws Exception {
        String apiKey = request.apiKey();
        String promptTxt = request.prompt();
        Message prompt = new Message(Role.user, promptTxt, Instant.now());

        Embedding promptEmb = client.embed(apiKey, promptTxt);
        repository.saveMessage(prompt, promptEmb);

        List<Message> similar = repository.semanticSearch(promptTxt, promptEmb);

        List<OpenAiRequestMessage> ctx = repository.contextMessages().stream()
                .map(c -> new OpenAiRequestMessage(Role.system, c.value()))
                .toList();

        List<OpenAiRequestMessage> messages = composeMessages(similar, ctx, prompt);
        List<ModelFunction> functions = repository.functions();
        ChatResponse response = client.chatCompletion(apiKey, messages, functions);

        switch (response) {
            case MessageChatResponse msg -> {
                Embedding completionEmb = client.embed(apiKey, msg.content());
                repository.saveMessage(
                        new Message(Role.assistant, msg.content(), Instant.now()),
                        completionEmb);
                return msg;
            }
            case FunctionCallChatResponse fn -> {
                return fn;
            }
            default -> throw new IllegalStateException();
        }
    }

    private void updateContext(
            Command command,
            String name,
            String value
    )
    throws Exception {
        switch (command) {
            case save -> repository.saveContext(new Context(name, value));
            case delete -> repository.deleteContext(name);
        }
    }

    private void updateFunction(
            Command command,
            String name,
            String bodyFile
    )
    throws Exception {
        switch (command) {
            case save -> {
                String body = Files.readString(new File(bodyFile).toPath());
                repository.saveFunction(new ModelFunction(name, body));
            }
            case delete -> repository.deleteFunction(name);
        }
    }

    private List<OpenAiRequestMessage> composeMessages(
            List<Message> similar,
            List<OpenAiRequestMessage> context,
            Message prompt
    ) {
        int promptTokens = tokenEncoder.countTokens(prompt.content());
        int contextTokens = context.stream()
                .map(OpenAiRequestMessage::content)
                .mapToInt(tokenEncoder::countTokens)
                .sum();
        int tot = promptTokens + contextTokens;
        List<Message> similarBound = new ArrayList<>();
        for (Message msg : similar) {
            int count = tokenEncoder.countTokens(msg.content());
            if (tot + count < tokenLimit) {
                similarBound.add(msg);
                tot += count;
            }
        }

        List<Message> similarSorted = similarBound.stream()
                .sorted(Comparator.comparing(Message::timestamp))
                .toList();

        List<OpenAiRequestMessage> messages = new ArrayList<>(context);
        messages.addAll(similarSorted.stream()
                .map(m -> new OpenAiRequestMessage(m.role(), m.content()))
                .toList());
        messages.add(new OpenAiRequestMessage(prompt.role(), prompt.content()));
        return messages;
    }
}

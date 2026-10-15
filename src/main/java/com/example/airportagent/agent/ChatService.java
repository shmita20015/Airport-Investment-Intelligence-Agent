package com.example.airportagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.airportagent.config.LimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One chat turn: ask the LLM, check the numbers of the answer, ask again one time, then use a template answer.
 * The LLM chooses tools and writes the text. It does not calculate (docs/DESIGN-full.md, section 8).
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    static final int MAX_DATA_TEXT = 1500;
    /** A request waits this long for a free chat slot. Then it gets HTTP 503. */
    static final Duration SLOT_WAIT = Duration.ofSeconds(2);
    /** The value of the key setting when the environment variable GEMINI_API_KEY is not set. */
    static final String NO_KEY = "not-set";
    /** Control characters and invisible text direction marks. A line break and a tab are allowed. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");

    private final ChatClient client;
    private final boolean keySet;
    private final Semaphore slots;
    /** The sessions of the app. An idle session and the oldest sessions are deleted, with their chat history. */
    private final Cache<String, ToolLog> sessions;

    public ChatService(ChatClient client, ChatMemory memory, LimitProperties limits,
                       @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        this.client = client;
        this.keySet = !apiKey.isBlank() && !NO_KEY.equals(apiKey);
        this.slots = new Semaphore(limits.maxConcurrentChats());
        this.sessions = Caffeine.newBuilder()
                .maximumSize(limits.maxSessions())
                .expireAfterAccess(Duration.ofMinutes(limits.sessionIdleMinutes()))
                .removalListener((String id, ToolLog log, RemovalCause cause) -> memory.clear(id))
                .build();
    }

    /**
     * @param sessionId the session id from an earlier reply. The app never accepts an id that it did not give,
     *                  so a client cannot choose or guess the session of another client. An unknown id starts
     *                  a new session.
     * @throws ChatBusyException if all chat slots are in use
     */
    public ChatReply chat(String sessionId, String message) {
        String session = sessionId != null && sessions.getIfPresent(sessionId) != null
                ? sessionId : UUID.randomUUID().toString();
        if (!keySet) {
            return new ChatReply(session, "The chat needs a Gemini API key. Set the environment variable GEMINI_API_KEY "
                    + "and restart the app. The REST API under /api works without the key.", false, List.of(), List.of());
        }
        acquireSlot();
        try {
            ToolLog log = sessions.get(session, id -> new ToolLog());
            synchronized (log) {
                log.startTurn();
                try {
                    return answer(session, cleanMessage(message), log);
                } catch (RuntimeException e) {
                    LOG.warn("The language model call failed", e);
                    return new ChatReply(session, "The language model is not available now (" + shortReason(e)
                            + "). The REST API under /api still works and gives the same numbers.",
                            false, toolNames(log), List.of());
                }
            }
        } finally {
            slots.release();
        }
    }

    private void acquireSlot() {
        try {
            if (!slots.tryAcquire(SLOT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ChatBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatBusyException();
        }
    }

    static String cleanMessage(String message) {
        return CONTROL.matcher(message).replaceAll(" ").trim();
    }

    private ChatReply answer(String session, String message, ToolLog log) {
        String reply = ask(session, message, log);
        List<String> unsupported = NumberCheck.unsupported(reply, log.allJson());
        if (!unsupported.isEmpty()) {
            LOG.info("Numbers not in the tool results: {}. The agent asks again. Answer: {}", unsupported, reply);
            reply = ask(session, "Your last answer has numbers that are not in the tool results: "
                    + String.join(", ", unsupported) + ". Write the answer again. Use only numbers from the tool "
                    + "results. Do not calculate new numbers.", log);
            unsupported = NumberCheck.unsupported(reply, log.allJson());
        }
        if (!unsupported.isEmpty()) {
            return new ChatReply(session, templateAnswer(log), false, toolNames(log), unsupported);
        }
        return new ChatReply(session, OutputGuard.neutralize(reply, log.allJson()), true, toolNames(log), List.of());
    }

    private String ask(String session, String message, ToolLog log) {
        String content = client.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, session))
                .toolContext(Map.of(ToolLog.KEY, log))
                .call()
                .content();
        return content == null ? "" : content.trim();
    }

    private static List<String> toolNames(ToolLog log) {
        return log.turnCalls().stream().map(ToolLog.Call::tool).toList();
    }

    /** The answer without the LLM text. It shows the raw result of each tool call. */
    static String templateAnswer(ToolLog log) {
        StringBuilder text = new StringBuilder("I could not check the numbers in my written answer, so I show the "
                + "results of the tools. Each number below comes from the data.\n");
        for (ToolLog.Call call : log.turnCalls()) {
            String result = call.json();
            text.append("\n").append(call.tool()).append(": ")
                    .append(result.length() > MAX_DATA_TEXT ? result.substring(0, MAX_DATA_TEXT) + " ..." : result)
                    .append("\n");
        }
        if (log.turnCalls().isEmpty()) {
            text.append("\nNo tool was called, so there is no data for this question.");
        }
        return text.toString();
    }

    private static String shortReason(RuntimeException e) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }
}

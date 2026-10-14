package com.example.airportagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One chat turn: ask the LLM, check the numbers of the answer, ask again one time, then use a template answer.
 * The LLM chooses tools and writes the text. It does not calculate (DESIGN.md, section 8).
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    static final int MAX_SESSIONS = 200;
    static final int MAX_DATA_TEXT = 1500;
    /** The value of the key setting when the environment variable GEMINI_API_KEY is not set. */
    static final String NO_KEY = "not-set";

    private final ChatClient client;
    private final JsonMapper json;
    private final boolean keySet;
    private final Map<String, ToolLog> logs = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ToolLog> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    public ChatService(ChatClient client, JsonMapper json, @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        this.client = client;
        this.json = json;
        this.keySet = !apiKey.isBlank() && !NO_KEY.equals(apiKey);
    }

    public ChatReply chat(String sessionId, String message) {
        String session = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        if (!keySet) {
            return new ChatReply(session, "The chat needs a Gemini API key. Set the environment variable GEMINI_API_KEY "
                    + "and restart the app. The REST API under /api works without the key.", false, List.of(), List.of());
        }
        ToolLog log = logs.computeIfAbsent(session, id -> new ToolLog());
        synchronized (log) {
            log.startTurn();
            try {
                return answer(session, message, log);
            } catch (RuntimeException e) {
                LOG.warn("The language model call failed: {}", e.toString());
                return new ChatReply(session, "The language model is not available now (" + shortReason(e)
                        + "). The REST API under /api still works and gives the same numbers.",
                        false, toolNames(log), List.of());
            }
        }
    }

    private ChatReply answer(String session, String message, ToolLog log) {
        String reply = ask(session, message, log);
        List<String> unsupported = NumberCheck.unsupported(reply, sources(log, message));
        if (!unsupported.isEmpty()) {
            LOG.info("Numbers not in the tool results: {}. The agent asks again.", unsupported);
            reply = ask(session, "Your last answer has numbers that are not in the tool results: "
                    + String.join(", ", unsupported) + ". Write the answer again. Use only numbers from the tool "
                    + "results. Do not calculate new numbers.", log);
            unsupported = NumberCheck.unsupported(reply, sources(log, message));
        }
        if (!unsupported.isEmpty()) {
            return new ChatReply(session, templateAnswer(log), false, toolNames(log), unsupported);
        }
        return new ChatReply(session, reply, true, toolNames(log), List.of());
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

    private static List<String> sources(ToolLog log, String message) {
        List<String> sources = new ArrayList<>(log.allJson());
        sources.add(message);
        return sources;
    }

    private static List<String> toolNames(ToolLog log) {
        return log.turnCalls().stream().map(ToolLog.Call::tool).toList();
    }

    /** The answer without the LLM text. It shows the notes and the data of the tool results. */
    String templateAnswer(ToolLog log) {
        StringBuilder text = new StringBuilder("I could not check the numbers in my written answer, so I show the "
                + "results of the tools. Each number below comes from the data.\n");
        for (ToolLog.Call call : log.turnCalls()) {
            text.append("\n").append(call.tool()).append(": ");
            JsonNode root = json.readTree(call.json());
            text.append(root.path("status").asString()).append("\n");
            if (root.hasNonNull("reason")) {
                text.append("Reason: ").append(root.path("reason").asString()).append("\n");
            }
            for (String field : List.of("assumptions", "uncertainties", "outOfScope")) {
                for (JsonNode note : root.path("envelope").path(field)) {
                    text.append("- ").append(field).append(": ").append(note.asString()).append("\n");
                }
            }
            String data = root.path("data").toString();
            text.append("Data: ").append(data.length() > MAX_DATA_TEXT ? data.substring(0, MAX_DATA_TEXT) + " ..." : data)
                    .append("\n");
        }
        if (log.turnCalls().isEmpty()) {
            text.append("\nNo tool was called, so there is no data for this question.");
        }
        return text.toString();
    }

    private static String shortReason(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }
}

package com.example.airportagent.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The chat interface. The chat page sends one question for each request. */
@RestController
public class ChatController {

    static final int MAX_MESSAGE_LENGTH = 1000;

    public record ChatRequest(String sessionId, String message) {}

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "The message is empty."));
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "The message is too long. The limit is " + MAX_MESSAGE_LENGTH + " characters."));
        }
        return ResponseEntity.ok(chat.chat(request.sessionId(), request.message().trim()));
    }
}

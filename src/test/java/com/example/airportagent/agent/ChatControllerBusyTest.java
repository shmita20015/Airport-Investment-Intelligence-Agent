package com.example.airportagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The controller turns a busy chat into HTTP 503 with a Retry-After header. */
class ChatControllerBusyTest {

    @Test
    void aBusyChatGivesStatus503WithRetryAfter() {
        ChatService chat = mock(ChatService.class);
        when(chat.chat(any(), any())).thenThrow(new ChatBusyException());

        ResponseEntity<?> response = new ChatController(chat).chat(new ChatController.ChatRequest(null, "Hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(response.getBody().toString()).contains("busy");
    }
}

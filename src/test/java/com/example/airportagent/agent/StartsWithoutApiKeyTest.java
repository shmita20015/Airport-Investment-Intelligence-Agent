package com.example.airportagent.agent;

import com.example.airportagent.service.AirportQueryService;
import com.example.airportagent.service.QueryResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** The app must start with no Gemini key. The REST API and the engine must work. Only the chat needs the key. */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=not-set")
class StartsWithoutApiKeyTest {

    @Autowired
    private AirportQueryService service;

    @Autowired
    private ChatService chat;

    /** Checks that the engine works without a key. */
    @Test
    void theEngineWorksWithoutAKey() {
        assertThat(service.longHaul("ANC", 2500)).isInstanceOf(QueryResult.Ok.class);
    }

    /** Checks that the chat gives a clear message without a key. */
    @Test
    void theChatGivesAClearMessageWithoutAKey() {
        ChatReply reply = chat.chat("s1", "Hello");

        assertThat(reply.verified()).isFalse();
        assertThat(reply.reply()).contains("GEMINI_API_KEY").contains("/api");
    }
}

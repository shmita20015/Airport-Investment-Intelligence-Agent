package com.example.airportagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the chat endpoint on a real server. No LLM key is set, so no LLM call is possible. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.google.genai.api-key=not-set")
class ChatControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/chat"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anEmptyMessageGivesStatus400() throws Exception {
        HttpResponse<String> response = post("{\"message\":\"  \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asString()).contains("empty");
    }

    @Test
    void aTooLongMessageGivesStatus400() throws Exception {
        HttpResponse<String> response = post("{\"message\":\"" + "a".repeat(ChatController.MAX_MESSAGE_LENGTH + 1) + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void aMessageGetsAReplyWithASessionId() throws Exception {
        HttpResponse<String> response = post("{\"message\":\"Hello\"}");

        JsonNode body = JSON.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.path("sessionId").asString()).isNotBlank();
        assertThat(body.path("reply").asString()).contains("GEMINI_API_KEY");
    }

    @Test
    void theChatPageIsServed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Airport Investment Intelligence Agent").contains("SpeechRecognition");
        // The scoring engine panel: ranked table, weights, live status and sources.
        assertThat(response.body()).contains("id=\"rank\"").contains("id=\"weights\"").contains("id=\"live\"")
                .contains("id=\"sources\"").contains("/api/rank").contains("/api/methodology");
    }
}

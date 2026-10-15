package com.example.airportagent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The filter is part of the running app. The limits come from the settings. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.google.genai.api-key=not-set", "airport.limits.chat-requests-per-minute=2",
                "airport.limits.api-requests-per-minute=3", "airport.limits.max-request-bytes=300"})
class RateLimitIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private HttpResponse<String> postChat(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/chat"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Checks that the chat is limited for one client. */
    @Test
    void theChatIsLimitedForOneClient() throws Exception {
        assertThat(postChat("{\"message\":\"Hello\"}").statusCode()).isEqualTo(200);
        assertThat(postChat("{\"message\":\"Hello\"}").statusCode()).isEqualTo(200);

        HttpResponse<String> third = postChat("{\"message\":\"Hello\"}");

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).isPresent();
    }

    /** Checks that the api is limited for one client. */
    @Test
    void theApiIsLimitedForOneClient() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/airport/ANC/long-haul")).GET().build();
        int last = 0;
        for (int i = 0; i < 4; i++) {
            last = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }

        assertThat(last).isEqualTo(429);
    }

}

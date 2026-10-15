package com.example.airportagent.api;

import com.example.airportagent.config.LimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(new LimitProperties(3, 5, 4, 100, 200, 60));

    private MockHttpServletResponse call(String path, String address, byte[] body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(path.equals("/chat") ? "POST" : "GET", path);
        request.setRemoteAddr(address);
        if (body != null) {
            request.setContent(body);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() == null) {
            return response;
        }
        response.setStatus(200);
        return response;
    }

    private MockHttpServletResponse chat(String address) throws Exception {
        return call("/chat", address, "{\"message\":\"Hi\"}".getBytes());
    }

    @Test
    void aClientAddressCanSendOnlyTheAllowedNumberOfChatRequestsInAMinute() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(chat("10.0.0.1").getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = chat("10.0.0.1");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(rejected.getHeader("Retry-After"))).isBetween(1, 61);
        assertThat(rejected.getContentAsString()).contains("Too many requests");
    }

    @Test
    void anotherClientAddressIsNotAffected() throws Exception {
        for (int i = 0; i < 4; i++) {
            chat("10.0.0.1");
        }

        assertThat(chat("10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    void theApiAndTheChatHaveSeparateLimits() throws Exception {
        for (int i = 0; i < 4; i++) {
            chat("10.0.0.3");
        }

        assertThat(call("/api/rank", "10.0.0.3", null).getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            call("/api/rank", "10.0.0.3", null);
        }
        assertThat(call("/api/rank", "10.0.0.3", null).getStatus()).isEqualTo(429);
    }

    @Test
    void aTooLargeChatRequestIsRejectedBeforeTheAppReadsIt() throws Exception {
        MockHttpServletResponse response = call("/chat", "10.0.0.4", new byte[101]);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void aChatRequestWithoutAContentLengthIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(411);
    }

    @Test
    void thePageAndOtherPathsAreNotLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("/", "10.0.0.6", null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void aRejectedRequestDoesNotReachTheApp() throws IOException, jakarta.servlet.ServletException {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request("10.0.0.7"), new MockHttpServletResponse(), new MockFilterChain());
        }
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("10.0.0.7"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat");
        request.setRemoteAddr(address);
        request.setContent("{}".getBytes());
        return request;
    }
}

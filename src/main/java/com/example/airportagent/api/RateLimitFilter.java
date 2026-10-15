package com.example.airportagent.api;

import com.example.airportagent.config.LimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Protects the app against a heavy load. Each client address has a token bucket for the chat and one for the
 * REST API. A request without a token gets HTTP 429 and a Retry-After header. A chat request that is too large,
 * or has no Content-Length, is rejected before the app reads the body.
 *
 * <p>The client address is the address of the connection. Behind a reverse proxy, the proxy must set it
 * (for example with server.forward-headers-strategy), or all clients share one bucket.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/chat";
    private static final String API_PREFIX = "/api/";

    private final LimitProperties limits;
    private final Cache<String, Bucket> chatBuckets = newBucketCache();
    private final Cache<String, Bucket> apiBuckets = newBucketCache();

    /** Creates the filter with the limits from the settings. */
    public RateLimitFilter(LimitProperties limits) {
        this.limits = limits;
    }

    /** A cache of buckets with a size limit and an idle time, so that the filter does not grow without a limit. */
    private static Cache<String, Bucket> newBucketCache() {
        return Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    }

    /** Limits the chat and the API requests of one client address. Other paths pass without a check. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean chat = path.equals(CHAT_PATH);
        if (!chat && !path.startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (chat && !bodySizeAllowed(request, response)) {
            return;
        }
        Cache<String, Bucket> buckets = chat ? chatBuckets : apiBuckets;
        int perMinute = chat ? limits.chatRequestsPerMinute() : limits.apiRequestsPerMinute();
        Bucket bucket = buckets.get(request.getRemoteAddr(), address -> newBucket(perMinute));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        reject(response, 429, "Too many requests. Wait " + retryAfter + " seconds and try again.");
    }

    /** A token bucket that gives the allowed number of requests in a minute. */
    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }

    /**
     * Checks the Content-Length of a chat request. If it is not allowed, it writes HTTP 411 or 413 and returns false.
     */
    private boolean bodySizeAllowed(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            reject(response, 411, "The request needs a Content-Length header.");
            return false;
        }
        if (length > limits.maxRequestBytes()) {
            reject(response, 413, "The request is too large. The limit is " + limits.maxRequestBytes() + " bytes.");
            return false;
        }
        return true;
    }

    /** Writes an error answer with a status and a message as JSON. */
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}

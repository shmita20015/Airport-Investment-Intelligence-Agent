package com.example.airportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits that protect the app against a heavy load (docs/DESIGN-full.md, section 22).
 *
 * @param chatRequestsPerMinute a chat request calls the LLM. This limit is for one client address.
 * @param apiRequestsPerMinute  the limit for the REST API, for one client address
 * @param maxConcurrentChats    the number of chat requests that run at the same time in the whole app
 * @param maxRequestBytes       the maximum size of the body of a chat request
 * @param maxSessions           the number of chat sessions that the app keeps
 * @param sessionIdleMinutes    a session that nobody uses for this time is deleted, with its chat history
 */
@ConfigurationProperties(prefix = "airport.limits")
public record LimitProperties(int chatRequestsPerMinute, int apiRequestsPerMinute, int maxConcurrentChats,
                              int maxRequestBytes, int maxSessions, int sessionIdleMinutes) {}

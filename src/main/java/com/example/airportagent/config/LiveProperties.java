package com.example.airportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the live FAA call.
 *
 * @param cacheSeconds the app keeps the answer of the FAA service for this time, so many questions make one call
 */
@ConfigurationProperties(prefix = "airport.live")
public record LiveProperties(String faaStatusUrl, long timeoutMs, long cacheSeconds) {}

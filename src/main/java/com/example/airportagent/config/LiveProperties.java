package com.example.airportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the live FAA call. */
@ConfigurationProperties(prefix = "airport.live")
public record LiveProperties(String faaStatusUrl, long timeoutMs) {}

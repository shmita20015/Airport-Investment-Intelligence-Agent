package com.example.airportagent.config;

import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.ExpertNotes;
import com.example.airportagent.data.FaaStatusClient;
import com.example.airportagent.data.HttpFaaStatusClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class DataConfig {

    /** The saved facts, loaded one time when the application starts. */
    @Bean
    public AirportDataset airportDataset() {
        return new AirportDataLoader().load();
    }

    @Bean
    public ExpertNotes expertNotes() {
        return ExpertNotes.load();
    }

    @Bean
    public FaaStatusClient faaStatusClient(LiveProperties properties) {
        return new HttpFaaStatusClient(properties.faaStatusUrl(), Duration.ofMillis(properties.timeoutMs()));
    }

    /** The clock of the app. A test can replace it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

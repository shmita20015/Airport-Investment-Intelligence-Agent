package com.example.airportagent.config;

import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.FaaStatusClient;
import com.example.airportagent.data.HttpFaaStatusClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class DataConfig {

    /**
     * The saved facts, loaded one time when the application starts. If the refresh job saved new files,
     * the app reads them. Otherwise it reads the files in the jar.
     */
    @Bean
    public AirportDataset airportDataset(RefreshProperties refresh) {
        Path folder = Path.of(refresh.dataDir());
        return new AirportDataLoader(Files.isRegularFile(folder.resolve("manifest.json")) ? folder : null).load();
    }

    /** The client of the live FAA status service, with the time limits from the settings. */
    @Bean
    public FaaStatusClient faaStatusClient(LiveProperties properties) {
        return new HttpFaaStatusClient(properties.faaStatusUrl(), Duration.ofMillis(properties.timeoutMs()),
                Duration.ofSeconds(properties.cacheSeconds()));
    }

    /** The clock of the app. A test can replace it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

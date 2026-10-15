package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Envelope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeFactoryTest {

    private static AirportDataset data;
    private static RankedSet rankedSet;

    @BeforeAll
    static void load() {
        data = new AirportDataLoader().load();
        rankedSet = new RankedSet(data, new ScoringProperties(Map.of(), Map.of(), 100_000, 1_000, 2_500, Map.of()));
    }

    private static Envelope envelopeAt(Duration afterGeneration) {
        Instant now = Instant.parse(data.info().generated()).plus(afterGeneration);
        EnvelopeFactory factory = new EnvelopeFactory(data, rankedSet, Clock.fixed(now, ZoneOffset.UTC));
        return factory.build("general", Map.of(), Confidence.HIGH, List.of(), List.of(), List.of());
    }

    @Test
    void freshDataHasNoAgeWarning() {
        assertThat(envelopeAt(Duration.ofDays(EnvelopeFactory.OLD_DATA_DAYS - 1)).uncertainties())
                .noneMatch(text -> text.contains("days old"));
    }

    @Test
    void oldDataGetsAnAgeWarningWithTheNumberOfDays() {
        assertThat(envelopeAt(Duration.ofDays(EnvelopeFactory.OLD_DATA_DAYS + 10)).uncertainties())
                .anyMatch(text -> text.contains((EnvelopeFactory.OLD_DATA_DAYS + 10) + " days old"));
    }

    @Test
    void everyEnvelopeNamesTheDataYearTheSizeLimitAndTheSources() {
        Envelope envelope = envelopeAt(Duration.ZERO);

        assertThat(envelope.assumptions().get(0)).contains("2025").contains("100,000");
        assertThat(envelope.sources()).anyMatch(text -> text.contains("T-100")).anyMatch(text -> text.contains("On-Time"));
        assertThat(envelope.asOf()).isEqualTo(data.info().generated());
    }
}

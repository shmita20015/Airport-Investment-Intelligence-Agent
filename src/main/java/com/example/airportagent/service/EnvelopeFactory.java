package com.example.airportagent.service;

import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Envelope;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the envelope of a result: the assumptions, the uncertainties and the sources. */
@Component
public class EnvelopeFactory {

    /** Data older than this gets an uncertainty text. */
    static final long OLD_DATA_DAYS = 90;
    static final String NO_PROFIT = "The index does not include cost, revenue or profit. It is a screening tool, "
            + "not an investment decision.";

    private final AirportDataset data;
    private final RankedSet rankedSet;
    private final Clock clock;

    public EnvelopeFactory(AirportDataset data, RankedSet rankedSet, Clock clock) {
        this.data = data;
        this.rankedSet = rankedSet;
        this.clock = clock;
    }

    public Envelope build(String profile, Map<String, String> parameters, Confidence confidence,
                          List<String> extraAssumptions, List<String> extraUncertainties, List<String> outOfScope) {
        int year = data.info().dataYear();
        List<String> assumptions = new ArrayList<>();
        assumptions.add("The data year is " + year + ". Scores are percentiles among the " + rankedSet.size()
                + " US airports with at least " + String.format("%,d", rankedSet.minEnplanements())
                + " enplanements in " + year + " (the ranked set).");
        assumptions.add("The weights of the score are heuristics. They are not fitted to data.");
        assumptions.add("Traffic data (BTS T-100) covers US airlines only. Delay data (BTS On-Time) covers large "
                + "reporting airlines only. T-100 counts flight segments, not trips.");
        assumptions.addAll(extraAssumptions);

        List<String> uncertainties = new ArrayList<>(extraUncertainties);
        long age = ageInDays();
        if (age > OLD_DATA_DAYS) {
            uncertainties.add("The data files are " + age + " days old. The data may be old.");
        }

        List<String> sources = List.of(
                "BTS T-100 Segment (US carriers), " + year,
                "BTS On-Time Performance, " + year,
                "FAA enplanements, CY2023 to CY2025",
                "OurAirports (airports and runways)");
        return new Envelope(assumptions, uncertainties, outOfScope, confidence, sources,
                data.info().generated(), profile == null ? "" : profile, parameters);
    }

    /** An envelope for a result that has no score (for example a name lookup). */
    public Envelope minimal() {
        return build(null, Map.of(), Confidence.HIGH, List.of(), List.of(), List.of());
    }

    private long ageInDays() {
        try {
            return Duration.between(Instant.parse(data.info().generated()), clock.instant()).toDays();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}

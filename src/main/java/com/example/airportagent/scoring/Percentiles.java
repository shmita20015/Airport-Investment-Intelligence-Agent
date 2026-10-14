package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Kpi;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Percentile ranks of KPI values among a set of airports (the peer set).
 *
 * <p>Formula: 100 x (values below x + 0.5 x values equal to x) / N.
 * Missing values are not in the peer set. This class has no Spring and no AI code.
 */
public final class Percentiles {

    private final Map<Kpi, double[]> peerValues = new EnumMap<>(Kpi.class);

    private Percentiles() {}

    public static Percentiles of(Collection<AirportMetrics> peers) {
        Percentiles result = new Percentiles();
        for (Kpi kpi : Kpi.values()) {
            double[] values = peers.stream()
                    .map(peer -> peer.kpi(kpi).number())
                    .filter(OptionalDouble::isPresent)
                    .mapToDouble(OptionalDouble::getAsDouble)
                    .toArray();
            result.peerValues.put(kpi, values);
        }
        return result;
    }

    /** The percentile of a value from 0 to 100. Empty if no peer has this KPI. */
    public OptionalDouble rank(Kpi kpi, double value) {
        double[] values = peerValues.get(kpi);
        if (values.length == 0) {
            return OptionalDouble.empty();
        }
        int below = 0;
        int equal = 0;
        for (double peer : values) {
            if (peer < value) {
                below++;
            } else if (peer == value) {
                equal++;
            }
        }
        return OptionalDouble.of(100.0 * (below + 0.5 * equal) / values.length);
    }
}

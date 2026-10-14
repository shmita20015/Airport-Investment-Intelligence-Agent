package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Kpi;

import java.util.Arrays;
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

    private final Map<Kpi, double[]> sortedValues = new EnumMap<>(Kpi.class);

    private Percentiles() {}

    public static Percentiles of(Collection<AirportMetrics> peers) {
        Percentiles result = new Percentiles();
        for (Kpi kpi : Kpi.values()) {
            double[] values = peers.stream()
                    .map(peer -> peer.kpi(kpi).number())
                    .filter(OptionalDouble::isPresent)
                    .mapToDouble(OptionalDouble::getAsDouble)
                    .sorted()
                    .toArray();
            result.sortedValues.put(kpi, values);
        }
        return result;
    }

    /** The percentile of a value from 0 to 100. Empty if no peer has this KPI. */
    public OptionalDouble rank(Kpi kpi, double value) {
        double[] sorted = sortedValues.get(kpi);
        if (sorted.length == 0) {
            return OptionalDouble.empty();
        }
        int below = lowerBound(sorted, value);
        int belowOrEqual = upperBound(sorted, value);
        int equal = belowOrEqual - below;
        return OptionalDouble.of(100.0 * (below + 0.5 * equal) / sorted.length);
    }

    public int peerCount(Kpi kpi) {
        return sortedValues.get(kpi).length;
    }

    /** The number of values that are less than x. */
    private static int lowerBound(double[] sorted, double x) {
        int index = Arrays.binarySearch(sorted, x);
        if (index < 0) {
            return -index - 1;
        }
        while (index > 0 && sorted[index - 1] == x) {
            index--;
        }
        return index;
    }

    /** The number of values that are less than or equal to x. */
    private static int upperBound(double[] sorted, double x) {
        int index = Arrays.binarySearch(sorted, x);
        if (index < 0) {
            return -index - 1;
        }
        while (index < sorted.length - 1 && sorted[index + 1] == x) {
            index++;
        }
        return index + 1;
    }
}

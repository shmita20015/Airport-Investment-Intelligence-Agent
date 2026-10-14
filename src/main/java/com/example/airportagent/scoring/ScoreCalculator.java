package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Contribution;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;
import com.example.airportagent.domain.Score;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Deterministic scoring and ranking. This class has no Spring and no AI code.
 *
 * <p>Rules:
 * <ul>
 *   <li>A KPI becomes a percentile among the peer set.</li>
 *   <li>A component is the mean of the percentiles of its available KPIs.</li>
 *   <li>A missing KPI or component is removed. The weight of a missing component goes to the other components.</li>
 *   <li>The coverage counts missing KPIs too. The weight of a component is shared equally among its KPIs.</li>
 *   <li>The score is the weighted mean of the available components.</li>
 * </ul>
 */
public final class ScoreCalculator {

    /** The value of each component with data. A component without data is not in the map. */
    public Map<Component, Double> componentValues(AirportMetrics airport, Percentiles peers) {
        Map<Component, Double> values = new EnumMap<>(Component.class);
        for (Component component : Component.values()) {
            double sum = 0;
            int count = 0;
            for (Kpi kpi : component.kpis()) {
                OptionalDouble raw = airport.kpi(kpi).number();
                if (raw.isEmpty()) {
                    continue;
                }
                OptionalDouble percentile = peers.rank(kpi, raw.getAsDouble());
                if (percentile.isPresent()) {
                    sum += percentile.getAsDouble();
                    count++;
                }
            }
            if (count > 0) {
                values.put(component, sum / count);
            }
        }
        return values;
    }

    /** The score of one airport. Empty if no component with weight has data. */
    public Optional<Score> score(AirportMetrics airport, WeightProfile profile, Percentiles peers) {
        Map<Component, Double> values = componentValues(airport, peers);

        double totalWeight = 0;
        double usedWeight = 0;
        double kpiCoveredWeight = 0;
        List<Component> missing = new ArrayList<>();
        List<Kpi> missingKpis = new ArrayList<>();
        for (Component component : Component.values()) {
            double weight = profile.weight(component);
            if (weight <= 0) {
                continue;
            }
            totalWeight += weight;
            int available = 0;
            for (Kpi kpi : component.kpis()) {
                if (airport.kpi(kpi).number().isPresent() && peers.peerCount(kpi) > 0) {
                    available++;
                } else {
                    missingKpis.add(kpi);
                }
            }
            // The weight of a component is shared equally among its KPIs (coverage only, not the score value).
            kpiCoveredWeight += weight * available / component.kpis().size();
            if (values.containsKey(component)) {
                usedWeight += weight;
            } else {
                missing.add(component);
            }
        }
        if (usedWeight <= 0) {
            return Optional.empty();
        }

        List<Contribution> contributions = new ArrayList<>();
        double scoreValue = 0;
        for (Component component : Component.values()) {
            Double componentValue = values.get(component);
            double weight = profile.weight(component);
            if (componentValue == null || weight <= 0) {
                continue;
            }
            double effectiveWeight = weight / usedWeight;
            double points = componentValue * effectiveWeight;
            scoreValue += points;
            contributions.add(new Contribution(component, componentValue, effectiveWeight, points,
                    usesProxy(airport, component)));
        }
        contributions.sort(Comparator.comparingDouble(Contribution::points).reversed());

        double coverage = kpiCoveredWeight / totalWeight;
        return Optional.of(new Score(airport.iata(), profile.name(), scoreValue, coverage,
                Confidence.fromCoverage(coverage), contributions, missing, missingKpis));
    }

    /**
     * Rank airports. The peer set for the percentiles is the given collection.
     * Order: score (high to low), then passengers (high to low), then IATA code.
     * Airports without a score are not in the result.
     */
    public List<Score> rank(Collection<AirportMetrics> airports, WeightProfile profile, int topN) {
        Percentiles peers = Percentiles.of(airports);
        record Entry(Score score, double passengers) {}

        List<Entry> entries = new ArrayList<>();
        for (AirportMetrics airport : airports) {
            score(airport, profile, peers)
                    .ifPresent(score -> entries.add(new Entry(score, airport.passengers())));
        }
        entries.sort(Comparator
                .comparingDouble((Entry e) -> e.score().value()).reversed()
                .thenComparing(Comparator.comparingDouble(Entry::passengers).reversed())
                .thenComparing(e -> e.score().iata()));

        return entries.stream().limit(Math.max(topN, 0)).map(Entry::score).toList();
    }

    private static boolean usesProxy(AirportMetrics airport, Component component) {
        return component.kpis().stream()
                .anyMatch(kpi -> airport.kpi(kpi) instanceof MetricValue.Proxy);
    }
}

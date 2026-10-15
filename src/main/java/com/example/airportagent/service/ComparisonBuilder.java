package com.example.airportagent.service;

import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.service.Views.AirportProfileView;
import com.example.airportagent.service.Views.KpiComparison;
import com.example.airportagent.service.Views.KpiView;
import com.example.airportagent.service.Views.Placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides which airport is higher in a comparison. Code decides this, so the LLM does not judge numbers.
 * A higher percentile always means more pressure and a higher contribution to the score.
 */
final class ComparisonBuilder {

    /** A percentile gap below this value is a tie. */
    static final double TIE_POINTS = 0.05;

    private ComparisonBuilder() {}

    /**
     * The airports sorted by the focus value, from high to low. An airport without a value is not in the list.
     *
     * @param congestionOnly true if the focus is the congestion component, false if it is the score of a profile
     */
    static List<Placement> focusOrder(List<AirportProfileView> views, boolean congestionOnly) {
        List<Placement> values = new ArrayList<>();
        for (AirportProfileView view : views) {
            Double value = congestionOnly ? view.components().get(Component.CONGESTION)
                    : view.score() == null ? null : view.score().value();
            if (value != null) {
                values.add(new Placement(0, view.airport().iata(), value));
            }
        }
        values.sort(Comparator.comparingDouble(Placement::value).reversed());
        List<Placement> ordered = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            ordered.add(new Placement(i + 1, values.get(i).iata(), values.get(i).value()));
        }
        return ordered;
    }

    /** The distance in points between the first and the last airport of the order, or null if there are less than two. */
    static Double focusGap(List<Placement> order) {
        return order.size() < 2 ? null : order.get(0).value() - order.get(order.size() - 1).value();
    }

    /** For each KPI that at least two airports have: the highest airport, the lowest airport and the gap. */
    static List<KpiComparison> kpiComparisons(List<AirportProfileView> views) {
        List<KpiComparison> result = new ArrayList<>();
        for (Kpi kpi : Kpi.values()) {
            List<Placement> percentiles = new ArrayList<>();
            for (AirportProfileView view : views) {
                Optional<KpiView> kpiView = view.kpis().stream()
                        .filter(k -> k.kpi() == kpi && k.percentile() != null).findFirst();
                kpiView.ifPresent(k -> percentiles.add(new Placement(0, view.airport().iata(), k.percentile())));
            }
            if (percentiles.size() < 2) {
                continue;
            }
            percentiles.sort(Comparator.comparingDouble(Placement::value).reversed());
            Placement highest = percentiles.get(0);
            Placement lowest = percentiles.get(percentiles.size() - 1);
            double gap = highest.value() - lowest.value();
            boolean tie = gap < TIE_POINTS;
            result.add(new KpiComparison(kpi, tie ? null : highest.iata(), tie ? null : lowest.iata(), gap, tie));
        }
        return result;
    }
}

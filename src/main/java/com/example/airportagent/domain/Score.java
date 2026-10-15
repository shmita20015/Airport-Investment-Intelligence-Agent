package com.example.airportagent.domain;

import java.util.List;

/**
 * A score from 0 to 100 with its trace.
 *
 * @param coverage        the share of the profile weight that had data, from 0 to 1. The weight of a component
 *                        is shared equally among its KPIs, so a missing KPI lowers the coverage.
 * @param contributions   the trace, sorted by points (high to low)
 * @param missingComponents components with weight in the profile but no data at all
 * @param missingKpis     KPIs of the components with weight that have no data
 */
public record Score(String iata, String profile, double value, double coverage, Confidence confidence,
                    List<Contribution> contributions, List<Component> missingComponents, List<Kpi> missingKpis) {

    /** Copies the lists, so that the record cannot change. */
    public Score {
        contributions = List.copyOf(contributions);
        missingComponents = List.copyOf(missingComponents);
        missingKpis = List.copyOf(missingKpis);
    }
}

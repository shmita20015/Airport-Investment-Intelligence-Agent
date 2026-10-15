package com.example.airportagent.domain;

import java.util.Map;

/**
 * The raw KPIs of one airport. Passengers are used only to break ties in a ranking.
 * A KPI that is not in the map is treated as missing.
 */
public record AirportMetrics(String iata, double passengers, Map<Kpi, MetricValue> kpis) {

    /** Copies the map, so that the record cannot change. */
    public AirportMetrics {
        kpis = Map.copyOf(kpis);
    }

    /** The value of a KPI. A KPI that is not in the map is Missing. */
    public MetricValue kpi(Kpi kpi) {
        return kpis.getOrDefault(kpi, new MetricValue.Missing("not in the data"));
    }
}

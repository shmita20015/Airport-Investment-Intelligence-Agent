package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;

import java.util.EnumMap;
import java.util.Map;

/** Helpers to build small, fixed data sets for the tests. */
final class TestData {

    private TestData() {}

    static AirportMetrics airport(String iata, double passengers, Object... kpiAndValue) {
        Map<Kpi, MetricValue> kpis = new EnumMap<>(Kpi.class);
        for (int i = 0; i < kpiAndValue.length; i += 2) {
            kpis.put((Kpi) kpiAndValue[i], new MetricValue.Direct(((Number) kpiAndValue[i + 1]).doubleValue(), "test"));
        }
        return new AirportMetrics(iata, passengers, kpis);
    }

    static WeightProfile profile(String name, Object... componentAndWeight) {
        Map<Component, Double> weights = new EnumMap<>(Component.class);
        for (int i = 0; i < componentAndWeight.length; i += 2) {
            weights.put((Component) componentAndWeight[i], ((Number) componentAndWeight[i + 1]).doubleValue());
        }
        return new WeightProfile(name, weights);
    }
}

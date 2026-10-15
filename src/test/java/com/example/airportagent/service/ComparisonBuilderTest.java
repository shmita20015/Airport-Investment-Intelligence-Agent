package com.example.airportagent.service;

import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.HubClass;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.service.Views.AirportProfileView;
import com.example.airportagent.service.Views.AirportRef;
import com.example.airportagent.service.Views.KpiComparison;
import com.example.airportagent.service.Views.KpiView;
import com.example.airportagent.service.Views.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ComparisonBuilderTest {

    private static AirportProfileView view(String iata, Double congestion, Double growthPercentile, Double delayPercentile) {
        List<KpiView> kpis = List.of(
                new KpiView(Kpi.GROWTH, 0.1, growthPercentile, "DIRECT", "test", null),
                new KpiView(Kpi.DELAY15_RATE, 0.2, delayPercentile, "DIRECT", "test", null));
        Map<Component, Double> components = congestion == null ? Map.of() : Map.of(Component.CONGESTION, congestion);
        return new AirportProfileView(new AirportRef(iata, iata, "City", "XX", HubClass.LARGE, 2), true, "congestion",
                null, components, kpis);
    }

    @Test
    void theAirportsAreSortedByTheFocusValueFromHighToLow() {
        List<Placement> order = ComparisonBuilder.focusOrder(
                List.of(view("AAA", 40.0, 1.0, 1.0), view("BBB", 60.0, 1.0, 1.0), view("CCC", 50.0, 1.0, 1.0)), true);

        assertThat(order).extracting(Placement::iata).containsExactly("BBB", "CCC", "AAA");
        assertThat(order).extracting(Placement::position).containsExactly(1, 2, 3);
        assertThat(ComparisonBuilder.focusGap(order)).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void anAirportWithoutAFocusValueIsLeftOutAndOneAirportHasNoGap() {
        List<Placement> order = ComparisonBuilder.focusOrder(
                List.of(view("AAA", 40.0, 1.0, 1.0), view("BBB", null, 1.0, 1.0)), true);

        assertThat(order).extracting(Placement::iata).containsExactly("AAA");
        assertThat(ComparisonBuilder.focusGap(order)).isNull();
    }

    @Test
    void eachKpiNamesTheHighestAirportAndTheGap() {
        List<KpiComparison> result = ComparisonBuilder.kpiComparisons(
                List.of(view("AAA", 40.0, 30.0, 80.0), view("BBB", 60.0, 55.0, 70.0)));

        KpiComparison growth = result.stream().filter(k -> k.kpi() == Kpi.GROWTH).findFirst().orElseThrow();
        KpiComparison delay = result.stream().filter(k -> k.kpi() == Kpi.DELAY15_RATE).findFirst().orElseThrow();
        assertThat(growth.highest()).isEqualTo("BBB");
        assertThat(growth.lowest()).isEqualTo("AAA");
        assertThat(growth.gapPercentilePoints()).isCloseTo(25.0, within(1e-9));
        assertThat(delay.highest()).isEqualTo("AAA");
        assertThat(result).extracting(KpiComparison::kpi).containsExactly(Kpi.DELAY15_RATE, Kpi.GROWTH);
    }

    @Test
    void aTinyGapIsATieWithNoHighestAirport() {
        List<KpiComparison> result = ComparisonBuilder.kpiComparisons(
                List.of(view("AAA", 40.0, 50.00, 10.0), view("BBB", 40.0, 50.01, 20.0)));

        KpiComparison growth = result.stream().filter(k -> k.kpi() == Kpi.GROWTH).findFirst().orElseThrow();
        assertThat(growth.tie()).isTrue();
        assertThat(growth.highest()).isNull();
        assertThat(growth.lowest()).isNull();
    }

    @Test
    void aKpiThatOnlyOneAirportHasIsNotCompared() {
        List<KpiComparison> result = ComparisonBuilder.kpiComparisons(
                List.of(view("AAA", 40.0, 30.0, null), view("BBB", 60.0, 55.0, 70.0)));

        assertThat(result).extracting(KpiComparison::kpi).containsExactly(Kpi.GROWTH);
    }
}

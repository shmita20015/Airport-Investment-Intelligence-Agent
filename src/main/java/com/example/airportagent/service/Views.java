package com.example.airportagent.service;

import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.HubClass;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.LiveStatus;
import com.example.airportagent.domain.Score;

import java.util.List;
import java.util.Map;

/** The data that the service returns. The REST API and the agent tools use these records. */
public final class Views {

    private Views() {}

    public record AirportRef(String iata, String name, String city, String state,
                             HubClass hubClass, int runwayCount) {

        public static AirportRef of(Airport airport) {
            return new AirportRef(airport.iata(), airport.name(), airport.city(), airport.state(),
                    airport.hubClass(), airport.runwayCount());
        }
    }

    /**
     * One KPI of an airport.
     *
     * @param value      the raw value, or null if missing
     * @param percentile the percentile among the ranked airports (0 to 100), or null if missing
     * @param quality    DIRECT, PROXY or MISSING
     * @param note       what a proxy stands for, or why the value is missing
     */
    public record KpiView(Kpi kpi, Double value, Double percentile, String quality, String source, String note) {}

    /**
     * @param inRankedSet false if the airport is below the size limit. Then it is not in rankings.
     * @param score       the score against the ranked airports, or null if no component has data
     * @param components  the value (0 to 100) of each component that has data
     */
    public record AirportProfileView(AirportRef airport, boolean inRankedSet, String profile, Score score,
                                     Map<Component, Double> components, List<KpiView> kpis) {}

    public record RankedItem(int rank, int nationalRank, AirportRef airport, Score score) {}

    /** @param weights the weights that the ranking used (after custom weights, if any), by component name */
    public record RankingView(String profile, String region, int rankedSetSize, int minEnplanements,
                              Map<String, Double> weights, List<RankedItem> items) {}

    /**
     * @param airports        each airport with its KPIs and score, in the order of the request
     * @param focusOrder      the airports sorted by the focus value, from high to low. Position 1 is the highest.
     * @param focusGapPoints  the distance between the first and the last airport of focusOrder, or null
     * @param kpiComparisons  for each KPI, which airport is higher and by how many percentile points
     */
    public record ComparisonView(String focus, String profile, List<AirportProfileView> airports,
                                 List<Placement> focusOrder, Double focusGapPoints,
                                 List<KpiComparison> kpiComparisons, List<String> warnings) {}

    /** The place of an airport in an order, with its value. */
    public record Placement(int position, String iata, double value) {}

    /**
     * @param highest the airport with the highest percentile, or null in a tie
     * @param lowest  the airport with the lowest percentile, or null in a tie
     * @param gapPercentilePoints the distance between the highest and the lowest percentile
     */
    public record KpiComparison(Kpi kpi, String highest, String lowest, double gapPercentilePoints, boolean tie) {}

    public record LongHaulView(AirportRef airport, int year, String table, int limitRequestedMiles, int limitUsedMiles,
                               boolean limitWasRounded, Double sharePassengerOnly, Double shareWithCargo,
                               double passengerDepartures, double cargoDepartures, double cargoShareOfDepartures) {}

    /**
     * @param index        the unmet demand index from 0 to 100
     * @param label        always PROXY
     * @param whyInPoints  the components in order of their points, as text
     * @param expertNote   a note from general knowledge, or null. It is not calculated from the data.
     */
    public record UnmetDemandView(AirportRef airport, double index, String label, String explanation, Score score,
                                  List<String> whyInPoints, String expertNote, String expertNoteSource) {}

    public record LiveStatusView(AirportRef airport, LiveStatus status) {}

    /** The method of the app: weights, limits and scope. For questions about how the score works. */
    public record MethodologyView(Map<String, Map<String, Double>> weightProfiles, Map<String, Double> unmetDemandWeights,
                                  int dataYear, int rankedAirports, long minEnplanements, int defaultLongHaulMiles,
                                  List<String> regions, String scope, List<String> notes) {}

    /** A possible match of a name to an airport. */
    public record Candidate(AirportRef airport, String matchedBy) {}

    /**
     * The result of a name lookup.
     *
     * @param resolved true if exactly one airport matches. Then best is set.
     */
    public record Resolution(String query, boolean resolved, Candidate best, List<Candidate> alternatives) {}
}

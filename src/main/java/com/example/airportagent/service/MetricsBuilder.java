package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.Rows.EnplanementRow;
import com.example.airportagent.data.Rows.OntimeRow;
import com.example.airportagent.data.Rows.TrafficRow;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;
import com.example.airportagent.scoring.LongHaulCalculator;
import com.example.airportagent.scoring.LongHaulCalculator.Bucket;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Calculates the raw KPIs of each airport from the saved facts (docs/DESIGN-full.md, section 6.1).
 * It does not rank. A KPI that cannot be calculated is Missing, with a reason. It is never zero.
 */
@Component
public class MetricsBuilder {

    static final String ONTIME = "BTS On-Time Performance";
    static final String T100 = "BTS T-100 Segment";
    static final String FAA = "FAA enplanements";
    private static final String NO_DEPARTURES = "no passenger departures in the T-100 data";

    /**
     * A long-haul share below this value is set to 0 in the KPI. A few positioning flights would otherwise
     * get a high percentile, because many airports have exactly 0. The long-haul tool still shows the real share.
     */
    static final double LONG_HAUL_NOISE_FLOOR = 0.01;

    private final ScoringProperties properties;

    public MetricsBuilder(ScoringProperties properties) {
        this.properties = properties;
    }

    /**
     * The KPIs of all airports in the data set.
     *
     * @param longHaulMiles distance limit of the long-haul share
     */
    public Map<String, AirportMetrics> buildAll(AirportDataset data, int longHaulMiles) {
        Map<String, AirportMetrics> result = new LinkedHashMap<>();
        for (Airport airport : data.airports().values()) {
            result.put(airport.iata(), build(data, airport, longHaulMiles));
        }
        return result;
    }

    /** The KPIs of one airport. The long-haul share counts passenger flights only. */
    public AirportMetrics build(AirportDataset data, Airport airport, int longHaulMiles) {
        String code = airport.iata();
        TrafficRow traffic = data.traffic().get(code);
        OntimeRow ontime = data.ontime().get(code);
        EnplanementRow enplanement = data.enplanements().get(code);

        Map<Kpi, MetricValue> kpis = new EnumMap<>(Kpi.class);
        kpis.put(Kpi.DELAY15_RATE, ontimeKpi(ontime, OntimeRow::delay15Rate));
        kpis.put(Kpi.CANCEL_RATE, ontimeKpi(ontime, OntimeRow::cancelRate));
        kpis.put(Kpi.TAXI_OUT_MIN, ontimeKpi(ontime, OntimeRow::avgTaxiOutMin));
        kpis.put(Kpi.OPS_PER_RUNWAY, opsPerRunway(traffic, airport.runwayCount()));
        kpis.put(Kpi.GROWTH, growth(enplanement));
        kpis.put(Kpi.LOAD_FACTOR, loadFactor(traffic));
        kpis.put(Kpi.PAX_PER_DEPARTURE, paxPerDeparture(traffic));
        kpis.put(Kpi.LONG_HAUL_SHARE, longHaul(data, code, longHaulMiles));
        kpis.put(Kpi.ENPLANEMENTS, enplanements(enplanement));

        return new AirportMetrics(code, size(enplanement, traffic), kpis);
    }

    /** The long-haul share and the details that the answer must show. */
    public LongHaulCalculator.Result longHaulResult(AirportDataset data, String code, int limitMiles,
                                                    boolean includeCargo) {
        List<Bucket> buckets = data.bucketsOf(code).stream()
                .map(row -> new Bucket(row.minMiles(), row.departuresPax(), row.departuresCargo()))
                .toList();
        return LongHaulCalculator.share(buckets, limitMiles, includeCargo);
    }

    private MetricValue longHaul(AirportDataset data, String code, int limitMiles) {
        if (data.bucketsOf(code).isEmpty()) {
            return new MetricValue.Missing("no T-100 departures in the data");
        }
        OptionalDouble share = longHaulResult(data, code, limitMiles, false).share();
        if (share.isEmpty()) {
            return new MetricValue.Missing("no departures in the T-100 data");
        }
        double value = share.getAsDouble() < LONG_HAUL_NOISE_FLOOR ? 0.0 : share.getAsDouble();
        return new MetricValue.Direct(value, T100 + " (shares below 1% are set to 0)");
    }

    private MetricValue ontimeKpi(OntimeRow ontime, Function<OntimeRow, OptionalDouble> column) {
        if (ontime == null) {
            return new MetricValue.Missing("no On-Time data (the airport has no large reporting carrier)");
        }
        if (ontime.flights() < properties.minOntimeFlights()) {
            return new MetricValue.Missing("fewer than " + properties.minOntimeFlights()
                    + " On-Time flights in the year (" + ontime.flights() + ")");
        }
        OptionalDouble value = column.apply(ontime);
        return value.isPresent()
                ? new MetricValue.Direct(value.getAsDouble(), ONTIME)
                : new MetricValue.Missing("value not in the On-Time data");
    }

    /** Operations per runway. It assumes that arrivals equal departures, so operations are 2 x departures. */
    private MetricValue opsPerRunway(TrafficRow traffic, int runwayCount) {
        if (noPassengerDepartures(traffic)) {
            return new MetricValue.Missing(NO_DEPARTURES);
        }
        if (runwayCount <= 0) {
            return new MetricValue.Missing("runway count not known");
        }
        return new MetricValue.Proxy(2 * traffic.departuresPax() / runwayCount, T100 + ", OurAirports",
                "runway load (arrivals assumed equal to departures, runways assumed independent)");
    }

    /** Growth per year from the enplanements of CY23 and CY25. */
    private MetricValue growth(EnplanementRow enplanement) {
        if (enplanement == null || enplanement.cy23().isEmpty() || enplanement.cy25().isEmpty()
                || enplanement.cy23().getAsLong() <= 0) {
            return new MetricValue.Missing("enplanements of CY23 or CY25 not available");
        }
        double ratio = (double) enplanement.cy25().getAsLong() / enplanement.cy23().getAsLong();
        return new MetricValue.Direct(Math.sqrt(ratio) - 1, FAA + " CY23 to CY25 (2-year yearly rate)");
    }

    /** The size of the airport: enplanements of CY25. */
    private MetricValue enplanements(EnplanementRow enplanement) {
        if (enplanement == null || enplanement.cy25().isEmpty()) {
            return new MetricValue.Missing("enplanements of CY25 not available");
        }
        return new MetricValue.Direct(enplanement.cy25().getAsLong(), FAA + " CY25");
    }

    private MetricValue loadFactor(TrafficRow traffic) {
        if (traffic == null || traffic.seats() <= 0) {
            return new MetricValue.Missing("no seats in the T-100 data");
        }
        return new MetricValue.Proxy(traffic.pax() / traffic.seats(), T100, "terminal pressure");
    }

    private MetricValue paxPerDeparture(TrafficRow traffic) {
        if (noPassengerDepartures(traffic)) {
            return new MetricValue.Missing(NO_DEPARTURES);
        }
        return new MetricValue.Proxy(traffic.pax() / traffic.departuresPax(), T100,
                "terminal strain (size of aircraft)");
    }

    private static boolean noPassengerDepartures(TrafficRow traffic) {
        return traffic == null || traffic.departuresPax() <= 0;
    }

    /** The size of the airport for ties and the size filter: enplanements of CY25, or T-100 passengers. */
    private static double size(EnplanementRow enplanement, TrafficRow traffic) {
        if (enplanement != null && enplanement.cy25().isPresent()) {
            return enplanement.cy25().getAsLong();
        }
        return traffic == null ? 0 : traffic.pax();
    }
}

package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.config.ExpertNotes;
import com.example.airportagent.data.FaaStatusClient;
import com.example.airportagent.data.Rows;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Contribution;
import com.example.airportagent.domain.Envelope;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.LiveStatus;
import com.example.airportagent.domain.MetricValue;
import com.example.airportagent.domain.Score;
import com.example.airportagent.scoring.LongHaulCalculator;
import com.example.airportagent.scoring.Percentiles;
import com.example.airportagent.scoring.ScoreCalculator;
import com.example.airportagent.scoring.WeightProfile;
import com.example.airportagent.service.Views.AirportProfileView;
import com.example.airportagent.service.Views.AirportRef;
import com.example.airportagent.service.Views.ComparisonView;
import com.example.airportagent.service.Views.KpiView;
import com.example.airportagent.service.Views.LiveStatusView;
import com.example.airportagent.service.Views.Placement;
import com.example.airportagent.service.Views.LongHaulView;
import com.example.airportagent.service.Views.MethodologyView;
import com.example.airportagent.service.Views.RankedItem;
import com.example.airportagent.service.Views.RankingView;
import com.example.airportagent.service.Views.Resolution;
import com.example.airportagent.service.Views.TrafficVolumeView;
import com.example.airportagent.service.Views.UnmetDemandView;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single facade of the app. The REST API and the agent tools call this class and nothing else.
 * It gives the same numbers to both (docs/DESIGN-full.md, section 4.3). It has no AI code.
 */
@Service
public class AirportQueryService {

    static final String T100_TABLE = "BTS T-100 Segment (US carriers), domestic and international flights";
    static final String DEFAULT_PROFILE = "general";
    static final int DEFAULT_TOP_N = 10;
    static final int MAX_TOP_N = 50;
    static final int MAX_COMPARE = 6;

    private final AirportDataset data;
    private final MetricsBuilder metrics;
    private final ScoringProperties properties;
    private final RankedSet rankedSet;
    private final EnvelopeFactory envelopes;
    private final AirportResolver resolver;
    private final FaaStatusClient liveClient;
    private final ExpertNotes expertNotes;
    private final RegionParser regions;
    private final ScoreCalculator calculator = new ScoreCalculator();
    private final Snapshot snapshot;

    /** Creates the service. It calculates the KPIs and the percentiles of all airports one time. */
    public AirportQueryService(AirportDataset data, MetricsBuilder metrics, ScoringProperties properties,
                               RankedSet rankedSet, EnvelopeFactory envelopes, AirportResolver resolver,
                               FaaStatusClient liveClient, ExpertNotes expertNotes) {
        this.data = data;
        this.metrics = metrics;
        this.properties = properties;
        this.rankedSet = rankedSet;
        this.envelopes = envelopes;
        this.resolver = resolver;
        this.liveClient = liveClient;
        this.expertNotes = expertNotes;
        this.regions = new RegionParser(properties, data);
        this.snapshot = buildSnapshot(data, metrics, properties, rankedSet);
    }

    // ------------------------------------------------------------------ name lookup

    /** Finds the airport for a name or a code. */
    public QueryResult<Resolution> resolveAirport(String text) {
        return resolver.resolve(text);
    }

    // ------------------------------------------------------------------ ranking

    /**
     * Rank the airports of the ranked set.
     *
     * @param region      a region name from the settings (for example NEW_ENGLAND), a state code (for example TX),
     *                    a comma list of both, or empty / ALL for the whole country. The percentiles stay national.
     * @param profileName a weight profile name (for example terminal). Empty means the default profile.
     * @param topN        the number of airports to return (1 to 50)
     */
    public QueryResult<RankingView> rank(String region, String profileName, Integer topN) {
        return rank(region, profileName, topN, null);
    }

    /**
     * Rank with custom weights (a what-if question).
     *
     * @param weights optional. Fixed weights for some components, for example "scale:0" or
     *                "congestion:0.5, growth:0.3". The other components share the rest in the ratio of the profile.
     */
    public QueryResult<RankingView> rank(String region, String profileName, Integer topN, String weights) {
        Optional<WeightProfile> known = profileFor(profileName);
        if (known.isEmpty()) {
            return unknownProfile(profileName);
        }
        WeightProfile base = known.get();
        WeightProfile profile;
        try {
            profile = CustomWeights.apply(base, weights);
        } catch (IllegalArgumentException e) {
            return new QueryResult.NotAvailable<>(e.getMessage(), CustomWeights.componentNames());
        }
        boolean custom = weights != null && !weights.isBlank();
        RegionParser.Filter regionFilter;
        try {
            regionFilter = regions.parse(region);
        } catch (IllegalArgumentException e) {
            return new QueryResult.NotAvailable<>(e.getMessage(), regions.knownRegions());
        }
        int limit = clampTopN(topN);

        List<Score> national = calculator.rank(snapshot.ranked(), profile, Integer.MAX_VALUE);

        List<RankedItem> items = new ArrayList<>();
        int regionRank = 0;
        for (int i = 0; i < national.size() && items.size() < limit; i++) {
            Score score = national.get(i);
            Airport airport = data.airports().get(score.iata());
            if (!regionFilter.contains(airport)) {
                continue;
            }
            items.add(new RankedItem(++regionRank, i + 1, AirportRef.of(airport), score));
        }
        String regionText = regionFilter.label();
        RankingView view = new RankingView(profile.name(), regionText, rankedSet.size(),
                (int) rankedSet.minEnplanements(), weightsOf(profile), items);

        List<Score> shown = items.stream().map(RankedItem::score).toList();
        Envelope envelope = envelopes.build(profile.name(),
                Map.of("region", regionText, "topN", String.valueOf(limit),
                        "minEnplanements", String.valueOf(rankedSet.minEnplanements()),
                        "longHaulMiles", String.valueOf(properties.defaultLongHaulMiles()),
                        "weights", custom ? weights.trim() : "profile " + base.name()),
                lowestConfidence(shown),
                rankingAssumptions(profile, base, custom),
                coverageNotes(shown, true),
                List.of(EnvelopeFactory.NO_PROFIT, "Airports below the size limit are not ranked."));
        if (items.isEmpty()) {
            return new QueryResult.NotAvailable<>("No ranked airport matches the region " + regionText + ".",
                    regions.knownRegions());
        }
        return new QueryResult.Ok<>(view, envelope);
    }

    // ------------------------------------------------------------------ one airport

    /** The KPIs, the components and the score of one airport. The text can be a code or a name. */
    public QueryResult<AirportProfileView> airportProfile(String code, String profileName) {
        return withAirport(code, airport -> airportProfile(airport, profileName));
    }

    /** The profile of an airport that is already resolved. */
    private QueryResult<AirportProfileView> airportProfile(Airport airport, String profileName) {
        Optional<WeightProfile> profile = profileFor(profileName);
        if (profile.isEmpty()) {
            return unknownProfile(profileName);
        }
        AirportProfileView view = profileView(airport, profile.get());

        List<String> missing = new ArrayList<>();
        if (view.score() == null) {
            missing.add("score: no component has data");
        }
        if (!view.inRankedSet()) {
            missing.add("The airport is below the size limit of " + String.format("%,d", rankedSet.minEnplanements())
                    + " enplanements. It is not in rankings. The score uses the ranked airports as peers.");
        }
        Envelope envelope = envelopes.build(profile.get().name(),
                Map.of("airport", airport.iata(), "longHaulMiles", String.valueOf(properties.defaultLongHaulMiles())),
                view.score() == null ? Confidence.LOW : view.score().confidence(),
                List.of(describe(profile.get())),
                view.score() == null ? List.of() : coverageNotes(List.of(view.score()), false),
                List.of(EnvelopeFactory.NO_PROFIT));
        return missing.isEmpty() ? new QueryResult.Ok<>(view, envelope)
                : new QueryResult.Partial<>(view, missing, envelope);
    }

    // ------------------------------------------------------------------ comparison

    /**
     * Compare airports.
     *
     * @param focus "congestion" compares the congestion component only. A profile name (for example terminal)
     *              compares the score of that profile.
     */
    public QueryResult<ComparisonView> compare(List<String> codes, String focus) {
        return compare(codes, focus, null);
    }

    /**
     * Compares airports. The result says which airport is higher, in the focus value and in each KPI.
     *
     * @param weights optional custom weights for a profile focus (see {@link #rank(String, String, Integer, String)}).
     *                They do not apply to the focus congestion.
     */
    public QueryResult<ComparisonView> compare(List<String> codes, String focus, String weights) {
        if (codes == null || codes.size() < 2) {
            return new QueryResult.NotAvailable<>("A comparison needs at least two airport codes.", List.of());
        }
        if (codes.size() > MAX_COMPARE) {
            return new QueryResult.NotAvailable<>("A comparison can have at most " + MAX_COMPARE + " airports.", List.of());
        }
        String focusName = focus == null || focus.isBlank() ? "congestion" : focus.trim().toLowerCase(Locale.ROOT);
        Optional<WeightProfile> known = "congestion".equals(focusName)
                ? Optional.of(new WeightProfile("congestion", Map.of(Component.CONGESTION, 1.0)))
                : profileFor(focusName);
        if (known.isEmpty()) {
            return unknownProfile(focusName);
        }
        WeightProfile profile;
        if (weights == null || weights.isBlank()) {
            profile = known.get();
        } else if ("congestion".equals(focusName)) {
            return new QueryResult.NotAvailable<>("Weights do not apply to the focus congestion. "
                    + "Use the focus terminal or general with weights.", List.of("terminal", "general"));
        } else {
            try {
                profile = CustomWeights.apply(known.get(), weights);
            } catch (IllegalArgumentException e) {
                return new QueryResult.NotAvailable<>(e.getMessage(), CustomWeights.componentNames());
            }
        }

        List<Airport> found = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            uniqueAirport(code).ifPresentOrElse(found::add, () -> unknown.add(code));
        }
        if (found.isEmpty()) {
            return notInData(String.join(", ", codes));
        }

        List<AirportProfileView> views = found.stream().map(a -> profileView(a, profile)).toList();

        List<String> warnings = new ArrayList<>();
        long hubClasses = found.stream().map(Airport::hubClass).distinct().count();
        if (hubClasses > 1) {
            warnings.add("The airports are in different FAA hub classes ("
                    + found.stream().map(a -> a.iata() + ": " + a.hubClass()).collect(Collectors.joining(", "))
                    + "). They have different sizes. Compare absolute values with care.");
        }
        for (AirportProfileView view : views) {
            if (!view.inRankedSet()) {
                warnings.add(view.airport().iata() + " is below the size limit. Its percentiles use the ranked airports as peers.");
            }
        }
        List<Placement> order = ComparisonBuilder.focusOrder(views, "congestion".equals(focusName));
        ComparisonView view = new ComparisonView(focusName, profile.name(), views, order,
                ComparisonBuilder.focusGap(order), ComparisonBuilder.kpiComparisons(views), warnings);

        List<Score> scores = views.stream().map(AirportProfileView::score).filter(s -> s != null).toList();
        Envelope envelope = envelopes.build(profile.name(),
                Map.of("focus", focusName, "airports", String.join(",", found.stream().map(Airport::iata).toList()),
                        "longHaulMiles", String.valueOf(properties.defaultLongHaulMiles())),
                lowestConfidence(scores),
                List.of("congestion".equals(focusName)
                                ? "The congestion component is the mean of the percentiles of the delay rate, the taxi-out "
                                + "time, the cancellation rate and the operations per runway. Missing KPIs are removed."
                                : describe(profile),
                        "The operations per runway KPI assumes that arrivals equal departures and that all runways are independent.",
                        "Code decides which airport is higher (focusOrder and kpiComparisons). A higher percentile means more "
                                + "pressure for every KPI, for example more delay, a longer taxi-out time or more operations per runway."),
                coverageNotes(scores, false),
                List.of(EnvelopeFactory.NO_PROFIT));
        if (!unknown.isEmpty()) {
            return new QueryResult.Partial<>(view,
                    unknown.stream().map(c -> c + " is not in the data, or it matches more than one airport").toList(), envelope);
        }
        return new QueryResult.Ok<>(view, envelope);
    }

    // ------------------------------------------------------------------ long-haul share

    /**
     * The long-haul share of the departures of an airport.
     * Both values (passenger flights only, and with cargo flights) are in the result.
     *
     * @param minMiles the distance limit in statute miles. Empty means the default of the settings.
     */
    public QueryResult<LongHaulView> longHaul(String code, Integer minMiles) {
        return withAirport(code, airport -> longHaul(airport, minMiles));
    }

    /** The long-haul share of an airport that is already resolved. */
    private QueryResult<LongHaulView> longHaul(Airport airport, Integer minMiles) {
        int limit = minMiles == null ? properties.defaultLongHaulMiles() : minMiles;
        if (limit < 0) {
            return new QueryResult.NotAvailable<>("The distance limit must be zero or more miles.", List.of());
        }
        if (data.bucketsOf(airport.iata()).isEmpty()) {
            return new QueryResult.NotAvailable<>("The T-100 data has no departures for " + airport.iata() + ".",
                    List.of());
        }
        LongHaulCalculator.Result passenger = metrics.longHaulResult(data, airport.iata(), limit, false);
        LongHaulCalculator.Result withCargo = metrics.longHaulResult(data, airport.iata(), limit, true);
        double cargoDepartures = withCargo.totalDepartures() - passenger.totalDepartures();
        LongHaulView view = new LongHaulView(AirportRef.of(airport), data.info().dataYear(), T100_TABLE,
                limit, passenger.limitUsed(), passenger.limitWasRounded(), boxed(passenger.share()),
                boxed(withCargo.share()), passenger.totalDepartures(), cargoDepartures,
                withCargo.totalDepartures() > 0 ? cargoDepartures / withCargo.totalDepartures() : 0);

        List<String> uncertainties = new ArrayList<>();
        if (passenger.limitWasRounded()) {
            uncertainties.add("The distance buckets are 500 miles wide. The limit " + limit + " was rounded down to "
                    + passenger.limitUsed() + " miles.");
        }
        if (view.cargoShareOfDepartures() > 0.2) {
            uncertainties.add("Cargo flights are " + Math.round(view.cargoShareOfDepartures() * 100)
                    + "% of the departures. The share with cargo flights is very different from the share of passenger flights.");
        }
        uncertainties.add("Flights of foreign airlines are not in the T-100 data.");
        Envelope envelope = envelopes.build(null,
                Map.of("airport", airport.iata(), "minMiles", String.valueOf(passenger.limitUsed()),
                        "table", "T-100 segment, US carriers, domestic and international"),
                Confidence.HIGH,
                List.of("A long-haul flight is a departure on a route of at least " + passenger.limitUsed()
                                + " statute miles. There is no standard limit. The default is "
                                + properties.defaultLongHaulMiles() + " miles.",
                        "The share counts departures performed, weighted by distance bucket. A passenger flight is a T-100 row with seats."),
                uncertainties, List.of());
        return new QueryResult.Ok<>(view, envelope);
    }

    // ------------------------------------------------------------------ traffic volume

    /** The number of flights and passengers of an airport, as the data give them. */
    public QueryResult<TrafficVolumeView> trafficVolume(String code) {
        return withAirport(code, airport -> trafficVolume(airport));
    }

    /** The traffic volume of an airport that is already resolved. */
    private QueryResult<TrafficVolumeView> trafficVolume(Airport airport) {
        Rows.TrafficRow traffic = data.traffic().get(airport.iata());
        Rows.OntimeRow ontime = data.ontime().get(airport.iata());
        Rows.EnplanementRow enplanements = data.enplanements().get(airport.iata());
        if (traffic == null && ontime == null && enplanements == null) {
            return new QueryResult.NotAvailable<>("The data have no traffic numbers for " + airport.iata() + ".", List.of());
        }
        TrafficVolumeView view = new TrafficVolumeView(AirportRef.of(airport), data.info().dataYear(),
                traffic == null ? null : traffic.departuresPax(), traffic == null ? null : traffic.departuresCargo(),
                traffic == null ? null : traffic.pax(),
                enplanements != null && enplanements.cy25().isPresent() ? enplanements.cy25().getAsLong() : null,
                ontime == null ? null : ontime.flights());
        Envelope envelope = envelopes.build(null, Map.of("airport", airport.iata()), Confidence.MEDIUM,
                List.of("Departures are flights that leave the airport. Arrivals are not counted. A simple estimate of all flights is "
                        + "about 2 times the departures, if arrivals equal departures."),
                List.of("Flights of foreign airlines are not in the T-100 data, so the real number of flights is higher.",
                        "The On-Time number counts only domestic flights of large reporting carriers, in both directions."),
                List.of());
        return new QueryResult.Ok<>(view, envelope);
    }

    // ------------------------------------------------------------------ unmet demand

    /** The unmet demand index. It is a proxy. No public data measures demand that airlines did not serve. */
    public QueryResult<UnmetDemandView> unmetDemand(String code) {
        return withAirport(code, airport -> unmetDemand(airport));
    }

    /** The unmet demand index of an airport that is already resolved. */
    private QueryResult<UnmetDemandView> unmetDemand(Airport airport) {
        WeightProfile profile = properties.unmetDemandProfile();
        AirportMetrics airportMetrics = snapshot.all().get(airport.iata());
        Optional<Score> score = calculator.score(airportMetrics, profile, snapshot.peers());
        if (score.isEmpty()) {
            return new QueryResult.NotAvailable<>("No data for " + airport.iata()
                    + " to calculate the unmet demand index.", List.of());
        }
        Score value = score.get();
        List<String> why = value.contributions().stream()
                .map(c -> String.format(Locale.ROOT, "%s: component value %.1f (percentile), weight %.2f, %.1f points",
                        c.component(), c.componentValue(), c.weight(), c.points()))
                .toList();
        Optional<ExpertNotes.Note> note = expertNotes.noteOf(airport.iata());
        UnmetDemandView view = new UnmetDemandView(AirportRef.of(airport), value.value(), "PROXY",
                "Index = " + describeWeights(profile) + ". It is a proxy. No public data measures demand that "
                        + "airlines did not serve. The index is not a number of flights or passengers.",
                value, why, note.map(ExpertNotes.Note::text).orElse(null), note.map(ExpertNotes.Note::source).orElse(null));

        Envelope envelope = envelopes.build(profile.name(),
                Map.of("airport", airport.iata(), "label", "PROXY"),
                value.confidence(),
                List.of("The unmet demand index is a proxy: " + describeWeights(profile) + ".",
                        "A high load factor, fast growth and high congestion together suggest that demand is higher than the capacity."),
                coverageNotes(List.of(value), false),
                List.of("A count of unserved flights or passengers. No public data has it.", EnvelopeFactory.NO_PROFIT));
        return new QueryResult.Ok<>(view, envelope);
    }

    // ------------------------------------------------------------------ live status

    /** The current FAA status. It is not a score input. */
    public QueryResult<LiveStatusView> liveStatus(String code) {
        return withAirport(code, airport -> liveStatus(airport));
    }

    /** The live status of an airport that is already resolved. */
    private QueryResult<LiveStatusView> liveStatus(Airport airport) {
        try {
            LiveStatus status = liveClient.statusOf(airport.iata());
            Envelope envelope = envelopes.build(null, Map.of("airport", airport.iata(), "source", "FAA NAS status"),
                    Confidence.HIGH,
                    List.of("The data comes from the FAA National Airspace System status service at the update time "
                            + status.updateTime() + ". The service lists active events only. An airport that is not in the "
                            + "list has no active event.", "The live status does not change any score."),
                    status.restrictionNotices() > 0
                            ? List.of(status.restrictionNotices() + " closure notice(s) only restrict some flights (for "
                            + "example general aviation). They are not listed.")
                            : List.of(),
                    List.of());
            return new QueryResult.Ok<>(new LiveStatusView(AirportRef.of(airport), status), envelope);
        } catch (IOException e) {
            return new QueryResult.NotAvailable<>("The live FAA status is not available now (" + e.getMessage() + ").",
                    List.of());
        }
    }

    // ------------------------------------------------------------------ methodology

    /** The weights, limits and scope. The agent uses it for questions about the method. */
    public QueryResult<MethodologyView> methodology() {
        Map<String, Map<String, Double>> profiles = new LinkedHashMap<>();
        properties.profiles().keySet().stream().sorted().forEach(name -> profiles.put(name, weightsOf(properties.profile(name))));
        MethodologyView view = new MethodologyView(profiles, weightsOf(properties.unmetDemandProfile()),
                data.info().dataYear(), rankedSet.size(), rankedSet.minEnplanements(), properties.defaultLongHaulMiles(),
                properties.regions().keySet().stream().sorted().toList(), AirportResolver.SCOPE_RULE,
                List.of("Each KPI becomes a percentile among the ranked airports. A component is the mean of its KPI percentiles.",
                        "The score is the weighted mean of the available components. The weights of missing components "
                                + "are shared among the others.",
                        "Congestion has four KPIs: delay rate, taxi-out time, cancellation rate and operations per runway.",
                        "The unmet demand index is a proxy. It is not a count of flights."));
        Envelope envelope = envelopes.build(null, Map.of(), Confidence.HIGH, List.of(), List.of(), List.of(EnvelopeFactory.NO_PROFIT));
        return new QueryResult.Ok<>(view, envelope);
    }

    /** The weights of a profile by component name, from high to low. */
    private static Map<String, Double> weightsOf(WeightProfile profile) {
        Map<String, Double> weights = new LinkedHashMap<>();
        profile.weights().entrySet().stream()
                .sorted(Map.Entry.<Component, Double>comparingByValue().reversed().thenComparing(e -> e.getKey().name()))
                .forEach(e -> weights.put(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue()));
        return weights;
    }

    // ------------------------------------------------------------------ helpers

    /** The KPIs of all airports and the percentiles of the ranked set. The data does not change at run time. */
    private record Snapshot(Map<String, AirportMetrics> all, List<AirportMetrics> ranked, Percentiles peers) {}

    private static Snapshot buildSnapshot(AirportDataset data, MetricsBuilder metrics, ScoringProperties properties,
                                          RankedSet rankedSet) {
        Map<String, AirportMetrics> all = metrics.buildAll(data, properties.defaultLongHaulMiles());
        List<AirportMetrics> ranked = all.values().stream().filter(m -> rankedSet.contains(m.iata())).toList();
        return new Snapshot(all, ranked, Percentiles.of(ranked));
    }

    /**
     * Runs the action for the airport that a code or a name gives. The tools accept both.
     * No match is OUT_OF_SCOPE. More than one match is NOT_AVAILABLE with the possible airports,
     * so the caller can ask the user.
     */
    private <T> QueryResult<T> withAirport(String text, Function<Airport, QueryResult<T>> action) {
        QueryResult<Resolution> found = resolver.resolve(text);
        if (!(found instanceof QueryResult.Ok<Resolution> ok)) {
            return notInData(text);
        }
        Resolution resolution = ok.data();
        if (!resolution.resolved()) {
            return new QueryResult.NotAvailable<>("\"" + text + "\" matches more than one airport. Ask the user which one.",
                    resolution.alternatives().stream()
                            .map(c -> c.airport().iata() + " (" + c.airport().name() + ")").toList());
        }
        return action.apply(data.airports().get(resolution.best().airport().iata()));
    }

    /** The one airport that a code or a name gives, or empty. */
    private Optional<Airport> uniqueAirport(String text) {
        return resolver.resolve(text) instanceof QueryResult.Ok<Resolution> ok && ok.data().resolved()
                ? Optional.of(data.airports().get(ok.data().best().airport().iata()))
                : Optional.empty();
    }

    /** The KPIs, the components and the score of an airport, against the ranked airports. */
    private AirportProfileView profileView(Airport airport, WeightProfile profile) {
        AirportMetrics airportMetrics = snapshot.all().get(airport.iata());
        Map<Component, Double> components = new EnumMap<>(Component.class);
        components.putAll(calculator.componentValues(airportMetrics, snapshot.peers()));
        Score score = calculator.score(airportMetrics, profile, snapshot.peers()).orElse(null);

        List<KpiView> kpis = new ArrayList<>();
        for (Kpi kpi : Kpi.values()) {
            MetricValue value = airportMetrics.kpi(kpi);
            kpis.add(switch (value) {
                case MetricValue.Direct d -> new KpiView(kpi, d.value(), percentile(kpi, d.value()),
                        "DIRECT", d.source(), null);
                case MetricValue.Proxy p -> new KpiView(kpi, p.value(), percentile(kpi, p.value()),
                        "PROXY", p.source(), "Proxy for: " + p.proxyFor());
                case MetricValue.Missing m -> new KpiView(kpi, null, null, "MISSING", null, m.reason());
            });
        }
        return new AirportProfileView(AirportRef.of(airport), rankedSet.contains(airport.iata()), profile.name(),
                score, components, kpis);
    }

    /** The percentile of a KPI value among the ranked airports, or null if no airport has the KPI. */
    private Double percentile(Kpi kpi, double value) {
        OptionalDouble percentile = snapshot.peers().rank(kpi, value);
        return percentile.isPresent() ? percentile.getAsDouble() : null;
    }

    /** The assumptions of a ranking result. */
    private static List<String> rankingAssumptions(WeightProfile profile, WeightProfile base, boolean custom) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add(describe(profile));
        if (custom) {
            assumptions.add("The weights are custom weights from the user. The components that the user did not name "
                    + "share the rest in the ratio of the profile " + base.name() + ".");
        }
        assumptions.add("The rank among the airports of the region is in the field rank. The rank among all ranked "
                + "airports is in the field nationalRank. The percentiles are always national.");
        assumptions.add("The score is an opportunity index. It shows traffic pressure and growth. It is a proxy "
                + "for the need of more capacity.");
        return assumptions;
    }

    /** The profile with this name (the default profile if the name is empty), or empty if there is none. */
    private Optional<WeightProfile> profileFor(String name) {
        String key = name == null || name.isBlank() ? DEFAULT_PROFILE : name.trim().toLowerCase(Locale.ROOT);
        return properties.profiles().containsKey(key) ? Optional.of(properties.profile(key)) : Optional.empty();
    }

    /** The answer for an unknown profile name. It lists the known names. */
    private <T> QueryResult<T> unknownProfile(String name) {
        return new QueryResult.NotAvailable<>("Unknown weight profile: " + name + ".",
                properties.profiles().keySet().stream().sorted().toList());
    }

    /** The answer for an airport that is not in the data. */
    private <T> QueryResult<T> notInData(String text) {
        return new QueryResult.OutOfScope<>("No airport for \"" + text + "\" is in the data.",
                AirportResolver.SCOPE_RULE);
    }

    /** The number of airports to return, from 1 to the maximum. */
    private static int clampTopN(Integer topN) {
        return topN == null ? DEFAULT_TOP_N : Math.max(1, Math.min(MAX_TOP_N, topN));
    }

    /** The lowest confidence of a list of scores. An empty list gives LOW. */
    private static Confidence lowestConfidence(List<Score> scores) {
        return scores.stream().map(Score::confidence).max(Comparator.comparingInt(Enum::ordinal)).orElse(Confidence.LOW);
    }

    /** Notes about airports with lower coverage, and about proxies. */
    private static List<String> coverageNotes(List<Score> scores, boolean listProxies) {
        List<String> notes = new ArrayList<>();
        for (Score score : scores) {
            if (!score.missingKpis().isEmpty()) {
                notes.add(score.iata() + ": no data for " + score.missingKpis().stream()
                        .map(k -> k.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "))
                        + ". Coverage " + String.format(Locale.ROOT, "%.2f", score.coverage())
                        + " (" + score.confidence() + " confidence). Missing KPIs are removed from their component, "
                        + "and missing components are removed from the score.");
            }
        }
        boolean proxy = scores.stream().flatMap(s -> s.contributions().stream()).anyMatch(Contribution::usesProxy);
        if (proxy || listProxies) {
            notes.add("Load factor, passengers per departure and operations per runway are proxies for terminal "
                    + "and runway pressure. They are not direct measures.");
        }
        return notes;
    }

    /** A short text of the weights of a profile. */
    private static String describe(WeightProfile profile) {
        return "Profile " + profile.name() + ": " + describeWeights(profile) + ". These weights are heuristics.";
    }

    /** The weights as text, for example 0.25 x congestion + 0.20 x growth. */
    private static String describeWeights(WeightProfile profile) {
        return weightsOf(profile).entrySet().stream()
                .map(e -> String.format(Locale.ROOT, "%.2f x %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining(" + "));
    }

    /** An optional number as a value that can be null, for JSON. */
    private static Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}

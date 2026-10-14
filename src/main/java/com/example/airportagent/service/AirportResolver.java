package com.example.airportagent.service;

import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.service.Views.AirportRef;
import com.example.airportagent.service.Views.Candidate;
import com.example.airportagent.service.Views.Resolution;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Converts a name or a code from the user to an airport of the data set.
 * The code does the matching. The LLM must not guess codes (DESIGN.md, section 8.3).
 *
 * <p>The tiers, in order: airport code, alias, city, part of the name. The first tier with a match decides.
 * If a tier has more than one airport, the result is not resolved. The caller must ask the user.
 */
@Component
public class AirportResolver {

    private static final int MAX_ALTERNATIVES = 8;
    public static final String SCOPE_RULE = "The app knows the US commercial service airports of the FAA list "
            + "(the 50 states, DC and the US territories). Foreign airports are not in the data.";

    private final AirportDataset data;
    private final EnvelopeFactory envelopes;

    public AirportResolver(AirportDataset data, EnvelopeFactory envelopes) {
        this.data = data;
        this.envelopes = envelopes;
    }

    public QueryResult<Resolution> resolve(String text) {
        if (text == null || text.isBlank()) {
            return new QueryResult.OutOfScope<>("The airport name is empty.", SCOPE_RULE);
        }
        String query = text.trim();
        String upper = query.toUpperCase(Locale.ROOT);
        String lower = query.toLowerCase(Locale.ROOT);

        List<Candidate> matches = tier("airport code", a -> a.iata().equals(upper) || a.faaId().equals(upper));
        if (matches.isEmpty() && upper.length() == 4 && upper.startsWith("K")) {
            matches = tier("airport code", a -> a.iata().equals(upper.substring(1)));
        }
        if (matches.isEmpty()) {
            String aliasTarget = data.aliases().get(lower);
            if (aliasTarget != null) {
                matches = data.airport(aliasTarget).map(a -> List.of(new Candidate(AirportRef.of(a), "alias"))).orElse(List.of());
            }
        }
        if (matches.isEmpty()) {
            matches = tier("city", a -> a.city().equalsIgnoreCase(query));
        }
        if (matches.isEmpty()) {
            matches = tier("name", a -> a.name().toLowerCase(Locale.ROOT).contains(lower)
                    || a.city().toLowerCase(Locale.ROOT).contains(lower));
        }

        if (matches.isEmpty()) {
            return new QueryResult.OutOfScope<>("No airport in the data matches \"" + query + "\".", SCOPE_RULE);
        }
        List<Candidate> sorted = matches.stream()
                .sorted(Comparator.comparingLong((Candidate c) -> size(c.airport().iata())).reversed()
                        .thenComparing(c -> c.airport().iata()))
                .limit(MAX_ALTERNATIVES).toList();
        boolean resolved = matches.size() == 1;
        return new QueryResult.Ok<>(new Resolution(query, resolved, resolved ? sorted.get(0) : null,
                resolved ? List.of() : sorted), envelopes.minimal());
    }

    /** The airport for a code that an earlier lookup returned. */
    public Optional<Airport> byCode(String code) {
        return data.airport(code);
    }

    private List<Candidate> tier(String matchedBy, Predicate<Airport> filter) {
        return data.airports().values().stream().filter(filter)
                .map(a -> new Candidate(AirportRef.of(a), matchedBy)).toList();
    }

    private long size(String code) {
        var enplanement = data.enplanements().get(code);
        return enplanement == null ? 0 : enplanement.cy25().orElse(0);
    }
}

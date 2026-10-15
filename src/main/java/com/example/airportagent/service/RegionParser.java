package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.domain.Airport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads a region text from the user: a region name from the settings (for example NEW_ENGLAND), a state code
 * (for example TX), or a comma list of both. The state of an airport is a US state code or a territory code.
 */
final class RegionParser {

    /** The states of a region filter. An empty set means the whole country. */
    record Filter(String label, Set<String> states) {

        /** True if the airport is in the region. */
        boolean contains(Airport airport) {
            return states.isEmpty() || states.contains(airport.state());
        }
    }

    private static final Filter ALL = new Filter("ALL", Set.of());
    private static final Set<String> ALL_WORDS = Set.of("ALL", "US", "USA", "NATIONAL");

    private final ScoringProperties properties;
    private final Set<String> knownStates;

    /** Creates the parser with the regions of the settings and the states of the data. */
    RegionParser(ScoringProperties properties, AirportDataset data) {
        this.properties = properties;
        this.knownStates = data.airports().values().stream().map(Airport::state).collect(Collectors.toSet());
    }

    /**
     * Converts the text of the user to a filter.
     *
     * @throws IllegalArgumentException if a part of the text is not a region name or a state code
     */
    Filter parse(String text) {
        if (text == null || text.isBlank()) {
            return ALL;
        }
        Set<String> states = new TreeSet<>();
        List<String> labels = new ArrayList<>();
        for (String token : text.split(",")) {
            String key = token.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            if (key.isEmpty()) {
                continue;
            }
            if (ALL_WORDS.contains(key)) {
                return ALL;
            }
            if (properties.regions().containsKey(key)) {
                states.addAll(properties.regions().get(key));
            } else if (knownStates.contains(key)) {
                states.add(key);
            } else {
                throw new IllegalArgumentException("Unknown region: " + token.trim() + ".");
            }
            labels.add(key);
        }
        return states.isEmpty() ? ALL : new Filter(String.join(",", labels), Set.copyOf(states));
    }

    /** The region names that a user can use, for an answer to an unknown region. */
    List<String> knownRegions() {
        List<String> names = new ArrayList<>(properties.regions().keySet().stream().sorted().toList());
        names.add("ALL");
        names.add("or a state code, for example TX, or a comma list such as CA,OR,WA");
        return names;
    }
}

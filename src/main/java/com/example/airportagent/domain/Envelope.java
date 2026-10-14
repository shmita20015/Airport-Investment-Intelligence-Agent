package com.example.airportagent.domain;

import java.util.List;
import java.util.Map;

/**
 * The context of a result: what the analyst must know to read the numbers.
 * Each result of the service has an envelope (DESIGN.md, section 9).
 *
 * @param assumptions   the assumptions that apply to this result
 * @param uncertainties data gaps, proxies and the age of the data
 * @param outOfScope    questions that this result does not answer
 * @param sources       source names and data years
 * @param asOf          the date of the data files
 * @param profile       the weight profile used, or empty text if none
 * @param parameters    the parameters used (for example the long-haul limit)
 */
public record Envelope(List<String> assumptions, List<String> uncertainties, List<String> outOfScope,
                       Confidence confidence, List<String> sources, String asOf, String profile,
                       Map<String, String> parameters) {

    public Envelope {
        assumptions = List.copyOf(assumptions);
        uncertainties = List.copyOf(uncertainties);
        outOfScope = List.copyOf(outOfScope);
        sources = List.copyOf(sources);
        parameters = Map.copyOf(parameters);
    }
}

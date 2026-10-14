package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.Rows.EnplanementRow;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The airports that the app ranks: at least the minimum enplanements in the latest year (docs/DESIGN-full.md, section 6.6).
 * The percentiles use only these airports as peers.
 */
@Component
public class RankedSet {

    private final Set<String> codes;
    private final long minEnplanements;

    public RankedSet(AirportDataset data, ScoringProperties properties) {
        this.minEnplanements = properties.minEnplanements();
        this.codes = data.airports().keySet().stream()
                .filter(code -> {
                    EnplanementRow row = data.enplanements().get(code);
                    return row != null && row.cy25().orElse(0) >= minEnplanements;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean contains(String code) {
        return codes.contains(code);
    }

    public int size() {
        return codes.size();
    }

    public long minEnplanements() {
        return minEnplanements;
    }
}

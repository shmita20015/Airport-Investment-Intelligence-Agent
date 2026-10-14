package com.example.airportagent.data;

import com.example.airportagent.data.Rows.BucketRow;
import com.example.airportagent.data.Rows.DatasetInfo;
import com.example.airportagent.data.Rows.EnplanementRow;
import com.example.airportagent.data.Rows.OntimeRow;
import com.example.airportagent.data.Rows.TrafficRow;
import com.example.airportagent.domain.Airport;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All saved facts, in memory. The maps use the airport code (the IATA code) as the key.
 * An airport can be in the airports map and not in another map. That is a data gap.
 *
 * @param aliases lower-case alias text to airport code (for example "la" to "LAX")
 */
public record AirportDataset(Map<String, Airport> airports,
                             Map<String, TrafficRow> traffic,
                             Map<String, List<BucketRow>> buckets,
                             Map<String, OntimeRow> ontime,
                             Map<String, EnplanementRow> enplanements,
                             Map<String, String> aliases,
                             DatasetInfo info) {

    public Optional<Airport> airport(String code) {
        return Optional.ofNullable(code == null ? null : airports.get(code.trim().toUpperCase()));
    }

    public List<BucketRow> bucketsOf(String code) {
        return buckets.getOrDefault(code, List.of());
    }
}

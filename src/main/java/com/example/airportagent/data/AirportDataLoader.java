package com.example.airportagent.data;

import com.example.airportagent.data.Rows.BucketRow;
import com.example.airportagent.data.Rows.DatasetInfo;
import com.example.airportagent.data.Rows.EnplanementRow;
import com.example.airportagent.data.Rows.OntimeRow;
import com.example.airportagent.data.Rows.TrafficRow;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.HubClass;
import com.example.airportagent.domain.Region;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Reads the CSV files that scripts/prepare_data.py wrote. It saves facts only. It calculates no score. */
public final class AirportDataLoader {

    private static final String BASE = "data/";

    public AirportDataset load() {
        Map<String, Airport> airports = new LinkedHashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "airports.csv")) {
            String code = row.get("iata");
            airports.put(code, new Airport(code, row.get("faa_id"), row.get("name"), row.get("city"),
                    row.get("state"), Region.fromText(row.get("region")), number(row.get("lat")).orElse(Double.NaN),
                    number(row.get("lon")).orElse(Double.NaN),
                    (int) number(row.get("runway_count")).orElse(0), HubClass.fromFaaCode(row.get("hub_class"))));
        }

        Map<String, TrafficRow> traffic = new HashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "traffic.csv")) {
            traffic.put(row.get("iata"), new TrafficRow(row.get("iata"), (int) number(row.get("year")).orElse(0),
                    number(row.get("pax")).orElse(0), number(row.get("seats")).orElse(0),
                    number(row.get("departures_pax")).orElse(0), number(row.get("departures_cargo")).orElse(0)));
        }

        Map<String, List<BucketRow>> buckets = new HashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "distance_buckets.csv")) {
            buckets.computeIfAbsent(row.get("iata"), key -> new ArrayList<>()).add(new BucketRow(row.get("iata"),
                    (int) number(row.get("bucket_min_miles")).orElse(0),
                    number(row.get("departures_pax")).orElse(0), number(row.get("departures_cargo")).orElse(0)));
        }

        Map<String, OntimeRow> ontime = new HashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "ontime.csv")) {
            ontime.put(row.get("iata"), new OntimeRow(row.get("iata"), (int) number(row.get("year")).orElse(0),
                    (long) number(row.get("flights")).orElse(0), number(row.get("delay15_rate")),
                    number(row.get("cancel_rate")), number(row.get("avg_taxi_out_min"))));
        }

        Map<String, EnplanementRow> enplanements = new HashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "enplanements.csv")) {
            enplanements.put(row.get("iata"), new EnplanementRow(row.get("iata"), whole(row.get("enpl_cy23")),
                    whole(row.get("enpl_cy24")), whole(row.get("enpl_cy25")), rank(row.get("faa_rank_cy25"))));
        }

        Map<String, String> aliases = new HashMap<>();
        for (Map<String, String> row : Csv.readResource(BASE + "aliases.csv")) {
            aliases.put(row.get("alias").trim().toLowerCase(), row.get("iata").trim().toUpperCase());
        }

        return new AirportDataset(Map.copyOf(airports), Map.copyOf(traffic), Map.copyOf(buckets),
                Map.copyOf(ontime), Map.copyOf(enplanements), Map.copyOf(aliases), readInfo());
    }

    private static DatasetInfo readInfo() {
        try (InputStream stream = AirportDataLoader.class.getClassLoader().getResourceAsStream(BASE + "manifest.json")) {
            if (stream == null) {
                throw new IllegalStateException("manifest.json not found. Run scripts/prepare_data.py.");
            }
            JsonNode root = JsonMapper.builder().build().readTree(stream);
            return new DatasetInfo(root.path("dataYear").asInt(), root.path("generated").asString(""));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read manifest.json", e);
        }
    }

    private static OptionalDouble number(String text) {
        if (text == null || text.isBlank()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(text.trim()));
    }

    private static OptionalLong whole(String text) {
        OptionalDouble value = number(text);
        return value.isPresent() ? OptionalLong.of((long) value.getAsDouble()) : OptionalLong.empty();
    }

    private static OptionalInt rank(String text) {
        OptionalDouble value = number(text);
        return value.isPresent() ? OptionalInt.of((int) value.getAsDouble()) : OptionalInt.empty();
    }
}

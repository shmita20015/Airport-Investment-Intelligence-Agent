package com.example.airportagent.data;

import com.example.airportagent.data.Rows.BucketRow;
import com.example.airportagent.data.Rows.DatasetInfo;
import com.example.airportagent.data.Rows.EnplanementRow;
import com.example.airportagent.data.Rows.OntimeRow;
import com.example.airportagent.data.Rows.TrafficRow;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.HubClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Reads the CSV files that scripts/prepare_data.py wrote. It saves facts only. It calculates no score. */
public final class AirportDataLoader {

    private static final String BASE = "data/";

    private final Path folder;

    /** Creates a loader that reads the files from the class path. */
    public AirportDataLoader() {
        this(null);
    }

    /** Creates a loader that reads the files from a folder. If the folder is null, it reads the class path. */
    public AirportDataLoader(Path folder) {
        this.folder = folder;
    }

    /** The rows of a data file, from the folder or from the class path. */
    private List<Map<String, String>> rows(String name) {
        return folder == null ? Csv.readResource(BASE + name) : Csv.readFile(folder.resolve(name));
    }

    /** Reads all CSV files and the manifest into one AirportDataset. */
    public AirportDataset load() {
        Map<String, Airport> airports = new LinkedHashMap<>();
        for (Map<String, String> row : rows("airports.csv")) {
            String code = row.get("iata");
            airports.put(code, new Airport(code, row.get("faa_id"), row.get("name"), row.get("city"),
                    row.get("state"), integer(row.get("runway_count")), HubClass.fromFaaCode(row.get("hub_class"))));
        }

        Map<String, TrafficRow> traffic = new HashMap<>();
        for (Map<String, String> row : rows("traffic.csv")) {
            traffic.put(row.get("iata"), new TrafficRow(row.get("iata"),
                    number(row.get("pax")).orElse(0), number(row.get("seats")).orElse(0),
                    number(row.get("departures_pax")).orElse(0), number(row.get("departures_cargo")).orElse(0)));
        }

        Map<String, List<BucketRow>> buckets = new HashMap<>();
        for (Map<String, String> row : rows("distance_buckets.csv")) {
            buckets.computeIfAbsent(row.get("iata"), key -> new ArrayList<>()).add(new BucketRow(row.get("iata"),
                    integer(row.get("bucket_min_miles")),
                    number(row.get("departures_pax")).orElse(0), number(row.get("departures_cargo")).orElse(0)));
        }

        Map<String, OntimeRow> ontime = new HashMap<>();
        for (Map<String, String> row : rows("ontime.csv")) {
            ontime.put(row.get("iata"), new OntimeRow(row.get("iata"),
                    (long) number(row.get("flights")).orElse(0), number(row.get("delay15_rate")),
                    number(row.get("cancel_rate")), number(row.get("avg_taxi_out_min"))));
        }

        Map<String, EnplanementRow> enplanements = new HashMap<>();
        for (Map<String, String> row : rows("enplanements.csv")) {
            enplanements.put(row.get("iata"), new EnplanementRow(row.get("iata"), whole(row.get("enpl_cy23")),
                    whole(row.get("enpl_cy25"))));
        }

        Map<String, String> aliases = new HashMap<>();
        for (Map<String, String> row : rows("aliases.csv")) {
            aliases.put(row.get("alias").trim().toLowerCase(Locale.ROOT), row.get("iata").trim().toUpperCase(Locale.ROOT));
        }

        return new AirportDataset(Map.copyOf(airports), Map.copyOf(traffic), Map.copyOf(buckets),
                Map.copyOf(ontime), Map.copyOf(enplanements), Map.copyOf(aliases), readInfo());
    }

    /** Reads the data year and the build time from manifest.json. */
    private DatasetInfo readInfo() {
        try (InputStream stream = folder == null
                ? AirportDataLoader.class.getClassLoader().getResourceAsStream(BASE + "manifest.json")
                : Files.newInputStream(folder.resolve("manifest.json"))) {
            if (stream == null) {
                throw new IllegalStateException("manifest.json not found. Run scripts/prepare_data.py.");
            }
            JsonNode root = JsonMapper.builder().build().readTree(stream);
            return new DatasetInfo(root.path("dataYear").asInt(), root.path("generated").asString(""));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read manifest.json", e);
        }
    }

    /** A number from a text. An empty text gives an empty value, never zero. */
    private static OptionalDouble number(String text) {
        if (text == null || text.isBlank()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(text.trim()));
    }

    /** A whole number from a text. An empty text gives an empty value. */
    private static OptionalLong whole(String text) {
        OptionalDouble value = number(text);
        return value.isPresent() ? OptionalLong.of((long) value.getAsDouble()) : OptionalLong.empty();
    }

    /** A whole number. An empty text gives 0. */
    private static int integer(String text) {
        return (int) number(text).orElse(0);
    }
}

package com.example.airportagent.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a CSV file with a header line. Each row is a map from the column name to the text (Apache Commons CSV). */
final class Csv {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    private Csv() {}

    /** Read a CSV file from the class path. */
    static List<Map<String, String>> readResource(String path) {
        InputStream stream = Csv.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Data file not found on the class path: " + path
                    + ". Run scripts/prepare_data.py.");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    static List<Map<String, String>> read(Reader reader) throws IOException {
        try (CSVParser parser = FORMAT.parse(reader)) {
            List<String> header = parser.getHeaderNames();
            return parser.stream().map(record -> toRow(header, record)).toList();
        }
    }

    /** A column that the line does not have becomes an empty text. */
    private static Map<String, String> toRow(List<String> header, CSVRecord record) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String name : header) {
            row.put(name, record.isSet(name) ? record.get(name) : "");
        }
        return row;
    }
}

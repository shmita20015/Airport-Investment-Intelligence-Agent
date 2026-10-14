package com.example.airportagent.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small CSV reader. It supports quoted fields, commas in quotes and doubled quotes. A field has no line break. */
final class Csv {

    private Csv() {}

    /** Read a CSV file from the class path. Each row is a map from the column name to the text. */
    static List<Map<String, String>> readResource(String path) {
        InputStream stream = Csv.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Data file not found on the class path: " + path
                    + ". Run scripts/prepare_data.py.");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    static List<Map<String, String>> read(BufferedReader reader) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            return List.of();
        }
        List<String> header = splitLine(headerLine);
        List<Map<String, String>> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            List<String> fields = splitLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static List<String> splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}

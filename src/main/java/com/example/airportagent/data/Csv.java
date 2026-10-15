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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads a CSV file with a header line. Each row is a map from the column name to the text (Apache Commons CSV).
 * The files come from scripts/prepare_data.py, so each row is complete.
 */
final class Csv {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    /** Utility class. It has no instance. */
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

    /** Reads a CSV file from a folder of the file system. */
    static List<Map<String, String>> readFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Reads the rows of a CSV text. Each row is a map from the column name to the text. */
    static List<Map<String, String>> read(Reader reader) throws IOException {
        try (CSVParser parser = FORMAT.parse(reader)) {
            return parser.stream().map(CSVRecord::toMap).toList();
        }
    }
}

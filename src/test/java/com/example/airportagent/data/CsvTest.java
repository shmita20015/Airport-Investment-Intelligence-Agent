package com.example.airportagent.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The parser is a library. These tests check the contract that the loader needs. */
class CsvTest {

    private static List<Map<String, String>> read(String text) throws IOException {
        return Csv.read(new StringReader(text));
    }

    @Test
    void readsRowsAsMapsAndSkipsBlankLines() throws IOException {
        List<Map<String, String>> rows = read("iata,name\nSFO,\"San Francisco, Intl\"\n\nLAX,Los Angeles\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "San Francisco, Intl");
        assertThat(rows.get(1)).containsEntry("iata", "LAX");
    }

    @Test
    void keepsDoubledQuotesAndEmptyFields() throws IOException {
        List<Map<String, String>> rows = read("a,b,c\n\"He said \"\"hi\"\"\",,x\n");

        assertThat(rows.get(0)).containsEntry("a", "He said \"hi\"").containsEntry("b", "").containsEntry("c", "x");
    }
}

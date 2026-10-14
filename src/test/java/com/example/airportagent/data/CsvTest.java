package com.example.airportagent.data;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    @Test
    void splitsSimpleFields() {
        assertThat(Csv.splitLine("a,b,c")).containsExactly("a", "b", "c");
    }

    @Test
    void keepsCommasAndDoubledQuotesInsideQuotes() {
        assertThat(Csv.splitLine("SFO,\"San Francisco, International\",\"He said \"\"hi\"\"\""))
                .containsExactly("SFO", "San Francisco, International", "He said \"hi\"");
    }

    @Test
    void keepsEmptyFields() {
        assertThat(Csv.splitLine("a,,c,")).containsExactly("a", "", "c", "");
    }

    @Test
    void readsRowsAsMapsAndSkipsBlankLines() throws IOException {
        String text = "iata,name\nSFO,\"San Francisco, Intl\"\n\nLAX,Los Angeles\n";

        List<Map<String, String>> rows = Csv.read(new BufferedReader(new StringReader(text)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "San Francisco, Intl");
        assertThat(rows.get(1)).containsEntry("iata", "LAX");
    }

    @Test
    void missingTrailingFieldsBecomeEmptyText() throws IOException {
        List<Map<String, String>> rows = Csv.read(new BufferedReader(new StringReader("a,b,c\n1,2\n")));

        assertThat(rows.get(0)).containsEntry("c", "");
    }
}

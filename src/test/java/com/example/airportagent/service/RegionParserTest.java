package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionParserTest {

    private static AirportDataset data;
    private static RegionParser parser;

    @BeforeAll
    static void load() {
        data = new AirportDataLoader().load();
        parser = new RegionParser(new ScoringProperties(Map.of(), Map.of(), 100_000, 1_000, 2_500,
                Map.of("NEW_ENGLAND", List.of("CT", "MA"), "WEST_COAST", List.of("CA", "OR", "WA"))), data);
    }

    @Test
    void emptyTextAndTheWordsForTheWholeCountryGiveAllAirports() {
        for (String text : new String[] {null, "", "  ", "ALL", "us", "National", "CA, ALL"}) {
            RegionParser.Filter filter = parser.parse(text);
            assertThat(filter.label()).as(text).isEqualTo("ALL");
            assertThat(filter.contains(data.airport("BOS").orElseThrow())).as(text).isTrue();
            assertThat(filter.contains(data.airport("LAX").orElseThrow())).as(text).isTrue();
        }
    }

    @Test
    void aRegionNameIsReadWithAnyCaseSpacesOrHyphens() {
        for (String text : new String[] {"NEW_ENGLAND", "new england", "New-England"}) {
            RegionParser.Filter filter = parser.parse(text);
            assertThat(filter.label()).as(text).isEqualTo("NEW_ENGLAND");
            assertThat(filter.contains(data.airport("BOS").orElseThrow())).isTrue();
            assertThat(filter.contains(data.airport("LAX").orElseThrow())).isFalse();
        }
    }

    @Test
    void aStateCodeAndAListOfBothAreRead() {
        assertThat(parser.parse("TX").contains(data.airport("DFW").orElseThrow())).isTrue();
        RegionParser.Filter list = parser.parse("new england, TX");

        assertThat(list.label()).isEqualTo("NEW_ENGLAND,TX");
        assertThat(list.contains(data.airport("BOS").orElseThrow())).isTrue();
        assertThat(list.contains(data.airport("DFW").orElseThrow())).isTrue();
        assertThat(list.contains(data.airport("LAX").orElseThrow())).isFalse();
    }

    @Test
    void anUnknownPartGivesAClearMessage() {
        assertThatThrownBy(() -> parser.parse("TX, Atlantis")).hasMessageContaining("Unknown region: Atlantis");
    }

    @Test
    void theKnownRegionsListTheNamesAndTheOtherForms() {
        assertThat(parser.knownRegions()).contains("NEW_ENGLAND", "WEST_COAST", "ALL");
        assertThat(parser.knownRegions().get(0)).isEqualTo("NEW_ENGLAND");
    }
}

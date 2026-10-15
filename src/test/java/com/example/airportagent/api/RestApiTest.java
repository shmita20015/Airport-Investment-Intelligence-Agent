package com.example.airportagent.api;

import com.example.airportagent.service.AirportQueryService;
import com.example.airportagent.service.QueryResult;
import com.example.airportagent.service.Views.LongHaulView;
import com.example.airportagent.service.Views.RankedItem;
import com.example.airportagent.service.Views.RankingView;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the application on a free port and calls the REST API like a client.
 * The numbers must be the same as the numbers of the service. The chat tools call the same service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.google.genai.api-key=test-key-not-real")
class RestApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private AirportQueryService service;

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path) throws IOException, InterruptedException {
        return JSON.readTree(get(path).body());
    }

    /** Checks that the ranking of the api has the same numbers as the service. */
    @Test
    void theRankingOfTheApiHasTheSameNumbersAsTheService() throws Exception {
        JsonNode body = json("/api/rank?region=NEW_ENGLAND&profile=terminal&topN=8");
        RankingView expected = ((QueryResult.Ok<RankingView>) service.rank("NEW_ENGLAND", "terminal", 8)).data();

        assertThat(body.path("status").asString()).isEqualTo("OK");
        JsonNode items = body.path("data").path("items");
        assertThat(items.size()).isEqualTo(expected.items().size());
        for (int i = 0; i < expected.items().size(); i++) {
            RankedItem item = expected.items().get(i);
            assertThat(items.get(i).path("airport").path("iata").asString()).isEqualTo(item.airport().iata());
            assertThat(items.get(i).path("score").path("value").asDouble())
                    .isCloseTo(item.score().value(), Offset.offset(1e-9));
            assertThat(items.get(i).path("score").path("coverage").asDouble())
                    .isCloseTo(item.score().coverage(), Offset.offset(1e-9));
        }
        assertThat(body.path("envelope").path("assumptions").size()).isPositive();
    }

    /** Checks that the long haul share of the api has the same numbers as the service. */
    @Test
    void theLongHaulShareOfTheApiHasTheSameNumbersAsTheService() throws Exception {
        JsonNode body = json("/api/airport/ANC/long-haul?minMiles=2500");
        LongHaulView expected = ((QueryResult.Ok<LongHaulView>) service.longHaul("ANC", 2500)).data();

        assertThat(body.path("data").path("sharePassengerOnly").asDouble())
                .isCloseTo(expected.sharePassengerOnly(), Offset.offset(1e-12));
        assertThat(body.path("data").path("shareWithCargo").asDouble())
                .isCloseTo(expected.shareWithCargo(), Offset.offset(1e-12));
    }

    /** Checks that the comparison and the unmet demand are available. */
    @Test
    void theComparisonAndTheUnmetDemandAreAvailable() throws Exception {
        JsonNode compare = json("/api/compare?codes=LAX,SNA&focus=congestion");
        assertThat(compare.path("status").asString()).isEqualTo("OK");
        assertThat(compare.path("data").path("airports").size()).isEqualTo(2);
        assertThat(compare.path("data").path("warnings").size()).isPositive();

        JsonNode unmet = json("/api/airport/SFO/unmet-demand");
        assertThat(unmet.path("data").path("label").asString()).isEqualTo("PROXY");
        assertThat(unmet.path("data").path("expertNote").asString("")).contains("parallel runways");
    }

    /** Checks that a gap in the data gives404 with a reason. */
    @Test
    void aGapInTheDataGives404WithAReason() throws Exception {
        HttpResponse<String> unknownAirport = get("/api/airport/ZZZ");
        assertThat(unknownAirport.statusCode()).isEqualTo(404);
        JsonNode body = JSON.readTree(unknownAirport.body());
        assertThat(body.path("status").asString()).isEqualTo("OUT_OF_SCOPE");
        assertThat(body.path("scopeRule").asString()).contains("Foreign airports");

        HttpResponse<String> unknownProfile = get("/api/rank?profile=nonsense");
        assertThat(unknownProfile.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(unknownProfile.body()).path("whatIsAvailable").size()).isPositive();
    }

    /** Checks that the name lookup resolves an alias. */
    @Test
    void theNameLookupResolvesAnAlias() throws Exception {
        JsonNode body = json("/api/resolve?q=" + java.net.URLEncoder.encode("Santa Ana", "UTF-8"));

        assertThat(body.path("data").path("resolved").asBoolean()).isTrue();
        assertThat(body.path("data").path("best").path("airport").path("iata").asString()).isEqualTo("SNA");
    }

    /** Checks that custom weights and the methodology are available in the api. */
    @Test
    void customWeightsAndTheMethodologyAreAvailableInTheApi() throws Exception {
        JsonNode custom = json("/api/rank?profile=terminal&topN=3&weights=scale:0,congestion:0.5");
        assertThat(custom.path("status").asString()).isEqualTo("OK");
        assertThat(custom.path("data").path("profile").asString()).isEqualTo("custom");
        assertThat(custom.path("data").path("weights").path("congestion").asDouble()).isEqualTo(0.5);

        HttpResponse<String> bad = get("/api/rank?weights=speed:1");
        assertThat(bad.statusCode()).isEqualTo(404);

        JsonNode methodology = json("/api/methodology");
        assertThat(methodology.path("data").path("regions").size()).isGreaterThan(10);
        assertThat(methodology.path("data").path("weightProfiles").path("terminal").path("congestion").asDouble()).isEqualTo(0.25);
    }
}

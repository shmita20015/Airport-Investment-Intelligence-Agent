package com.example.airportagent.api;

import com.example.airportagent.service.AirportQueryService;
import com.example.airportagent.service.QueryResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The REST interface of the engine. It uses no LLM. The chat tools call the same service,
 * so both give the same numbers (docs/DESIGN-full.md, section 4.3).
 */
@RestController
@RequestMapping("/api")
public class ScoreController {

    private final AirportQueryService service;

    /** Creates the controller with the engine. */
    public ScoreController(AirportQueryService service) {
        this.service = service;
    }

    /** GET /api/resolve: finds the airport for a name or a code. */
    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam("q") String text) {
        return respond(service.resolveAirport(text));
    }

    /** GET /api/rank: ranks the airports of a region, with custom weights if the caller gives them. */
    @GetMapping("/rank")
    public ResponseEntity<?> rank(@RequestParam(required = false) String region,
                                  @RequestParam(required = false) String profile,
                                  @RequestParam(required = false) Integer topN,
                                  @RequestParam(required = false) String weights) {
        return respond(service.rank(region, profile, topN, weights));
    }

    /** GET /api/compare: compares airports and says which one is higher. */
    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam("codes") List<String> codes,
                                     @RequestParam(required = false) String focus,
                                     @RequestParam(required = false) String weights) {
        return respond(service.compare(codes, focus, weights));
    }

    /** GET /api/methodology: the profiles, the weights, the regions, the limits and the scope. */
    @GetMapping("/methodology")
    public ResponseEntity<?> methodology() {
        return respond(service.methodology());
    }

    /** GET /api/airport/{code}: the KPIs, the components and the score of one airport. */
    @GetMapping("/airport/{code}")
    public ResponseEntity<?> airport(@PathVariable String code, @RequestParam(required = false) String profile) {
        return respond(service.airportProfile(code, profile));
    }

    /** GET /api/airport/{code}/long-haul: the long-haul share of one airport. */
    @GetMapping("/airport/{code}/long-haul")
    public ResponseEntity<?> longHaul(@PathVariable String code, @RequestParam(required = false) Integer minMiles) {
        return respond(service.longHaul(code, minMiles));
    }

    /** GET /api/airport/{code}/traffic: the number of flights and passengers of one airport. */
    @GetMapping("/airport/{code}/traffic")
    public ResponseEntity<?> traffic(@PathVariable String code) {
        return respond(service.trafficVolume(code));
    }

    /** GET /api/airport/{code}/unmet-demand: the unmet demand index (a proxy) of one airport. */
    @GetMapping("/airport/{code}/unmet-demand")
    public ResponseEntity<?> unmetDemand(@PathVariable String code) {
        return respond(service.unmetDemand(code));
    }

    /** GET /api/airport/{code}/live-status: the current FAA status of one airport. */
    @GetMapping("/airport/{code}/live-status")
    public ResponseEntity<?> liveStatus(@PathVariable String code) {
        return respond(service.liveStatus(code));
    }

    /** Converts a result of the service to an HTTP answer. A gap in the data or the scope gives HTTP 404. */
    private static <T> ResponseEntity<ApiResponse<T>> respond(QueryResult<T> result) {
        ApiResponse<T> body = ApiResponse.of(result);
        return ResponseEntity.status(body.httpStatus()).body(body);
    }
}

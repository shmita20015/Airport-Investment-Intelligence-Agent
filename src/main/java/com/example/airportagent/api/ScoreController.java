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

    public ScoreController(AirportQueryService service) {
        this.service = service;
    }

    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam("q") String text) {
        return respond(service.resolveAirport(text));
    }

    @GetMapping("/rank")
    public ResponseEntity<?> rank(@RequestParam(required = false) String region,
                                  @RequestParam(required = false) String profile,
                                  @RequestParam(required = false) Integer topN,
                                  @RequestParam(required = false) String weights) {
        return respond(service.rank(region, profile, topN, weights));
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam("codes") List<String> codes,
                                     @RequestParam(required = false) String focus,
                                     @RequestParam(required = false) String weights) {
        return respond(service.compare(codes, focus, weights));
    }

    @GetMapping("/methodology")
    public ResponseEntity<?> methodology() {
        return respond(service.methodology());
    }

    @GetMapping("/airport/{code}")
    public ResponseEntity<?> airport(@PathVariable String code, @RequestParam(required = false) String profile) {
        return respond(service.airportProfile(code, profile));
    }

    @GetMapping("/airport/{code}/long-haul")
    public ResponseEntity<?> longHaul(@PathVariable String code, @RequestParam(required = false) Integer minMiles) {
        return respond(service.longHaul(code, minMiles));
    }

    @GetMapping("/airport/{code}/unmet-demand")
    public ResponseEntity<?> unmetDemand(@PathVariable String code) {
        return respond(service.unmetDemand(code));
    }

    @GetMapping("/airport/{code}/live-status")
    public ResponseEntity<?> liveStatus(@PathVariable String code) {
        return respond(service.liveStatus(code));
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(QueryResult<T> result) {
        ApiResponse<T> body = ApiResponse.of(result);
        return ResponseEntity.status(body.httpStatus()).body(body);
    }
}

package com.example.airportagent.agent;

import com.example.airportagent.api.ApiResponse;
import com.example.airportagent.service.AirportQueryService;
import com.example.airportagent.service.QueryResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Supplier;

/**
 * The tools of the LLM. Each tool calls one method of {@link AirportQueryService}.
 * The LLM chooses the tool and the inputs. Java code calculates every number (docs/DESIGN-full.md, section 8).
 */
@Component
public class AirportTools {

    private final AirportQueryService service;
    private final JsonMapper json;

    public AirportTools(AirportQueryService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @Tool(description = "Find the airport for a name, a city or a code. Returns the match, how it matched, and other "
            + "possible airports. Use it when the user gives a name and not a 3-letter code.")
    public ApiResponse<?> resolveAirport(
            @ToolParam(description = "Airport name, city or code, for example Santa Ana") String text,
            ToolContext context) {
        return run(context, "resolveAirport", () -> service.resolveAirport(text));
    }

    @Tool(description = "Rank US airports by an opportunity score. Use it for questions about the best candidates for "
            + "terminal expansion or investment. The percentiles are national. The region only filters the list.")
    public ApiResponse<?> rankAirports(
            @ToolParam(description = "NEW_ENGLAND, or ALL for the whole country", required = false) String region,
            @ToolParam(description = "Weight profile: terminal (terminal expansion) or general", required = false) String profile,
            @ToolParam(description = "Number of airports, 1 to 50. Default 10", required = false) Integer topN,
            ToolContext context) {
        return run(context, "rankAirports", () -> service.rank(region, profile, topN));
    }

    @Tool(description = "Compare two to six airports. Use focus congestion to compare congestion. Use a profile name "
            + "(terminal or general) to compare the score. Returns the KPIs and percentiles of each airport.")
    public ApiResponse<?> compareAirports(
            @ToolParam(description = "Airport codes, for example LAX and SNA") List<String> codes,
            @ToolParam(description = "congestion, terminal or general. Default congestion", required = false) String focus,
            ToolContext context) {
        return run(context, "compareAirports", () -> service.compare(codes, focus));
    }

    @Tool(description = "All KPIs, component values and the score of one airport, with the source and the data year of each KPI.")
    public ApiResponse<?> getAirportProfile(
            @ToolParam(description = "Airport code, for example SFO") String code,
            @ToolParam(description = "Weight profile: terminal or general. Default general", required = false) String profile,
            ToolContext context) {
        return run(context, "getAirportProfile", () -> service.airportProfile(code, profile));
    }

    @Tool(description = "The share of long-haul departures of one airport. Returns the share of passenger flights and "
            + "the share with cargo flights. There is no standard definition of long-haul. The default limit is 2,500 miles.")
    public ApiResponse<?> getLongHaulShare(
            @ToolParam(description = "Airport code, for example ANC") String code,
            @ToolParam(description = "Minimum route distance in statute miles. Default 2500", required = false) Integer minMiles,
            ToolContext context) {
        return run(context, "getLongHaulShare", () -> service.longHaul(code, minMiles));
    }

    @Tool(description = "The unmet demand index of one airport. It is a PROXY built from load factor, growth and congestion. "
            + "No public data counts unserved flights. Returns the parts of the index, so you can explain why.")
    public ApiResponse<?> getUnmetDemandIndex(
            @ToolParam(description = "Airport code, for example SFO") String code,
            ToolContext context) {
        return run(context, "getUnmetDemandIndex", () -> service.unmetDemand(code));
    }

    @Tool(description = "The current FAA status of one airport: ground stops, ground delays and closures. "
            + "It does not change any score.")
    public ApiResponse<?> getLiveStatus(
            @ToolParam(description = "Airport code, for example LAX") String code,
            ToolContext context) {
        return run(context, "getLiveStatus", () -> service.liveStatus(code));
    }

    @Tool(description = "The weights of the score, the size limit, the data year and the scope of the app. "
            + "Use it for questions about how the score works.")
    public ApiResponse<?> getMethodology(ToolContext context) {
        return run(context, "getMethodology", service::methodology);
    }

    private <T> ApiResponse<?> run(ToolContext context, String tool, Supplier<QueryResult<T>> call) {
        ToolLog log = (ToolLog) context.getContext().get(ToolLog.KEY);
        if (log != null && !log.allowCall()) {
            return ApiResponse.of(new QueryResult.NotAvailable<T>("The limit of " + ToolLog.MAX_CALLS_PER_TURN
                    + " tool calls for one question is reached. Answer with the data that you have.", List.of()));
        }
        ApiResponse<T> response = ApiResponse.of(call.get());
        if (log != null) {
            log.record(tool, json.writeValueAsString(response));
        }
        return response;
    }
}

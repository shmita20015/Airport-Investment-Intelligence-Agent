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

    /** Creates the tools with the engine and the JSON writer that records the tool results. */
    public AirportTools(AirportQueryService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /** Tool: ranks the airports of a region with a weight profile, and with custom weights if the user gives them. */
    @Tool(description = "Rank US airports by an opportunity score. Use it for questions about the best candidates for "
            + "terminal expansion or investment. The percentiles are national. The region only filters the list.")
    public ApiResponse<?> rankAirports(
            @ToolParam(description = "A region name (NEW_ENGLAND, NORTHEAST, MIDWEST, SOUTH, WEST, PACIFIC_NORTHWEST, WEST_COAST, "
                    + "SOUTHWEST, SOUTHEAST, GREAT_LAKES, GULF_COAST, ROCKY_MOUNTAIN, TERRITORIES and others), "
                    + "a state code such as TX, a comma list such as CA,OR,WA, or ALL for the whole country", required = false) String region,
            @ToolParam(description = "Weight profile: terminal (terminal expansion) or general", required = false) String profile,
            @ToolParam(description = "Number of airports, 1 to 50. Default 10", required = false) Integer topN,
            @ToolParam(description = "Only for a what-if question: custom weights as component:weight, for example "
                    + "scale:0 or congestion:0.5,growth:0.3. Components: congestion, growth, load_factor, terminal_strain, "
                    + "long_haul, scale. The components that are not named share the rest. Leave empty for the normal profile.",
                    required = false) String weights,
            ToolContext context) {
        return run(context, "rankAirports", () -> service.rank(region, profile, topN, weights));
    }

    /** Tool: compares two to six airports and says which one is higher. */
    @Tool(description = "Compare two to six airports. Use focus congestion to compare congestion. Use a profile name "
            + "(terminal or general) to compare the score. Returns the KPIs and percentiles of each airport. "
            + "It also says which airport is higher (focusOrder and kpiComparisons). Use these fields. Do not decide it yourself.")
    public ApiResponse<?> compareAirports(
            @ToolParam(description = "Airport codes or names, for example LAX and Santa Ana") List<String> codes,
            @ToolParam(description = "congestion, terminal or general. Default congestion", required = false) String focus,
            @ToolParam(description = "Only for a what-if question and only with the focus terminal or general: custom "
                    + "weights as component:weight, for example scale:0", required = false) String weights,
            ToolContext context) {
        return run(context, "compareAirports", () -> service.compare(codes, focus, weights));
    }

    /** Tool: all KPIs, the component values and the score of one airport. */
    @Tool(description = "All KPIs, component values and the score of one airport, with the source and the data year of each KPI.")
    public ApiResponse<?> getAirportProfile(
            @ToolParam(description = "Airport code or name, for example SFO or its city name") String airport,
            @ToolParam(description = "Weight profile: terminal or general. Default general", required = false) String profile,
            ToolContext context) {
        return run(context, "getAirportProfile", () -> service.airportProfile(airport, profile));
    }

    /** Tool: the share of long-haul departures of one airport, with and without cargo flights. */
    @Tool(description = "The share of long-haul departures of one airport. Returns the share of passenger flights and "
            + "the share with cargo flights. There is no standard definition of long-haul. The default limit is 2,500 miles.")
    public ApiResponse<?> getLongHaulShare(
            @ToolParam(description = "Airport code or name, for example ANC or its city name") String airport,
            @ToolParam(description = "Minimum route distance in statute miles. Default 2500", required = false) Integer minMiles,
            ToolContext context) {
        return run(context, "getLongHaulShare", () -> service.longHaul(airport, minMiles));
    }

    /** Tool: the number of flights and passengers of one airport. */
    @Tool(description = "How many flights and passengers an airport has. Returns the departures of passenger and cargo flights "
            + "(US carriers, T-100), the passengers, the FAA enplanements and the On-Time flight count, for the data year.")
    public ApiResponse<?> getTrafficVolume(
            @ToolParam(description = "Airport code or name, for example BOS or its city name") String airport,
            ToolContext context) {
        return run(context, "getTrafficVolume", () -> service.trafficVolume(airport));
    }

    /** Tool: the unmet demand index of one airport (a proxy), with its parts. */
    @Tool(description = "The unmet demand index of one airport. It is a PROXY built from load factor, growth and congestion. "
            + "No public data counts unserved flights. Returns the parts of the index, so you can explain why.")
    public ApiResponse<?> getUnmetDemandIndex(
            @ToolParam(description = "Airport code or name, for example SFO or its city name") String airport,
            ToolContext context) {
        return run(context, "getUnmetDemandIndex", () -> service.unmetDemand(airport));
    }

    /** Tool: the current FAA status of one airport. */
    @Tool(description = "The current FAA status of one airport: ground stops, ground delays and closures. "
            + "It does not change any score.")
    public ApiResponse<?> getLiveStatus(
            @ToolParam(description = "Airport code or name, for example LAX or its city name") String airport,
            ToolContext context) {
        return run(context, "getLiveStatus", () -> service.liveStatus(airport));
    }

    /** Tool: the weights, the limits and the scope of the app. */
    @Tool(description = "The weights of the score, the size limit, the data year and the scope of the app. "
            + "Use it for questions about how the score works.")
    public ApiResponse<?> getMethodology(ToolContext context) {
        return run(context, "getMethodology", service::methodology);
    }

    /**
     * Runs one tool call. It checks the call limit of the turn, calls the engine and records the result for the
     * number check.
     */
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

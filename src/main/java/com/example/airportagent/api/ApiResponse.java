package com.example.airportagent.api;

import com.example.airportagent.domain.Envelope;
import com.example.airportagent.service.QueryResult;

import java.util.List;

/**
 * The JSON body of each REST answer.
 *
 * @param status OK, PARTIAL, NOT_AVAILABLE or OUT_OF_SCOPE
 * @param data   the result. It is null for NOT_AVAILABLE and OUT_OF_SCOPE.
 */
public record ApiResponse<T>(String status, T data, Envelope envelope, List<String> missing, String reason,
                             List<String> whatIsAvailable, String scopeRule) {

    /** Converts a result of the service to the JSON body. */
    public static <T> ApiResponse<T> of(QueryResult<T> result) {
        return switch (result) {
            case QueryResult.Ok<T> ok -> new ApiResponse<>("OK", ok.data(), ok.envelope(), List.of(), null, List.of(), null);
            case QueryResult.Partial<T> partial -> new ApiResponse<>("PARTIAL", partial.data(), partial.envelope(),
                    partial.missing(), null, List.of(), null);
            case QueryResult.NotAvailable<T> na -> new ApiResponse<>("NOT_AVAILABLE", null, null, List.of(), na.reason(),
                    na.whatIsAvailable(), null);
            case QueryResult.OutOfScope<T> out -> new ApiResponse<>("OUT_OF_SCOPE", null, null, List.of(), out.reason(),
                    List.of(), out.scopeRule());
        };
    }

    /** OK and PARTIAL give 200. A gap in the data or the scope gives 404. */
    public int httpStatus() {
        return "OK".equals(status) || "PARTIAL".equals(status) ? 200 : 404;
    }
}

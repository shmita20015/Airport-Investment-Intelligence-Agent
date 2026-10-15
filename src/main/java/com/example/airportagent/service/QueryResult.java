package com.example.airportagent.service;

import com.example.airportagent.domain.Envelope;

import java.util.List;

/**
 * The result of a service call. A gap in the data is a normal result with a reason.
 * The code never returns an empty value or a guess (docs/DESIGN-full.md, section 11).
 */
public sealed interface QueryResult<T> {

    /** All data is available. */
    record Ok<T>(T data, Envelope envelope) implements QueryResult<T> {}

    /** The result has data, but some parts are missing. */
    record Partial<T>(T data, List<String> missing, Envelope envelope) implements QueryResult<T> {
        /** Copies the list of missing parts. */
        public Partial {
            missing = List.copyOf(missing);
        }
    }

    /** The data does not exist, or the question has no answer in the data. */
    record NotAvailable<T>(String reason, List<String> whatIsAvailable) implements QueryResult<T> {
        /** Copies the list of what is available. */
        public NotAvailable {
            whatIsAvailable = List.copyOf(whatIsAvailable);
        }
    }

    /** The question is outside the scope of the app. */
    record OutOfScope<T>(String reason, String scopeRule) implements QueryResult<T> {}
}

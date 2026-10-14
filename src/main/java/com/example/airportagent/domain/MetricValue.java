package com.example.airportagent.domain;

import java.util.OptionalDouble;

/**
 * A KPI value. The type tells the quality of the source.
 * A missing value is never zero. The scoring code removes it.
 */
public sealed interface MetricValue {

    /** A value that the source measures directly. */
    record Direct(double value, String source) implements MetricValue {}

    /** A value that stands in for another quantity that has no direct measure. */
    record Proxy(double value, String source, String proxyFor) implements MetricValue {}

    /** No value is available. The reason is for the user. */
    record Missing(String reason) implements MetricValue {}

    /** The number, or empty if the value is missing. */
    default OptionalDouble number() {
        return switch (this) {
            case Direct d -> OptionalDouble.of(d.value());
            case Proxy p -> OptionalDouble.of(p.value());
            case Missing m -> OptionalDouble.empty();
        };
    }
}

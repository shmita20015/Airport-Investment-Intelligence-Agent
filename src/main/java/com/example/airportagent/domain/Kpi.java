package com.example.airportagent.domain;

/** A raw KPI of an airport. Each KPI is converted to a percentile before it is scored. */
public enum Kpi {
    DELAY15_RATE,
    TAXI_OUT_MIN,
    CANCEL_RATE,
    OPS_PER_RUNWAY,
    GROWTH,
    LOAD_FACTOR,
    PAX_PER_DEPARTURE,
    LONG_HAUL_SHARE,
    /** Enplanements of the latest year. It measures the size of the airport. */
    ENPLANEMENTS
}

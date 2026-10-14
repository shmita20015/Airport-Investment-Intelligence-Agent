package com.example.airportagent.domain;

import java.util.List;

/** A component of the opportunity score. Each component has a value from 0 to 100. */
public enum Component {
    CONGESTION(List.of(Kpi.DELAY15_RATE, Kpi.TAXI_OUT_MIN, Kpi.CANCEL_RATE, Kpi.OPS_PER_RUNWAY)),
    GROWTH(List.of(Kpi.GROWTH)),
    LOAD_FACTOR(List.of(Kpi.LOAD_FACTOR)),
    TERMINAL_STRAIN(List.of(Kpi.PAX_PER_DEPARTURE)),
    LONG_HAUL(List.of(Kpi.LONG_HAUL_SHARE)),
    /** The size of the airport. A larger airport gives a larger investment. */
    SCALE(List.of(Kpi.ENPLANEMENTS));

    private final List<Kpi> kpis;

    Component(List<Kpi> kpis) {
        this.kpis = kpis;
    }

    /** The KPIs of this component. The component value is the mean of their percentiles. */
    public List<Kpi> kpis() {
        return kpis;
    }
}

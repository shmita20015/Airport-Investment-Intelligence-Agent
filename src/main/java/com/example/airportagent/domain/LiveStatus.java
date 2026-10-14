package com.example.airportagent.domain;

import java.util.List;

/**
 * The current FAA status of an airport (National Airspace System status).
 * It is not an input of any score.
 *
 * @param updateTime         the update time that the FAA service reports
 * @param events             ground stops, ground delays, delays and full closures
 * @param restrictionNotices the number of closure notices that only restrict some flights (for example general aviation)
 */
public record LiveStatus(String airport, String updateTime, List<Event> events, int restrictionNotices) {

    public LiveStatus {
        events = List.copyOf(events);
    }

    public boolean hasActiveEvents() {
        return !events.isEmpty();
    }

    public enum EventType { GROUND_STOP, GROUND_DELAY, DELAY, CLOSURE }

    /** @param details extra text of the FAA entry (for example the end time) */
    public record Event(EventType type, String reason, String details) {}
}

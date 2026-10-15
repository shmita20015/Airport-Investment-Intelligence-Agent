package com.example.airportagent.domain;

/** The identity of an airport. The IATA code is the main key of the app (an FAA ID if no IATA code exists). */
public record Airport(String iata, String faaId, String name, String city, String state,
                      int runwayCount, HubClass hubClass) {}

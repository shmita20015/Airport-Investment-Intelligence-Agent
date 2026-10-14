package com.example.airportagent.data;

import com.example.airportagent.domain.LiveStatus;

import java.io.IOException;

/** The live call to the FAA National Airspace System status. */
public interface FaaStatusClient {

    /**
     * The current status of one airport.
     *
     * @throws IOException if the FAA service does not answer in time or sends data that cannot be read
     */
    LiveStatus statusOf(String airportCode) throws IOException;
}

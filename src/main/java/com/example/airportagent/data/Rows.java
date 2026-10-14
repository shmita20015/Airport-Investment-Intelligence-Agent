package com.example.airportagent.data;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Rows of the data files. A value that is not in the file is empty. It is never zero. */
public final class Rows {

    private Rows() {}

    /** T-100 passenger and cargo totals of an airport (flights that leave the airport). */
    public record TrafficRow(String iata, int year, double pax, double seats,
                             double departuresPax, double departuresCargo) {}

    /** Departures in one distance bucket. The bucket starts at minMiles and is 500 miles wide. */
    public record BucketRow(String iata, int minMiles, double departuresPax, double departuresCargo) {}

    /** On-Time Performance rates of an airport. */
    public record OntimeRow(String iata, int year, long flights, OptionalDouble delay15Rate,
                            OptionalDouble cancelRate, OptionalDouble avgTaxiOutMin) {}

    /** FAA enplanements of an airport. */
    public record EnplanementRow(String iata, OptionalLong cy23, OptionalLong cy24, OptionalLong cy25) {}

    /** Facts about the data files, from manifest.json. */
    public record DatasetInfo(int dataYear, String generated) {}
}

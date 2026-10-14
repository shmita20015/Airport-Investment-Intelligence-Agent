package com.example.airportagent.scoring;

import java.util.Collection;
import java.util.OptionalDouble;

/**
 * Long-haul share of departures from distance buckets. This class has no Spring and no AI code.
 *
 * <p>The buckets are 500 miles wide. Thus, a limit that is not a multiple of 500 is rounded down.
 * The result tells the limit that the code used.
 */
public final class LongHaulCalculator {

    public static final int BUCKET_MILES = 500;

    private LongHaulCalculator() {}

    /** The departures that start in a distance bucket (the lower edge of the bucket is minMiles). */
    public record Bucket(int minMiles, double passengerDepartures, double cargoDepartures) {}

    public record Result(OptionalDouble share, int limitRequested, int limitUsed, boolean includesCargo,
                         double longHaulDepartures, double totalDepartures) {

        public boolean limitWasRounded() {
            return limitRequested != limitUsed;
        }
    }

    public static Result share(Collection<Bucket> buckets, int limitMiles, boolean includeCargo) {
        if (limitMiles < 0) {
            throw new IllegalArgumentException("The limit must be zero or more.");
        }
        int limitUsed = (limitMiles / BUCKET_MILES) * BUCKET_MILES;
        double total = 0;
        double longHaul = 0;
        for (Bucket bucket : buckets) {
            double departures = bucket.passengerDepartures() + (includeCargo ? bucket.cargoDepartures() : 0);
            total += departures;
            if (bucket.minMiles() >= limitUsed) {
                longHaul += departures;
            }
        }
        OptionalDouble share = total > 0 ? OptionalDouble.of(longHaul / total) : OptionalDouble.empty();
        return new Result(share, limitMiles, limitUsed, includeCargo, longHaul, total);
    }
}

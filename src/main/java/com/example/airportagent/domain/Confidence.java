package com.example.airportagent.domain;

/** How much of the score weight had data. */
public enum Confidence {
    HIGH, MEDIUM, LOW;

    /** Coverage of 0.9 or more is HIGH. Coverage from 0.7 to 0.9 is MEDIUM. Lower is LOW. */
    public static Confidence fromCoverage(double coverage) {
        if (coverage >= 0.9) {
            return HIGH;
        }
        if (coverage >= 0.7) {
            return MEDIUM;
        }
        return LOW;
    }
}

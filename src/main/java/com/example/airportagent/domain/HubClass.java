package com.example.airportagent.domain;

/** FAA hub class. It comes from the share of national enplanements. */
public enum HubClass {
    LARGE, MEDIUM, SMALL, NONHUB, UNKNOWN;

    /** Convert the code in the FAA file (L, M, S, N). Other values give UNKNOWN. */
    public static HubClass fromFaaCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code.trim().toUpperCase()) {
            case "L" -> LARGE;
            case "M" -> MEDIUM;
            case "S" -> SMALL;
            case "N" -> NONHUB;
            default -> UNKNOWN;
        };
    }
}

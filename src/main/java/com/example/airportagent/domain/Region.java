package com.example.airportagent.domain;

/** A group of states that the analyst can name in a question. */
public enum Region {
    NEW_ENGLAND, OTHER;

    public static Region fromText(String text) {
        return "NEW_ENGLAND".equalsIgnoreCase(text == null ? "" : text.trim()) ? NEW_ENGLAND : OTHER;
    }
}

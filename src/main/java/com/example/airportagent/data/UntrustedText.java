package com.example.airportagent.data;

import java.util.regex.Pattern;

/**
 * Cleans text from an outside source before the app uses it. The text of an outside source can hold
 * instructions for the LLM (prompt injection). This class keeps letters, digits and simple punctuation only,
 * and it limits the length. It removes line breaks, markup, code and links.
 */
public final class UntrustedText {

    static final int MAX_LENGTH = 160;
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{N} .,:;/()+&'%#@_-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+");

    /** Utility class. It has no instance. */
    private UntrustedText() {}

    /** Cleans a text from an outside source. See the comment of the class. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String result = LINK.matcher(text).replaceAll(" ");
        result = NOT_ALLOWED.matcher(result).replaceAll(" ");
        result = SPACES.matcher(result).replaceAll(" ").trim();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH).trim() + "..." : result;
    }
}

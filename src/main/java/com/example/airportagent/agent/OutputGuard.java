package com.example.airportagent.agent;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes links and images from an answer. A prompt injection can make the LLM write a link or an image
 * that carries data to another server. The app has no reason to show a link that no tool result has.
 * A link that a tool result has (for example a source name) is allowed, unless it is an image.
 */
public final class OutputGuard {

    static final String REMOVED = "[link removed]";
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK =
            Pattern.compile("(?i)\\b(?:https?|ftp)://[^\\s)\\]>\"]+|\\bwww\\.[^\\s)\\]>\"]+");

    private OutputGuard() {}

    /**
     * @param sources texts that may hold an allowed link: the tool results
     */
    public static String neutralize(String answer, Collection<String> sources) {
        String result = IMAGE.matcher(answer).replaceAll(REMOVED);
        Matcher matcher = LINK.matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String link = matcher.group();
            boolean known = sources.stream().anyMatch(source -> source.contains(link));
            matcher.appendReplacement(out, Matcher.quoteReplacement(known ? link : REMOVED));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

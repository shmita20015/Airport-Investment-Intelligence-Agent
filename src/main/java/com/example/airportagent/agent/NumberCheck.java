package com.example.airportagent.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds numbers in an answer that no tool result contains. The LLM must not calculate or invent numbers.
 * The check finds numbers only. It does not find a wrong claim in words (docs/DESIGN-full.md, section 8.7).
 */
public final class NumberCheck {

    private static final Pattern IN_ANSWER =
            Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(\\s*(?:million|billion|thousand|[MBK])\\b)?");
    private static final Pattern IN_SOURCE = Pattern.compile("(?<!\\w)-?(?:\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
    private static final double SMALLEST_CHECKED = 10;

    /** Utility class. It has no instance. */
    private NumberCheck() {
    }

    /**
     * Finds the numbers of an answer that no source supports.
     *
     * @param answer  the text of the answer
     * @param sources texts with allowed numbers: the tool results (JSON). The question of the user is not a source.
     * @return the numbers of the answer that no source supports, in the order of the answer
     */
    public static List<String> unsupported(String answer, Collection<String> sources) {
        Set<Double> allowed = new LinkedHashSet<>();
        for (String source : sources) {
            Matcher matcher = IN_SOURCE.matcher(source);
            while (matcher.find()) {
                // The sign is not compared. An answer can say "declined 5.6%" for a value of -0.056.
                allowed.add(Math.abs(Double.parseDouble(matcher.group().replace(",", ""))));
            }
        }
        List<String> result = new ArrayList<>();
        Matcher matcher = IN_ANSWER.matcher(answer);
        while (matcher.find()) {
            String digits = matcher.group(1).replace(",", "");
            double value = Double.parseDouble(digits);
            double scale = scaleOf(matcher.group(2));
            // Small whole numbers are ranks, counts and list numbers. They are not checked.
            if (scale == 1 && value < SMALLEST_CHECKED && !digits.contains(".")) {
                continue;
            }
            if (!supported(value, scale, decimals(digits), allowed)) {
                result.add(matcher.group().trim());
            }
        }
        return result;
    }

    /**
     * True if a number of the answer equals a number of a source. A rounded value, a cut value and a percentage of a
     * share are accepted.
     */
    private static boolean supported(double shown, double scale, int decimals, Set<Double> allowed) {
        double actual = shown * scale;
        double step = scale * Math.pow(10, -decimals);
        double tolerance = 1e-9 * Math.max(1, Math.abs(actual));
        for (double source : allowed) {
            // A share can be shown as a percentage. A KPI in thousands can be shown in millions.
            for (double factor : new double[] {1, 100}) {
                double value = source * factor;
                boolean rounded = Math.abs(value - actual) <= 0.5 * step + tolerance;
                // An LLM often cuts the last digit and does not round it (48.645 shown as 48.64).
                // A whole number must be rounded. A cut whole number is too far from the value.
                boolean cut = decimals > 0 && value >= actual - tolerance && value < actual + step;
                if (rounded || cut) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The factor of the word after a number: million, billion, thousand, or the letters M, B and K. */
    private static double scaleOf(String suffix) {
        if (suffix == null) {
            return 1;
        }
        return switch (suffix.trim().toLowerCase()) {
            case "million", "m" -> 1_000_000;
            case "billion", "b" -> 1_000_000_000;
            case "thousand", "k" -> 1_000;
            default -> 1;
        };
    }

    /** The number of decimal places of a number text. */
    private static int decimals(String digits) {
        int dot = digits.indexOf('.');
        return dot < 0 ? 0 : digits.length() - dot - 1;
    }
}

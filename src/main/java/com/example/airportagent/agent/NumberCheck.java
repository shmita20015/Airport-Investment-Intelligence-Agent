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
 * The check finds numbers only. It does not find a wrong claim in words (DESIGN.md, section 8.7).
 */
public final class NumberCheck {

    private static final Pattern IN_ANSWER =
            Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(\\s*(?:million|billion|thousand|[MBK])\\b)?");
    private static final Pattern IN_SOURCE = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final double SMALLEST_CHECKED = 10;

    private NumberCheck() {
    }

    /**
     * @param answer  the text of the answer
     * @param sources texts with allowed numbers: the tool results (JSON) and the question of the user
     * @return the numbers of the answer that no source supports, in the order of the answer
     */
    public static List<String> unsupported(String answer, Collection<String> sources) {
        Set<Double> allowed = new LinkedHashSet<>();
        for (String source : sources) {
            Matcher matcher = IN_SOURCE.matcher(source);
            while (matcher.find()) {
                allowed.add(Double.parseDouble(matcher.group()));
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

    private static boolean supported(double shown, double scale, int decimals, Set<Double> allowed) {
        double actual = shown * scale;
        double halfStep = 0.5 * scale * Math.pow(10, -decimals);
        for (double source : allowed) {
            // A share can be shown as a percentage. A KPI in thousands can be shown in millions.
            for (double factor : new double[] {1, 100}) {
                if (Math.abs(source * factor - actual) <= halfStep + 1e-9 * Math.max(1, Math.abs(actual))) {
                    return true;
                }
            }
        }
        return false;
    }

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

    private static int decimals(String digits) {
        int dot = digits.indexOf('.');
        return dot < 0 ? 0 : digits.length() - dot - 1;
    }
}

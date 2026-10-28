package com.ep.integration.bdd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Gherkin parser for the BDD layer of this module (zero new
 * dependencies — cucumber is not available offline). Supports exactly the
 * subset the module needs:
 *
 * <ul>
 *   <li>{@code Feature:} / {@code 功能:} lines</li>
 *   <li>{@code Scenario:} / {@code 场景:} lines (also {@code Scenario Outline:} header form)</li>
 *   <li>step lines beginning with Given/When/Then/And/But (case-insensitive)
 *       or the common Chinese keywords 假如/假设 (Given), 当 (When), 那么 (Then),
 *       而且/并且 (And), 但是 (But) — the keyword is stripped from the step text</li>
 * </ul>
 *
 * <p>Blank lines, {@code #} comments, {@code @} tags and free-text feature
 * descriptions are ignored. An unrecognised non-empty line inside a scenario
 * is a hard error (fail fast — never silently drop a step).
 */
public final class GherkinParser {

    /** One Gherkin step with its canonical English keyword and stripped text. */
    public record Step(String keyword, String text) {
    }

    /** A Scenario block: its title and its steps in declaration order. */
    public record Scenario(String name, List<Step> steps) {
    }

    /** A parsed feature: its title and its scenarios, top-down. */
    public record FeatureDoc(String featureName, List<Scenario> scenarios) {
    }

    private GherkinParser() {
    }

    /**
     * Parses a {@code .feature} file located on the test classpath
     * (e.g. {@code "/features/course-lifecycle.feature"}).
     */
    public static FeatureDoc parse(String classpathLocation) throws IOException {
        try (InputStream in = GherkinParser.class.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IOException("Feature file not found on classpath: " + classpathLocation);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseLines(List.of(content.split("\n", -1)));
        }
    }

    /** Core, side-effect-free parsing used by both the classpath loader and unit tests. */
    public static FeatureDoc parseLines(List<String> lines) {
        String featureName = null;
        List<Scenario> scenarios = new ArrayList<>();
        String currentScenarioName = null;
        List<Step> currentSteps = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) {
                continue;
            }
            if (isKeyword(line, "Feature:") || line.startsWith("功能:")) {
                featureName = textAfterKeyword(line, "Feature:", "功能:");
                continue;
            }
            if (isKeyword(line, "Scenario Outline:") || isKeyword(line, "Scenario:") || line.startsWith("场景:")) {
                currentScenarioName = textAfterKeyword(line, "Scenario:", "场景:");
                currentSteps = new ArrayList<>();
                scenarios.add(new Scenario(currentScenarioName, currentSteps));
                continue;
            }
            String keyword = stepKeyword(line);
            if (keyword != null) {
                if (currentSteps == null) {
                    throw new IllegalArgumentException(
                            "Step outside any Scenario: [" + keyword + "] " + line);
                }
                String text = line.substring(keyword.length()).trim();
                currentSteps.add(new Step(english(keyword), text));
                continue;
            }
            if (currentSteps != null) {
                // Inside a scenario: an unrecognised line must not be silently skipped.
                throw new IllegalArgumentException(
                        "Unrecognised line inside scenario \"" + currentScenarioName + "\": " + line);
            }
            // Free-text feature description outside scenarios: ignored.
        }
        return new FeatureDoc(featureName == null ? "" : featureName, List.copyOf(scenarios));
    }

    private static boolean isKeyword(String line, String prefix) {
        return line.length() >= prefix.length() && line.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String textAfterKeyword(String line, String... prefixes) {
        for (String prefix : prefixes) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }
        return line;
    }

    /** Returns the keyword prefix of a step line (Chinese keywords first: longest, unambiguous). */
    private static String stepKeyword(String line) {
        for (String kw : new String[]{"假如", "假设", "那么", "而且", "并且", "但是", "当"}) {
            if (line.startsWith(kw)) {
                return kw;
            }
        }
        for (String kw : new String[]{"Given", "When", "Then", "And", "But"}) {
            if (isKeyword(line, kw)) {
                return kw;
            }
        }
        return null;
    }

    /** Maps a Chinese keyword to its canonical English counterpart for reporting. */
    private static String english(String keyword) {
        return switch (keyword) {
            case "假如", "假设" -> "Given";
            case "当" -> "When";
            case "那么" -> "Then";
            case "而且", "并且" -> "And";
            case "但是" -> "But";
            default -> keyword;
        };
    }
}

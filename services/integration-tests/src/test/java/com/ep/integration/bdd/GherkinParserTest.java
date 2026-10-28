package com.ep.integration.bdd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the lightweight Gherkin parser (written test-first, before
 * {@code GherkinParser} existed). The parser only needs to support the subset
 * of Gherkin used by this module: Feature / Scenario / Given / When / Then /
 * And / But step lines (English and common Chinese keywords), with comments,
 * blank lines and tags ignored.
 */
@Tag("integration")
class GherkinParserTest {

    private static List<String> linesOf(String content) {
        return List.of(content.split("\n", -1));
    }

    @Test
    @DisplayName("parses feature name, scenario name and strips Given/When/Then/And keywords")
    void parses_feature_scenario_and_steps() {
        GherkinParser.FeatureDoc doc = GherkinParser.parseLines(linesOf("""
                Feature: Order payment
                  Scenario: Student pays successfully
                    Given a published course
                    When the student places an order
                    And pays with ALIPAY
                    Then the order status becomes PAID
                """));

        assertEquals("Order payment", doc.featureName());
        assertEquals(1, doc.scenarios().size());
        GherkinParser.Scenario scenario = doc.scenarios().get(0);
        assertEquals("Student pays successfully", scenario.name());
        assertEquals(4, scenario.steps().size());
        assertEquals("a published course", scenario.steps().get(0).text());
        assertEquals("Given", scenario.steps().get(0).keyword());
        assertEquals("the student places an order", scenario.steps().get(1).text());
        assertEquals("When", scenario.steps().get(1).keyword());
        assertEquals("pays with ALIPAY", scenario.steps().get(2).text());
        assertEquals("And", scenario.steps().get(2).keyword());
        assertEquals("the order status becomes PAID", scenario.steps().get(3).text());
        assertEquals("Then", scenario.steps().get(3).keyword());
    }

    @Test
    @DisplayName("parses Chinese keywords 假如/当/那么/而且 and Chinese step text verbatim")
    void parses_chinese_keywords_and_text() {
        GherkinParser.FeatureDoc doc = GherkinParser.parseLines(linesOf("""
                功能: 课程生命周期
                  场景: 教师发布课程供学生报名
                    假如 教师 "t1" 已登录系统
                    当 教师创建课程 "Java进阶" 定价 99.0
                    而且 教师发布该课程
                    那么 课程状态为 PUBLISHED
                """));

        assertEquals("课程生命周期", doc.featureName());
        GherkinParser.Scenario scenario = doc.scenarios().get(0);
        assertEquals("教师发布课程供学生报名", scenario.name());
        assertEquals(4, scenario.steps().size());
        assertEquals("教师 \"t1\" 已登录系统", scenario.steps().get(0).text());
        assertEquals("Given", scenario.steps().get(0).keyword());
        assertEquals("教师创建课程 \"Java进阶\" 定价 99.0", scenario.steps().get(1).text());
        assertEquals("When", scenario.steps().get(1).keyword());
        assertEquals("教师发布该课程", scenario.steps().get(2).text());
        assertEquals("教师发布该课程", scenario.steps().get(2).text());
        assertEquals("课程状态为 PUBLISHED", scenario.steps().get(3).text());
        assertEquals("Then", scenario.steps().get(3).keyword());
    }

    @Test
    @DisplayName("keeps scenarios top-down in declaration order and separates them")
    void keeps_scenario_order() {
        GherkinParser.FeatureDoc doc = GherkinParser.parseLines(linesOf("""
                Feature: f
                  Scenario: first
                    Given a
                    When b
                  Scenario: second
                    Given c
                    Then d
                """));

        assertEquals(2, doc.scenarios().size());
        assertEquals("first", doc.scenarios().get(0).name());
        assertEquals(2, doc.scenarios().get(0).steps().size());
        assertEquals("second", doc.scenarios().get(1).name());
        assertEquals("c", doc.scenarios().get(1).steps().get(0).text());
        assertEquals("d", doc.scenarios().get(1).steps().get(1).text());
    }

    @Test
    @DisplayName("ignores comments, blank lines, tags and feature descriptions")
    void ignores_noise_lines() {
        GherkinParser.FeatureDoc doc = GherkinParser.parseLines(linesOf("""
                # leading comment
                @smoke @integration
                Feature: Order payment

                  The course must be published before ordering.

                  Scenario: Student pays successfully
                    # step comment
                    Given a published course

                    Then the order status becomes PAID
                """));

        assertEquals("Order payment", doc.featureName());
        assertEquals(1, doc.scenarios().size());
        assertEquals(2, doc.scenarios().get(0).steps().size());
    }

    @Test
    @DisplayName("fails fast on an unknown line inside a scenario (no silent step drop)")
    void rejects_unknown_line_inside_scenario() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                GherkinParser.parseLines(linesOf("""
                        Feature: f
                          Scenario: s
                            NotAKeyword something
                        """)));
        assertTrue(ex.getMessage().contains("NotAKeyword"));
    }

    @Test
    @DisplayName("parses a .feature file from the test classpath")
    void parses_feature_file_from_classpath() throws IOException {
        GherkinParser.FeatureDoc doc = GherkinParser.parse("/features/course-lifecycle.feature");
        assertEquals(1, doc.scenarios().size());
        assertTrue(doc.scenarios().get(0).steps().size() >= 3);
    }

    @Test
    @DisplayName("missing feature file on classpath surfaces a clear IOException")
    void missing_feature_file_throws() {
        assertThrows(IOException.class, () -> GherkinParser.parse("/features/no-such.feature"));
    }

    /** Utility used only by tests above to read the sample feature. */
    @SuppressWarnings("unused")
    private static String resource(String path) throws IOException {
        try (InputStream in = GherkinParserTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

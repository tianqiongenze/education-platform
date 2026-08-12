package com.ep.data.feature;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FeatureImportance} comparator and
 * {@link FeatureGroup#getTopKFeatures(int)} / {@link FeatureGroup#getBottomKFeatures(int)}.
 */
class FeatureImportanceTest {

    private static FeatureImportance fi(String name, double shap) {
        return new FeatureImportance(name, shap, 0, "m1", LocalDateTime.now());
    }

    @Test
    void test_comparator_shap_descending() {
        List<FeatureImportance> list = new ArrayList<>(List.of(
                fi("a", 0.1),
                fi("b", 0.5),
                fi("c", 0.3)));
        list.sort(FeatureImportance.BY_SHAP_DESC);
        assertEquals("b", list.get(0).featureName(),
                "highest SHAP should be first, got " + list.get(0).featureName());
        assertEquals("c", list.get(1).featureName(),
                "second highest SHAP should be 'c', got " + list.get(1).featureName());
        assertEquals("a", list.get(2).featureName(),
                "lowest SHAP should be last, got " + list.get(2).featureName());
    }

    @Test
    void test_top_k_features() {
        FeatureGroup group = new FeatureGroup();
        group.setImportanceRanking(List.of(
                fi("a", 0.1), fi("b", 0.5), fi("c", 0.3),
                fi("d", 0.4), fi("e", 0.2)));
        List<FeatureImportance> top3 = group.getTopKFeatures(3);
        assertEquals(3, top3.size(), "top-3 should return 3 features, got " + top3.size());
        assertEquals("b", top3.get(0).featureName());
        assertEquals("d", top3.get(1).featureName());
        assertEquals("c", top3.get(2).featureName());
    }

    @Test
    void test_bottom_k_features() {
        FeatureGroup group = new FeatureGroup();
        group.setImportanceRanking(List.of(
                fi("a", 0.5), fi("b", 0.4), fi("c", 0.3),
                fi("d", 0.2), fi("e", 0.1)));
        List<FeatureImportance> bottom2 = group.getBottomKFeatures(2);
        assertEquals(2, bottom2.size(), "bottom-2 should return 2 features, got " + bottom2.size());
        assertEquals("d", bottom2.get(0).featureName());
        assertEquals("e", bottom2.get(1).featureName());
    }

    @Test
    void test_empty_list_returns_empty() {
        FeatureGroup group = new FeatureGroup();
        assertTrue(group.getTopKFeatures(3).isEmpty(), "top-K on empty ranking should be empty");
        assertTrue(group.getBottomKFeatures(3).isEmpty(), "bottom-K on empty ranking should be empty");
    }

    @Test
    void test_k_exceeds_size_returns_all() {
        FeatureGroup group = new FeatureGroup();
        group.setImportanceRanking(List.of(fi("a", 0.5), fi("b", 0.3)));
        assertEquals(2, group.getTopKFeatures(10).size(), "top-K larger than size should return all");
    }
}

package com.ep.integration.bdd;

import java.util.Map;

/**
 * Abstract base for feature-file-driven tests. Subclasses implement
 * {@link #defineSteps()} with lambdas that call the existing in-JVM fakes and
 * call {@link #runFeature(String)} from a JUnit {@code @Test} method to execute
 * every scenario of the feature top-down, step by step.
 *
 * <p>Failure contract: any unimplemented or failing step aborts the run with
 * an {@link AssertionError} carrying the feature name, scenario name and the
 * exact step text — never a stack trace without BDD context.
 */
public abstract class BddScenarioTest {

    /** Subclass supplies the step implementations for its feature. */
    protected abstract Map<String, StepRegistry.Step> defineSteps();

    /**
     * Parses the feature file from the test classpath and executes each
     * scenario top-down against {@link #defineSteps()}.
     */
    protected void runFeature(String classpathLocation) throws Exception {
        GherkinParser.FeatureDoc feature = GherkinParser.parse(classpathLocation);
        if (feature.scenarios().isEmpty()) {
            throw new AssertionError("Feature has no scenarios: " + classpathLocation);
        }
        StepRegistry registry = new StepRegistry();
        defineSteps().forEach(registry::register);
        if (registry.size() == 0) {
            throw new AssertionError("No step definitions supplied for " + classpathLocation);
        }

        for (GherkinParser.Scenario scenario : feature.scenarios()) {
            if (scenario.steps().isEmpty()) {
                throw new AssertionError("Scenario has no steps: " + scenario.name());
            }
            for (GherkinParser.Step step : scenario.steps()) {
                StepRegistry.Step impl = registry.lookup(step.text());
                if (impl == null) {
                    throw new AssertionError(String.format(
                            "未实现的步骤 (missing step definition): [%s] %s — feature=%s, scenario=%s",
                            step.keyword(), step.text(), feature.featureName(), scenario.name()));
                }
                try {
                    impl.run();
                } catch (Throwable t) {
                    throw new AssertionError(String.format(
                            "步骤失败 (step failed): [%s] %s — feature=%s, scenario=%s",
                            step.keyword(), step.text(), feature.featureName(), scenario.name()), t);
                }
            }
        }
    }
}

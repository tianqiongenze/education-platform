package com.ep.data.feature;

import java.io.Serializable;
import java.util.Objects;

/**
 * Definition of a single feature inside a {@link FeatureGroup}.
 */
public class FeatureDef implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String type;
    private String transform;
    private String aggregation;
    private String windowSpec;

    public FeatureDef() {
    }

    public FeatureDef(String name, String type, String transform, String aggregation, String windowSpec) {
        this.name = name;
        this.type = type;
        this.transform = transform;
        this.aggregation = aggregation;
        this.windowSpec = windowSpec;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** @return one of "int","long","double","string","vector" */
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTransform() { return transform; }
    public void setTransform(String transform) { this.transform = transform; }

    /** @return one of "sum","avg","count","max","min","distinct_count" */
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }

    /** @return e.g. "30d","7d","1h" */
    public String getWindowSpec() { return windowSpec; }
    public void setWindowSpec(String windowSpec) { this.windowSpec = windowSpec; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FeatureDef that)) return false;
        return Objects.equals(name, that.name)
                && Objects.equals(type, that.type)
                && Objects.equals(transform, that.transform)
                && Objects.equals(aggregation, that.aggregation)
                && Objects.equals(windowSpec, that.windowSpec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, transform, aggregation, windowSpec);
    }

    @Override
    public String toString() {
        return "FeatureDef{name='" + name + "', type='" + type + "', transform='" + transform
                + "', aggregation='" + aggregation + "', windowSpec='" + windowSpec + '\'' + '}';
    }
}

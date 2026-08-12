package com.ep.data.feature;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A logical group of features materialized to an online (Redis) and offline
 * (Iceberg) store.
 */
public class FeatureGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_ONLINE_STORE = "redis";
    public static final String DEFAULT_OFFLINE_STORE = "iceberg";
    public static final long DEFAULT_ONLINE_TTL_SEC = 86_400L;

    private String groupName;
    private String owner;
    private String onlineStore = DEFAULT_ONLINE_STORE;
    private String offlineStore = DEFAULT_OFFLINE_STORE;
    private long onlineTtlSec = DEFAULT_ONLINE_TTL_SEC;
    private List<FeatureDef> features = new ArrayList<>();
    private List<FeatureImportance> importanceRanking = new ArrayList<>();

    public FeatureGroup() {
    }

    public FeatureGroup(String groupName, String owner, String onlineStore, String offlineStore,
                        long onlineTtlSec, List<FeatureDef> features) {
        this.groupName = groupName;
        this.owner = owner;
        this.onlineStore = onlineStore;
        this.offlineStore = offlineStore;
        this.onlineTtlSec = onlineTtlSec;
        this.features = features == null ? new ArrayList<>() : new ArrayList<>(features);
    }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getOnlineStore() { return onlineStore; }
    public void setOnlineStore(String onlineStore) { this.onlineStore = onlineStore; }

    public String getOfflineStore() { return offlineStore; }
    public void setOfflineStore(String offlineStore) { this.offlineStore = offlineStore; }

    public long getOnlineTtlSec() { return onlineTtlSec; }
    public void setOnlineTtlSec(long onlineTtlSec) { this.onlineTtlSec = onlineTtlSec; }

    public List<FeatureDef> getFeatures() {
        return features;
    }

    public void setFeatures(List<FeatureDef> features) {
        this.features = features == null ? new ArrayList<>() : new ArrayList<>(features);
    }

    /**
     * SHAP-derived importance ranking for this group's features (§12.7). The
     * list is ordered by SHAP value descending when populated by
     * {@link FeatureFeedbackConsumer}.
     *
     * @return unmodifiable list of feature importances (never {@code null})
     */
    public List<FeatureImportance> getImportanceRanking() {
        return importanceRanking;
    }

    public void setImportanceRanking(List<FeatureImportance> importanceRanking) {
        this.importanceRanking = importanceRanking == null
                ? new ArrayList<>()
                : new ArrayList<>(importanceRanking);
    }

    /**
     * Return the top {@code k} features by SHAP value (§12.2.3). The ranking
     * list is sorted by SHAP descending before slicing.
     *
     * @param k number of top features to return (negative or zero → empty)
     * @return top-k features by SHAP, or an empty list if none
     */
    public List<FeatureImportance> getTopKFeatures(int k) {
        if (k <= 0 || importanceRanking.isEmpty()) {
            return List.of();
        }
        List<FeatureImportance> sorted = new ArrayList<>(importanceRanking);
        sorted.sort(FeatureImportance.BY_SHAP_DESC);
        int limit = Math.min(k, sorted.size());
        return Collections.unmodifiableList(new ArrayList<>(sorted.subList(0, limit)));
    }

    /**
     * Return the bottom {@code k} features by SHAP value (§12.2.3). The
     * ranking list is sorted by SHAP descending; the bottom-k are the
     * lowest-SHAP entries.
     *
     * @param k number of bottom features to return (negative or zero → empty)
     * @return bottom-k features by SHAP, or an empty list if none
     */
    public List<FeatureImportance> getBottomKFeatures(int k) {
        if (k <= 0 || importanceRanking.isEmpty()) {
            return List.of();
        }
        List<FeatureImportance> sorted = new ArrayList<>(importanceRanking);
        sorted.sort(FeatureImportance.BY_SHAP_DESC);
        int size = sorted.size();
        int limit = Math.min(k, size);
        int start = size - limit;
        return Collections.unmodifiableList(new ArrayList<>(sorted.subList(start, size)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FeatureGroup that)) return false;
        return onlineTtlSec == that.onlineTtlSec
                && Objects.equals(groupName, that.groupName)
                && Objects.equals(owner, that.owner)
                && Objects.equals(onlineStore, that.onlineStore)
                && Objects.equals(offlineStore, that.offlineStore)
                && Objects.equals(features, that.features)
                && Objects.equals(importanceRanking, that.importanceRanking);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupName, owner, onlineStore, offlineStore, onlineTtlSec,
                features, importanceRanking);
    }

    @Override
    public String toString() {
        return "FeatureGroup{groupName='" + groupName + "', owner='" + owner
                + "', onlineStore='" + onlineStore + "', offlineStore='" + offlineStore
                + "', onlineTtlSec=" + onlineTtlSec + ", features=" + features
                + ", importanceRanking=" + importanceRanking + '}';
    }
}

package com.ep.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L1 (Caffeine) tuning knobs, bound at {@code ep.cache.*}.
 *
 * <p>Audit fix (雪崩防护 / L1 支持): the L1 layer used to apply one hard-coded
 * spec (10000 entries / 60s) to EVERY cache name. Hosts can now override
 * {@code maximumSize} and {@code expireAfterWrite} per cache name via:
 *
 * <pre>{@code
 * ep.cache:
 *   default-maximum-size: 10000        # fallback for unconfigured names
 *   default-expire-after-write-sec: 60 # fallback for unconfigured names
 *   specs:
 *     course:
 *       maximum-size: 5000
 *       expire-after-write-sec: 120
 *     bff:aggregation:
 *       maximum-size: 2000
 *       expire-after-write-sec: 90
 * }</pre>
 *
 * <p>Defaults keep the previous behaviour (10000 / 60s) so existing hosts are
 * unaffected.
 */
@ConfigurationProperties(prefix = "ep.cache")
public class CacheProperties {

    /** Per-cache-name L1 overrides (keys are cache names, e.g. {@code course}). */
    private Map<String, CacheSpec> specs = new LinkedHashMap<>();

    /** Fallback maximumSize for cache names without an explicit spec. */
    private long defaultMaximumSize = 10000;

    /** Fallback expireAfterWrite (seconds) for cache names without an explicit spec. */
    private long defaultExpireAfterWriteSec = 60;

    public Map<String, CacheSpec> getSpecs() {
        return specs;
    }

    public void setSpecs(Map<String, CacheSpec> specs) {
        this.specs = specs == null ? new LinkedHashMap<>() : specs;
    }

    public long getDefaultMaximumSize() {
        return defaultMaximumSize;
    }

    public void setDefaultMaximumSize(long defaultMaximumSize) {
        this.defaultMaximumSize = defaultMaximumSize;
    }

    public long getDefaultExpireAfterWriteSec() {
        return defaultExpireAfterWriteSec;
    }

    public void setDefaultExpireAfterWriteSec(long defaultExpireAfterWriteSec) {
        this.defaultExpireAfterWriteSec = defaultExpireAfterWriteSec;
    }

    /** Resolved spec for one cache name: explicit value or the global default. */
    public long resolveMaximumSize(String cacheName) {
        CacheSpec spec = specs.get(cacheName);
        return spec != null && spec.getMaximumSize() != null ? spec.getMaximumSize() : defaultMaximumSize;
    }

    public long resolveExpireAfterWriteSec(String cacheName) {
        CacheSpec spec = specs.get(cacheName);
        return spec != null && spec.getExpireAfterWriteSec() != null
                ? spec.getExpireAfterWriteSec() : defaultExpireAfterWriteSec;
    }

    /** Per-cache-name L1 overrides; unset fields fall back to the global defaults. */
    public static class CacheSpec {

        private Long maximumSize;
        private Long expireAfterWriteSec;

        public Long getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(Long maximumSize) {
            this.maximumSize = maximumSize;
        }

        public Long getExpireAfterWriteSec() {
            return expireAfterWriteSec;
        }

        public void setExpireAfterWriteSec(Long expireAfterWriteSec) {
            this.expireAfterWriteSec = expireAfterWriteSec;
        }
    }
}

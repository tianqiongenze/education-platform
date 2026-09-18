package com.ep.data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-216: {@code ep.data.*} — one property family, any storage infrastructure.
 *
 * <pre>
 * ep.data:
 *   enabled: true
 *   provider: cockroachdb            # tidb | cockroachdb | mysql | h2
 *   read-replicas: true              # false collapses READ onto the write pool
 *   follower-reads: true             # CRDB only: read pool uses follower reads
 *   write:
 *     url: ...                       # full JDBC url (overrides host/db composition)
 *     host: tidb-primary:4000
 *     database: ep_business
 *     username: root
 *     password: ""
 *     maximum-pool-size: 10
 *   read:
 *     url: ...
 *     host: tidb-replica:4000
 *     database: ep_business
 *     username: root
 *     maximum-pool-size: 20
 * </pre>
 */
@ConfigurationProperties(prefix = "ep.data")
public class EpDataProperties {

    /** Master switch — off by default so existing service configs stay untouched. */
    private boolean enabled = false;

    /** Which storage infrastructure this service uses. */
    private DataSourceProvider provider = DataSourceProvider.H2;

    /** False when the provider has no replicas: READ resolves to the write pool. */
    private boolean readReplicas = true;

    /** CRDB stale follower reads on the read pool (default_transaction_use_followers). */
    private boolean followerReads = true;

    private final Pool write = new Pool();
    private final Pool read = new Pool();

    public static class Pool {
        /** Full JDBC url; when set, host/database are ignored. */
        private String url;

        private String host;

        private String database;

        private String username = "root";

        private String password = "";

        private String schemaSql;

        private int maximumPoolSize = 10;

        private int minimumIdle = 2;

        private long connectionTimeoutMillis = 3_000;

        /** Provider-specific session init (e.g. CRDB follower reads). */
        private String connectionInitSql;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSchemaSql() {
            return schemaSql;
        }

        public void setSchemaSql(String schemaSql) {
            this.schemaSql = schemaSql;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeoutMillis() {
            return connectionTimeoutMillis;
        }

        public void setConnectionTimeoutMillis(long connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
        }

        public String getConnectionInitSql() {
            return connectionInitSql;
        }

        public void setConnectionInitSql(String connectionInitSql) {
            this.connectionInitSql = connectionInitSql;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DataSourceProvider getProvider() {
        return provider;
    }

    public void setProvider(DataSourceProvider provider) {
        this.provider = provider;
    }

    public boolean isReadReplicas() {
        return readReplicas;
    }

    public void setReadReplicas(boolean readReplicas) {
        this.readReplicas = readReplicas;
    }

    public boolean isFollowerReads() {
        return followerReads;
    }

    public void setFollowerReads(boolean followerReads) {
        this.followerReads = followerReads;
    }

    public Pool getWrite() {
        return write;
    }

    public Pool getRead() {
        return read;
    }
}

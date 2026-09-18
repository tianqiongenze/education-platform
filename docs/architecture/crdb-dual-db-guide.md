# CockroachDB Dual-Database Access Layer Guide

> **Ticket:** D-216 · **Status:** Production-ready template · **Last updated:** 2026-08-15

This guide documents the platform's two complementary dual-database access
mechanisms: **`ep-data`** (read/write-split within a single database cluster)
and **`DualDbConfig`** (domestic TiDB vs overseas CockroachDB routing). It
serves as the single source of truth for onboarding a service onto either
pattern.

---

## 1. Architecture Overview

```
                         ┌──────────────────────────────────────┐
                         │           Application Layer          │
                         │  (JPA repositories / JdbcTemplate)    │
                         └──────────────────┬───────────────────┘
                                            │
                 ┌──────────────────────────┴──────────────────────────┐
                 │                                                       │
    ┌────────────▼───────────────┐                    ┌─────────────────▼──────────────┐
    │  ep-data (per-service)     │                    │  data-platform DualDbConfig     │
    │  ep.data.enabled=true       │                    │  ep.db.dual.enabled=true        │
    │                             │                    │                                  │
    │  LazyConnectionDataSourceProxy                  │  RoutingDataSource               │
    │          │                  │                    │  (by TenantContext.region)       │
    │  EpRoutingDataSource        │                    │     │           │                │
    │     │         │             │                    │  primary    overseas            │
    │  WRITE       READ           │                    │  (TiDB)     (CockroachDB)       │
    │     │         │             │                    │  MySQL      PostgreSQL          │
    │  ┌───▼───┐ ┌──▼───┐        │                    │  protocol   protocol            │
    │  │ write │ │ read │        │                    └─────────────────────────────────┘
    │  │ pool  │ │ pool │        │
    │  │(leader│ │(foll.│        │
    │  │  reads│ │ reads│        │
    │  └───────┘ └──────┘        │
    │  Same DB cluster           │
    └────────────────────────────┘
```

### Two mechanisms, two scopes

| Aspect | `ep-data` (EpDataAutoConfiguration) | `DualDbConfig` (data-platform) |
|--------|--------------------------------------|-------------------------------|
| **Purpose** | Read/write split within ONE database cluster | Route between TWO different database clusters |
| **Switch** | `ep.data.enabled=true` | `ep.db.dual.enabled=true` |
| **Routing key** | Transaction readOnly flag + `@ReadOnly`/`@WriteRoute` AOP override | `TenantContext.getRegion()` (domestic / overseas) |
| **Pools** | write pool (leader) + read pool (follower/replica) | primary pool (TiDB) + overseas pool (CockroachDB) |
| **Where** | Each business service (`com.ep:ep-data:1.0.0`) | data-platform only (`com.ep.data.cockroachdb`) |
| **Use case** | Scale reads; follower-read latency optimization | Multi-region data residency |

They are **orthogonal** — a service can use `ep-data` for read/write split
while data-platform's `DualDbConfig` handles the cross-cluster routing
upstream. In practice, business services adopt `ep-data`; only
data-platform itself runs `DualDbConfig`.

---

## 2. ep.data Configuration Reference

### 2.1 Property family

All properties live under the `ep.data.*` prefix, bound by
`EpDataProperties` (`@ConfigurationProperties(prefix = "ep.data")`).

| Property | Default | Description |
|----------|---------|-------------|
| `ep.data.enabled` | `false` | Master switch. When `false`, the service's own `DataSourceAutoConfiguration` is untouched. |
| `ep.data.provider` | `h2` | `tidb` / `cockroachdb` / `mysql` / `h2`. Determines driver class, URL template, and whether read replicas are supported. |
| `ep.data.read-replicas` | `true` | When `false` (or provider doesn't support replicas), the read pool collapses onto the write pool — same bean graph, no call-site changes. |
| `ep.data.follower-reads` | `true` | CRDB only: injects `SET default_transaction_use_followers = on` as connection-init SQL on the read pool. |
| `ep.data.write.*` | — | Write pool config (see Pool properties below). |
| `ep.data.read.*` | — | Read pool config. |

### 2.2 Pool properties (`write` / `read`)

| Property | Default | Description |
|----------|---------|-------------|
| `url` | *(none)* | Full JDBC URL. When set, overrides `host` + `database` composition. |
| `host` | *(none)* | `host:port` — composed with provider URL template. |
| `database` | *(none)* | Database/schema name. |
| `username` | `root` | Pool username. |
| `password` | `""` | Pool password. |
| `maximum-pool-size` | `10` | HikariCP max pool size. |
| `minimum-idle` | `2` | HikariCP min idle connections. |
| `connection-timeout-millis` | `3000` | HikariCP connection timeout. |
| `connection-init-sql` | *(none)* | Provider-specific session init SQL. |
| `schema-sql` | *(none)* | Reserved for future schema initialization. |

### 2.3 DataSourceProvider matrix

| Provider | Driver | URL Template | Supports Read Replicas |
|----------|--------|--------------|----------------------|
| `tidb` | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://%s/%s` | Yes |
| `cockroachdb` | `org.postgresql.Driver` | `jdbc:postgresql://%s/%s` | Yes |
| `mysql` | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://%s/%s` | Yes |
| `h2` | `org.h2.Driver` | `jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1;MODE=MySQL` | No (read collapses to write) |

---

## 3. Bean Topology (EpDataAutoConfiguration)

```
@AutoConfiguration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ep.data", name = "enabled", havingValue = "true")
@Import(RoutingOverrideAspect.class)
class EpDataAutoConfiguration

    Bean: writeDataSource   (HikariDataSource, destroyMethod = "close")
          → buildPool(props, props.write, readOnly=false)
          → CRDB: sets ApplicationName = "edu-platform-rw"

    Bean: readDataSource    (HikariDataSource, destroyMethod = "close")
          → if (!readReplicas || !provider.supportsReadReplicas()) → return writePool
          → else buildPool(props, props.read, readOnly=true)
          → CRDB + followerReads: connectionInitSql = "SET default_transaction_use_followers = on"

    Bean: dataSource         (@Primary DataSource)
          → EpRoutingDataSource with targets {WRITE_KEY → writePool, READ_KEY → readPool}
          → defaultTargetDataSource = writePool
          → wrapped in LazyConnectionDataSourceProxy (defers routing decision to first statement)
```

### Why LazyConnectionDataSourceProxy?

Spring's `AbstractRoutingDataSource` determines the target connection at
`getConnection()` time. If the connection is acquired before the transaction
readOnly flag is set (which can happen with eager JPA initialization), the
routing decision is wrong. `LazyConnectionDataSourceProxy` defers connection
acquisition until the first actual SQL statement — by which point
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()` is
correctly populated. This is the standard fix (cf. Vlad Mihalcea, Baeldung).

---

## 4. Read/Write Split Routing Rules

`EpRoutingDataSource.determineCurrentLookupKey()` evaluates in priority order:

| Priority | Condition | Routes to |
|----------|-----------|-----------|
| 1 | `RoutingContextHolder.currentOverride()` is non-null | `WRITE_KEY` or `READ_KEY` per override |
| 2 | Transaction is active AND `isCurrentTransactionReadOnly()` | `READ_KEY` |
| 3 | Default (no tx, or non-read-only tx) | `WRITE_KEY` |

### 4.1 Transaction-based routing

```java
@Transactional(readOnly = true)   // → routes to READ pool
public List<Order> findByUserId(Long userId) { ... }

@Transactional                     // → routes to WRITE pool (default)
public void saveOrder(Order order) { ... }
```

### 4.2 AOP override routing

For cases where the transaction flag alone is insufficient (e.g.,
read-your-own-writes after a write, or forcing a read onto the primary for
freshness), use `@ReadOnly` or `@WriteRoute` annotations:

```java
@ReadOnly                         // forces READ pool regardless of tx flag
public OrderSummary getSummary(Long id) { ... }

@WriteRoute                       // forces WRITE pool (e.g., after a write in same tx)
public Order refreshAndRead(Long id) { ... }
```

`RoutingOverrideAspect` wraps the annotated method in a
`RoutingContextHolder.Scope` — exception-safe and nested-safe (the override
is restored on exit).

### 4.3 H2 / collapsed mode

When `provider=h2` or `read-replicas=false`, the read pool bean IS the write
pool (same object reference). Both `WRITE_KEY` and `READ_KEY` resolve to the
same HikariCP pool — the routing logic is exercised but no separate
connection pool is created. This lets dev-local profiles test the full
dual-pool code path without a second database.

---

## 5. CockroachDB Follower Reads

### 5.1 What are follower reads?

CockroachDB uses Raft consensus — every write must be acknowledged by the
leaseholder (the "leader" for a range). By default, reads also go to the
leaseholder to guarantee linearizability. This means a read in an overseas
region must round-trip to wherever the leaseholder lives, even if a local
replica has the data.

**Follower reads** allow reads to be served from the nearest replica instead
of the leaseholder, trading freshness (reads see data as of ~4.8 seconds ago,
the closed timestamp interval) for dramatically lower latency — especially
for geo-distributed deployments.

### 5.2 How ep-data enables it

When `provider=cockroachdb` AND `follower-reads=true`, the read pool's
HikariCP connections are initialized with:

```sql
SET default_transaction_use_followers = on
```

This session-level setting applies to all read-only transactions on that
connection. The write pool is unaffected — writes always go to the
leaseholder.

### 5.3 When to use follower reads

| Scenario | Use follower reads? |
|----------|-------------------|
| Dashboard analytics, aggregate reports | Yes — staleness is acceptable |
| User profile / settings lookup | Yes — eventual consistency is fine |
| Payment balance verification | **No** — use `@WriteRoute` to force primary |
| After a write, read-your-own-writes | **No** — use `@WriteRoute` or ensure same connection |
| Audit log queries | Yes |

### 5.4 Freshness guarantee

CRDB follower reads are bounded by the `closed_timestamp` interval (default
~4.8s). Reads return data as of at least `now() - 4.8s`. For exact staleness
budget, query `crdb_internal.cluster_settings` for
`kv.closed_timestamp.target_duration`.

---

## 6. Per-Service Storage Guide

| Service | Dev-local DB | Prod provider | Prod database | Notes |
|---------|-------------|---------------|---------------|-------|
| core-business | `ep_business` (H2/MySQL mode) | cockroachdb | `ep_business` | |
| grading-service | `ep_itbackend` (H2/MySQL mode) | cockroachdb | `ep_grading` | |
| boardgame-service | `ep_boardgame` (H2/MySQL mode) | cockroachdb | `ep_boardgame` | |
| senior-care | `ep_elder` (H2/MySQL mode) | cockroachdb | `ep_senior` | |
| knowledge-service | `ep_knowledge` (H2/PostgreSQL mode) | cockroachdb | `ep_knowledge` | Targets PG-wire in prod |
| tenant-service | `ep_tenant` (H2/PostgreSQL mode) | cockroachdb | `ep_tenant` | Targets PG-wire in prod |
| order-platform-service | `orderdb` (H2/MySQL mode) | cockroachdb | `ep_order` | |
| payment-platform-service | `paymentdb` (H2/MySQL mode) | cockroachdb | `ep_payment` | Financial writes must use `@WriteRoute` |

### knowledge-service & tenant-service note

These services use H2 in `MODE=PostgreSQL` for local development (rather
than `MODE=MySQL` like the other services) because they target PostgreSQL in
production. CockroachDB is PostgreSQL-wire compatible, so the `cockroachdb`
provider works seamlessly with these services — no dialect changes needed.

---

## 7. DualDbConfig vs EpDataAutoConfiguration

### 7.1 Relationship

```
                    data-platform (gateway/aggregator)
                          │
            ┌─────────────┴─────────────┐
            │  DualDbConfig              │
            │  ep.db.dual.enabled=true   │
            │  Routes by tenant region   │
            │  domestic → TiDB           │
            │  overseas → CockroachDB    │
            └─────────────┬─────────────┘
                          │
         ┌────────────────┼────────────────┐
         │                │                │
   ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐
   │ Service A  │   │ Service B  │   │ Service C  │
   │ ep-data    │   │ ep-data    │   │ ep-data    │
   │ R/W split  │   │ R/W split  │   │ R/W split  │
   └───────────┘   └───────────┘   └───────────┘
```

### 7.2 Key differences

| | `DualDbConfig` | `EpDataAutoConfiguration` |
|---|---|---|
| **Module** | `data-platform` | `ep-data` (shared SDK) |
| **Package** | `com.ep.data.cockroachdb` | `com.ep.data` |
| **Switch** | `ep.db.dual.enabled` | `ep.data.enabled` |
| **Routing** | `RoutingDataSource` (by `TenantContext.getRegion()`) | `EpRoutingDataSource` (by tx readOnly + AOP override) |
| **Targets** | TiDB (primary) + CockroachDB (overseas) | Write pool (leader) + Read pool (follower/replica) |
| **Scope** | Cross-cluster (two physical databases) | Within-cluster (read replicas / followers) |
| **When to use** | Multi-region data residency | Read scalability + follower-read latency |

### 7.3 Can they coexist?

Yes. `DualDbConfig` is active only in `data-platform` and routes between
TiDB and CockroachDB at the cluster level. Each downstream business service
uses `ep-data` for read/write split within whichever cluster it lands on.
They operate at different layers of the routing hierarchy and do not
conflict.

---

## 8. Dev-Local Configuration Template

Every service's `application-dev-local.yml` includes an `ep.data` block with
`enabled: false` — the dual pool is dormant and the service's existing
`spring.datasource.*` H2 setup is authoritative.

```yaml
ep:
  data:
    enabled: false
    provider: h2
    read-replicas: false
    follower-reads: false
    write:
      database: <db_name>
      maximum-pool-size: 10
    read:
      database: <db_name>
      maximum-pool-size: 10
```

To test the dual-pool code path locally, set `enabled: true`. The read pool
collapses onto the write pool (H2 doesn't support replicas), but
`EpRoutingDataSource`, `LazyConnectionDataSourceProxy`, and the AOP aspect
are all instantiated and exercised.

---

## 9. Production Configuration Template

Every service's `application-prod.yml` includes an `ep.data` block targeting
CockroachDB with follower reads enabled. `enabled` defaults to
`${EP_DATA_ENABLED:false}` so the dual pool only activates when the
environment variable is set — allowing a gradual rollout.

```yaml
ep:
  data:
    enabled: ${EP_DATA_ENABLED:false}
    provider: cockroachdb
    read-replicas: true
    follower-reads: true
    write:
      host: ${CRDB_PRIMARY:crdb-primary:26257}
      database: <db_name>
      username: ${DB_USERNAME:root}
      password: ${DB_PASSWORD:}
      maximum-pool-size: 10
    read:
      host: ${CRDB_FOLLOWER:crdb-follower:26257}
      database: <db_name>
      username: ${DB_USERNAME:root}
      password: ${DB_PASSWORD:}
      maximum-pool-size: 20
```

---

## 10. Production Deployment Checklist

### 10.1 Pre-deployment

- [ ] **CRDB cluster provisioned** — at least 3 nodes per region; multi-region
      topology configured if geo-distributed.
- [ ] **Database created** — `CREATE DATABASE <db_name>;` on the CRDB cluster
      (e.g., `ep_business`, `ep_grading`, `ep_order`, etc.).
- [ ] **User/role created** — dedicated app user (not `root`); granted
      `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the target database.
- [ ] **SSL configured** — CRDB connections should use `sslmode=require` or
      `sslmode=verify-full`; add `?sslmode=require` to the JDBC URL via
      `ep.data.write.url` / `ep.data.read.url`.
- [ ] **Follower reads region config** — ensure CRDB `kv.closed_timestamp.
      target_duration` is tuned for your freshness budget (default ~4.8s).
- [ ] **Connection pool sizing** — review `maximum-pool-size` for write (10)
      and read (20) pools; adjust based on expected concurrency and CRDB node
      count.

### 10.2 Environment variables

| Variable | Example | Required |
|----------|---------|----------|
| `EP_DATA_ENABLED` | `true` | Yes — activates dual pool |
| `CRDB_PRIMARY` | `crdb-primary.region1.internal:26257` | Yes |
| `CRDB_FOLLOWER` | `crdb-follower.region2.internal:26257` | Yes (if `read-replicas: true`) |
| `DB_USERNAME` | `edu_app` | Yes |
| `DB_PASSWORD` | *(secret)* | Yes |

### 10.3 Activation

1. Deploy the service with `EP_DATA_ENABLED=true`.
2. Verify logs show: `[ep-data] read/write split active: provider=COCKROACHDB, followerReads=true, write=..., read=...`.
3. Confirm no `DataSourceAutoConfiguration` warnings (the `@AutoConfigureBefore`
   guard ensures ep-data wins).
4. Check CRDB `SHOW SESSIONS` — connections should appear with
   `ApplicationName = edu-platform-rw` and `edu-platform-ro`.

### 10.4 Validation

- [ ] **Write path** — insert a record; verify it lands on the primary (write pool).
- [ ] **Read path** — query with `@Transactional(readOnly = true)`; verify
      CRDB session shows `default_transaction_use_followers = on`.
- [ ] **Staleness** — follower reads have ~4.8s lag; write then immediately
      read in a read-only tx — you may see stale data. Use `@WriteRoute` for
      read-your-own-writes.
- [ ] **Pool metrics** — HikariCP metrics exposed via `/actuator/prometheus`;
      monitor `hikaricp_connections_active`, `hikaricp_connections_pending`,
      `hikaricp_connections_timeout`.
- [ ] **Failover** — if CRDB primary node fails, write pool connections should
      reconnect to the new leaseholder; read pool (follower reads) should be
      unaffected.

### 10.5 Rollback

Set `EP_DATA_ENABLED=false` (or remove the env var). The service reverts to
its `spring.datasource.*` single-pool configuration with zero code changes.
This is the safe rollback path — no schema migration, no redeploy needed
beyond a config refresh / restart.

---

## 11. Common Pitfalls

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Eager connection acquisition | Reads go to write pool despite `readOnly=true` | Ensure `LazyConnectionDataSourceProxy` is in the bean chain (it is, by default in ep-data) |
| Read-your-own-writes on follower | Fresh write not visible in subsequent read | Use `@WriteRoute` on the read method, or `@Transactional` (non-read-only) |
| `MODE=MySQL` H2 with PG-targeting service | Dialect mismatch in dev | knowledge-service/tenant-service use `MODE=PostgreSQL` — don't change |
| Forgetting `ep.data.enabled` | Dual pool not active; logs show no `[ep-data]` line | Set `EP_DATA_ENABLED=true` in the environment |
| Using `root` in prod | Over-privileged connections | Create a dedicated app user; set `DB_USERNAME` |
| SSL disabled | Plaintext DB traffic | Add `?sslmode=require` to `ep.data.*.url` |

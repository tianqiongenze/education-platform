# DEPRECATED: ep-rw-java

> **Status:** DEPRECATED (forRemoval = false, since = D-094)
> **Date:** 2026-08-08
> **Do NOT delete** — source retained for future read-write-splitting reuse.

## What this module does

`ep-rw-java` is a Spring Boot autoconfigure SDK providing **read-write
splitting** with multi-cell TiDB follower-read support:

- `DataSourceType` — routing key enum: `WRITE` / `READ` / `FOLLOWER_READ`
- `@DataSource` — method/type annotation (with `consistent()`, `tiflash()`,
  `followerRead()`) drives routing via an AOP aspect
- `DataSourceAspect` — `@Around` aspect that sets the `RoutingHolder`
  ThreadLocal before the call and restores/clears it afterward (anti-dirty-read)
- `RoutingDataSource` / `RoutingHolder` — `AbstractRoutingDataSource` +
  ThreadLocal routing key
- `DataSourceConfig` — three HikariCP data sources (write / read /
  follower-read) wired into a `LazyConnectionDataSourceProxy`

Originally introduced for D-041 (TiDB multi-region disaster tolerance).

## Why it is deprecated

The **D-094 code scan** confirmed **0 consumers**:

- No service imports `com.ep.rw.*` outside this module
- No `pom.xml` declares a `<dependency>` on `ep-rw-java`
- The artifact is **not** installed to the local `.m2` cache

Verified with:

```bash
grep -rn "import com.ep.rw" services/ --include="*.java" | grep -v ep-rw-java
grep -rn "ep-rw-java" services/ --include="pom.xml" | grep -v "ep-rw-java/pom.xml"
```

Both return empty.

The decision is **deprecate, not delete** (see "Alternatives" below). The
code is working, tested, and represents non-trivial TiDB follower-read +
AOP knowledge that may be reused by future business units (BU expansion).

## How to re-activate

If a future service needs read-write splitting:

1. Add the dependency to the target service's `pom.xml`:
   ```xml
   <dependency>
     <groupId>com.ep</groupId>
     <artifactId>ep-rw-java</artifactId>
     <version>1.0.0</version>
   </dependency>
   ```
2. Enable it in the service's `application.yml`:
   ```yaml
   ep:
     rw:
       enabled: true                 # default true
       follower-read:
         enabled: false              # set true for cross-cell TiDB follower read
   spring:
     datasource:
       write:   { jdbc-url: ..., username: ..., password: ... }
       read:    { jdbc-url: ..., username: ..., password: ... }
       # follower-read: { ... }      # only when ep.rw.follower-read.enabled=true
   ```
3. Annotate read-heavy methods: `@DataSource(followerRead = true)`.
4. Remove the `@Deprecated` annotations from the public classes in this
   module (added by D-094) — see the source files under
   `src/main/java/com/ep/rw/`.

## Alternatives considered (Iron Law 8)

| # | Approach | Trade-off |
|---|----------|-----------|
| 1 | **Deprecate in place (CHOSEN)** | Keeps tested code for future reuse; signals "unused" via `@Deprecated` + this file; zero build risk. Risk: stale code drifts if untouched for long. |
| 2 | **Delete entirely** | Removes dead code permanently. Risk: destroys non-trivial TiDB follower-read + AOP work that future BUs may want; re-implementation cost is high. Rejected for a 0-consumer-but-working module. |
| 3 | **Archive to `archive/` or git tag** | Moves code out of the active tree. Cleaner working tree, but breaks IDE discoverability and the `mvn` build history; recovery requires `git show` / copy-back. Over-engineering for two small modules. |

## Files annotated as `@Deprecated(forRemoval = false, since = "D-094")`

- `src/main/java/com/ep/rw/DataSourceType.java`
- `src/main/java/com/ep/rw/DataSource.java`
- `src/main/java/com/ep/rw/DataSourceAspect.java`
- `src/main/java/com/ep/rw/DataSourceConfig.java`
- `src/main/java/com/ep/rw/RoutingDataSource.java`
- `src/main/java/com/ep/rw/RoutingHolder.java`

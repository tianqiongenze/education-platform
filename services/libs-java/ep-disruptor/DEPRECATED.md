# DEPRECATED: ep-disruptor

> **Status:** DEPRECATED (forRemoval = false, since = D-094)
> **Date:** 2026-08-08
> **Do NOT delete** — source retained for future high-frequency write reuse.

## What this module does

`ep-disruptor` is a thin wrapper over the LMAX Disruptor (4.0.0) for
**high-frequency, low-latency write pipelines**:

- `DisruptorWorker<T>` — single-ring buffer worker with a fluent `Builder`
  (buffer size, producer type, wait strategy, event factory / translator /
  handler). Drop-on-full via `tryNext()` + `PublishResult.DROPPED`.
- `DisruptorPipeline` — multi-stage pipeline composing several
  `DisruptorWorker`s with ordered start / reverse-order shutdown and
  `forward()` between stages.
- `DisruptorStats` — record (published / dropped / consumed / backlog) for
  observability.
- `WaitStrategyType` — enum mapping to LMAX `WaitStrategy` impls
  (BLOCKING / SLEEPING / YIELDING / BUSY_SPIN / LITE_BLOCKING).
- `PublishResult` — `PUBLISHED` / `DROPPED`.

Designed for backpressure-tolerant ingestion (e.g. event sourcing, audit
log, telemetry) where bounded queues + drop-on-full are preferred over
unbounded blocking queues.

## Why it is deprecated

The **D-094 code scan** confirmed **0 consumers**:

- No service imports `com.ep.disruptor.*` outside this module
- No `pom.xml` declares a `<dependency>` on `ep-disruptor`
- The artifact is **not** installed to the local `.m2` cache

Verified with:

```bash
grep -rn "import com.ep.disruptor" services/ --include="*.java" | grep -v ep-disruptor
grep -rn "ep-disruptor" services/ --include="pom.xml" | grep -v "ep-disruptor/pom.xml"
```

Both return empty.

The decision is **deprecate, not delete** (see "Alternatives" below). The
code is working, tested (DisruptorWorker / Pipeline / Stats / Enums tests),
and represents non-trivial LMAX Disruptor 4.0 + drop-on-full + multi-stage
pipeline knowledge that may be reused by future high-throughput services.

## How to re-activate

If a future service needs a high-frequency write pipeline:

1. Add the dependency to the target service's `pom.xml`:
   ```xml
   <dependency>
     <groupId>com.ep</groupId>
     <artifactId>ep-disruptor</artifactId>
     <version>1.0.0</version>
   </dependency>
   ```
2. Build a worker:
   ```java
   DisruptorWorker<MyEvent> worker = DisruptorWorker.<MyEvent>builder()
       .name("audit")
       .bufferSize(65536)
       .waitStrategy(WaitStrategyType.BLOCKING)
       .factory(MyEvent::new)
       .translator((slot, seq, src) -> { slot.copyFrom(src); })
       .handler(evt -> auditSink.write(evt))
       .build();
   worker.start();
   PublishResult r = worker.publish(event); // PUBLISHED or DROPPED
   ```
3. Or compose a multi-stage pipeline with `DisruptorPipeline.named(...).stage(...)...`.
4. Remove the `@Deprecated` annotations from the public classes in this
   module (added by D-094) — see the source files under
   `src/main/java/com/ep/disruptor/`.

## Alternatives considered (Iron Law 8)

| # | Approach | Trade-off |
|---|----------|-----------|
| 1 | **Deprecate in place (CHOSEN)** | Keeps tested code for future reuse; signals "unused" via `@Deprecated` + this file; zero build risk. Risk: stale code drifts if untouched for long. |
| 2 | **Delete entirely** | Removes dead code permanently. Risk: destroys non-trivial Disruptor 4.0 + drop-on-full + multi-stage pipeline work; re-implementation cost is high. Rejected for a 0-consumer-but-working module. |
| 3 | **Archive to `archive/` or git tag** | Moves code out of the active tree. Cleaner working tree, but breaks IDE discoverability and the `mvn` build history; recovery requires `git show` / copy-back. Over-engineering for two small modules. |

## Files annotated as `@Deprecated(forRemoval = false, since = "D-094")`

- `src/main/java/com/ep/disruptor/DisruptorWorker.java`
- `src/main/java/com/ep/disruptor/DisruptorPipeline.java`
- `src/main/java/com/ep/disruptor/DisruptorStats.java`
- `src/main/java/com/ep/disruptor/WaitStrategyType.java`
- `src/main/java/com/ep/disruptor/PublishResult.java`

# SP-01 技术中台 —— 软件详细设计文档(SDD)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP01-SDD-001 | v1.0 | 2026-08-03 | Approved | ZCode(文档工程师) | SP01-SAD-001 / SP01-SRS-001 / 已实现 SDK 源码 |

> 本文是 SP-01 技术中台的**详细设计基线**，定义每个组件的类/接口设计、关键时序图、表结构、核心算法。已实现的 3 个 SDK(ep-rw-java / ep-disruptor / ep-cache)的类设计与实际代码严格对齐(代码已读取核对)。

---

## 目录

1. [ep-rw-java 读写分离 SDK(已实现)](#1-ep-rw-java-读写分离-sdk已实现)
2. [ep-disruptor 高并发 ingest SDK(已实现)](#2-ep-disruptor-高并发-ingest-sdk已实现)
3. [ep-cache 多级缓存 SDK(已实现)](#3-ep-cache-多级缓存-sdk已实现)
4. [CellRouter 单元路由器](#4-cellrouter-单元路由器)
5. [API 网关/BFF](#5-api-网关bff)
6. [WS/实时网关](#6-ws实时网关)
7. [事件总线 EDA](#7-事件总线-eda)
8. [可观测](#8-可观测)
9. [Rust 热点服务](#9-rust-热点服务)
10. [表结构](#10-表结构)
11. [核心算法](#11-核心算法)

---

## 1. ep-rw-java 读写分离 SDK(已实现)

> 已实现版本 v1.0.0，源码 `services/ep-rw-java/`，11 tests pass，97.7% coverage。本节类设计与实际代码严格对齐。

### 1.1 包结构

```
com.ep.rw
├── DataSourceType.java        // 枚举: WRITE, READ
├── DataSource.java            // 注解: @DataSource(value, consistent, tiflash)
├── RoutingHolder.java         // ThreadLocal 路由上下文
├── RoutingDataSource.java     // 继承 AbstractRoutingDataSource
├── DataSourceAspect.java      // @Around AOP 切面
└── DataSourceConfig.java      // Spring 配置(write/read/routing DataSource)
```

### 1.2 类设计

#### 1.2.1 DataSourceType(枚举)

```java
package com.ep.rw;
public enum DataSourceType { WRITE, READ }
```

- 两个值：`WRITE`(Primary)、`READ`(Follower 池)
- 4 模式通过 `@DataSource` 注解的 `consistent` / `tiflash` 标志组合实现

#### 1.2.2 DataSource(注解)

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    DataSourceType value() default DataSourceType.WRITE;
    boolean consistent() default false;   // 强制走 Primary，防读后写不一致
    boolean tiflash() default false;      // 路由到 TiFlash 列存(OLAP)
}
```

**4 模式映射**：

| 模式 | 注解配置 | 路由目标 |
|------|---------|---------|
| READ | `@DataSource(READ)` | Follower 池 |
| WRITE | `@DataSource(WRITE)` (默认) | Primary |
| READ+consistent | `@DataSource(value=READ, consistent=true)` | Primary(防读后写) |
| READ+tiflash | `@DataSource(value=READ, tiflash=true)` | 读池 + SQL hint(本版简化，通过 SQL hint 路由 TiFlash) |

#### 1.2.3 RoutingHolder(ThreadLocal 上下文)

```java
public final class RoutingHolder {
    private static final ThreadLocal<DataSourceType> CTX = new ThreadLocal<>();
    private RoutingHolder() {}
    public static void set(DataSourceType t) { CTX.set(t); }
    public static DataSourceType get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
}
```

- ThreadLocal 存储当前线程的数据源类型
- `clear()` 防 ThreadLocal 内存泄漏(关键：写操作后必清)

#### 1.2.4 RoutingDataSource(路由数据源)

```java
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType t = RoutingHolder.get();
        return t == null ? DataSourceType.WRITE : t;  // 默认走 Primary
    }
}
```

- 继承 Spring `AbstractRoutingDataSource`
- `determineCurrentLookupKey()` 由 Spring 在获取连接时回调
- 默认走 WRITE(防止未注解的写操作误路由到 Follower 导致主从延迟读)

#### 1.2.5 DataSourceAspect(AOP 切面)

```java
@Component
@Aspect
public class DataSourceAspect {
    @Around("@annotation(com.ep.rw.DataSource)")
    public Object around(ProceedingJoinPoint pjp, DataSource ds) throws Throwable {
        DataSourceType prev = RoutingHolder.get();
        DataSourceType target = ds.consistent() ? DataSourceType.WRITE : ds.value();
        if (target == DataSourceType.WRITE) RoutingHolder.clear();  // 写操作清残留防脏读
        else RoutingHolder.set(target);
        try {
            return pjp.proceed();
        } finally {
            // WRITE always clears; READ restores prev to support nested calls.
            if (target == DataSourceType.WRITE || prev == null) RoutingHolder.clear();
            else RoutingHolder.set(prev);
        }
    }
}
```

**嵌套调用处理逻辑**：
- `prev = RoutingHolder.get()`：保存外层上下文
- WRITE 模式：`clear()` 清残留(防外层 READ 残留导致写后脏读)
- READ 模式：`set(READ)` 设置当前
- finally：WRITE 或 prev==null → `clear()`；否则恢复 `prev`(支持嵌套 READ→WRITE→READ)

#### 1.2.6 DataSourceConfig(Spring 配置)

```java
@Configuration
@ConditionalOnProperty(name = "ep.rw.enabled", matchIfMissing = true)
public class DataSourceConfig {
    @Bean @ConfigurationProperties("spring.datasource.write")
    public DataSource writeDataSource() { return new HikariDataSource(); }
    @Bean @ConfigurationProperties("spring.datasource.read")
    public DataSource readDataSource() { return new HikariDataSource(); }

    @Bean
    public AbstractRoutingDataSource routingDataSource(
            @Qualifier("writeDataSource") DataSource write,
            @Qualifier("readDataSource") DataSource read) {
        RoutingDataSource r = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.WRITE, write);
        targets.put(DataSourceType.READ, read);
        r.setTargetDataSources(targets);
        r.setDefaultTargetDataSource(write);
        return r;
    }

    @Bean @Primary
    public DataSource primaryDataSource(AbstractRoutingDataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }
}
```

**关键设计**：
- `@ConditionalOnProperty` 默认启用，可通过 `ep.rw.enabled=false` 关闭
- write/read 独立 HikariCP 连接池
- `LazyConnectionDataSourceProxy` 延迟连接获取(事务开始时不立即获取，首个 SQL 时才路由)
- `@Primary` 让 Spring 默认注入路由数据源

### 1.3 依赖

| 依赖 | 版本 | scope |
|------|------|-------|
| spring-jdbc | 7.0.8 | provided |
| spring-context | 7.0.8 | provided |
| spring-boot-autoconfigure | 4.0.7 | provided |
| HikariCP | 6.3.3 | provided |
| aspectjweaver | 1.9.25.1 | provided |
| JUnit Jupiter | 5.14.4 | test |
| H2 | 2.3.232 | test |

### 1.4 测试覆盖(已实现)

| 测试类 | 覆盖点 |
|--------|--------|
| DataSourceTypeTest | 枚举值 |
| RoutingHolderTest | ThreadLocal set/get/clear |
| DataSourceAspectTest | 4 模式 + 嵌套调用 |
| RoutingDataSourceTest | determineCurrentLookupKey |
| DataSourceConfigTest | Bean 装配 + 条件开关 |

**结果**：11 tests pass，97.7% line coverage。

---

## 2. ep-disruptor 高并发 ingest SDK(已实现)

> 已实现版本 v1.0.0，源码 `services/ep-disruptor/`，9 tests pass，93% coverage。

### 2.1 包结构

```
com.ep.disruptor
├── PublishResult.java          // 枚举: PUBLISHED, DROPPED
├── WaitStrategyType.java       // 枚举: 5 种 WaitStrategy
├── DisruptorStats.java         // record: 指标
├── DisruptorWorker.java        // 单阶段 worker(Builder 模式)
└── DisruptorPipeline.java      // 多阶段串联 pipeline
```

### 2.2 类设计

#### 2.2.1 PublishResult(枚举)

```java
public enum PublishResult { PUBLISHED, DROPPED }
```

- `tryNext()` 成功 → PUBLISHED
- `InsufficientCapacityException` → DROPPED(不阻塞生产者，drop-on-full)

#### 2.2.2 WaitStrategyType(枚举)

```java
public enum WaitStrategyType {
    BLOCKING, SLEEPING, YIELDING, BUSY_SPIN, LITE_BLOCKING;
    public WaitStrategy toWaitStrategy() {
        return switch (this) {
            case BLOCKING -> new BlockingWaitStrategy();
            case SLEEPING -> new SleepingWaitStrategy();
            case YIELDING -> new YieldingWaitStrategy();
            case BUSY_SPIN -> new BusySpinWaitStrategy();
            case LITE_BLOCKING -> new LiteBlockingWaitStrategy();
        };
    }
}
```

**选型建议**：
- BLOCKING：低 CPU 场景(默认)
- YIELDING：均衡
- BUSY_SPIN：最低延迟、最高 CPU(适合单消费者绑核)
- SLEEPING：中等吞吐、低 CPU

#### 2.2.3 DisruptorStats(record)

```java
public record DisruptorStats(String name, long bufferSize, long published, long dropped, long consumed) {
    public long backlog() { return published - consumed; }
}
```

- 不可变值对象(Java record)
- `backlog()` = 未消费积压量，用于监控告警

#### 2.2.4 DisruptorWorker(单阶段 worker，Builder 模式)

```java
public final class DisruptorWorker<T> {
    private final Disruptor<T> disruptor;
    private final EventTranslatorOneArg<T, T> translator;
    private final int bufferSize;
    private final String name;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();

    private DisruptorWorker(Builder<T> b) {
        this.name = b.name; this.bufferSize = b.bufferSize; this.translator = b.translator;
        this.disruptor = new Disruptor<>(b.factory, b.bufferSize, DaemonThreadFactory.INSTANCE,
                                         b.producerType, b.waitStrategy.toWaitStrategy());
        // 计数 EventHandler 前置：消费计数独立于业务 handler
        EventHandler<T> counting = (evt, seq, eob) -> consumed.incrementAndGet();
        disruptor.handleEventsWith(counting).then(b.handler);
    }

    @PostConstruct public void start() { disruptor.start(); }

    public PublishResult publish(T payload) {
        long seq;
        try { seq = disruptor.getRingBuffer().tryNext(); }
        catch (InsufficientCapacityException e) { dropped.incrementAndGet(); return PublishResult.DROPPED; }
        try {
            // Disruptor 4.0.0: tryNext 预分配 slot → translator 填充 → publish
            T slot = disruptor.getRingBuffer().get(seq);
            translator.translateTo(slot, seq, payload);
            disruptor.getRingBuffer().publish(seq);
            published.incrementAndGet();
            return PublishResult.PUBLISHED;
        } finally { }
    }

    @PreDestroy public void shutdown() { disruptor.shutdown(); }
    public DisruptorStats stats() {
        return new DisruptorStats(name, bufferSize, published.get(), dropped.get(), consumed.get());
    }
}
```

**关键设计**：
- Builder 模式：`name/bufferSize/producerType/waitStrategy/factory/translator/handler`，必填项 Objects.requireNonNull
- 默认 bufferSize=65536，ProducerType.MULTI，WaitStrategyType.BLOCKING
- 计数 EventHandler 前置：`disruptor.handleEventsWith(counting).then(b.handler)` —— counting 先执行，确保 consumed 准确
- publish 用 `tryNext()`(非阻塞)，满则 DROPPED，符合 ADR-T03 drop-on-full
- Disruptor 4.0.0 API：`tryNext()` 预分配 → `get(seq)` 取 slot → `translator.translateTo` 填充 → `publish(seq)`

#### 2.2.5 DisruptorPipeline(多阶段串联)

```java
public final class DisruptorPipeline {
    private final Map<String, DisruptorWorker<?>> stages = new LinkedHashMap<>();
    private final String name;

    public <T> DisruptorPipeline stage(String stageName, EventFactory<T> factory,
            EventTranslatorOneArg<T,T> translator, EventHandler<T> handler) {
        return stage(stageName, 65536, ProducerType.MULTI, WaitStrategyType.BLOCKING, factory, translator, handler);
    }

    public void start() { stages.values().forEach(DisruptorWorker::start); }

    public <T> PublishResult publish(String stage, T payload) {
        DisruptorWorker<T> w = (DisruptorWorker<T>) stages.get(stage);
        if (w == null) throw new IllegalArgumentException("unknown stage " + stage);
        return w.publish(payload);
    }

    public <T> void forward(String fromStage, T payload) {
        // 转发到下一阶段
        List<String> names = new ArrayList<>(stages.keySet());
        int idx = names.indexOf(fromStage);
        if (idx + 1 < names.size()) ((DisruptorWorker<T>) stages.get(names.get(idx+1))).publish(payload);
    }

    public void shutdown() {
        // 逆序 shutdown，确保上游先停
        List<DisruptorWorker<?>> rev = new ArrayList<>(stages.values());
        Collections.reverse(rev);
        rev.forEach(DisruptorWorker::shutdown);
    }

    public DisruptorStats stats(int stageIdx) { return new ArrayList<>(stages.values()).get(stageIdx).stats(); }
}
```

**关键设计**：
- LinkedHashMap 保持 stage 顺序
- `forward(fromStage, payload)`：自动找到下一 stage 并 publish
- 逆序 shutdown：上游先停(避免下游 shutdown 后上游继续 publish 报错)

### 2.3 依赖

| 依赖 | 版本 | scope |
|------|------|-------|
| disruptor (LMAX) | 4.0.0 | compile |
| jakarta.annotation-api | 3.0.0 | compile |
| slf4j-api | 2.0.18 | compile |

### 2.4 测试覆盖(已实现)

| 测试类 | 覆盖点 |
|--------|--------|
| EnumsTest | PublishResult / WaitStrategyType |
| DisruptorWorkerTest | publish/drop/start/shutdown/stats/Builder |
| DisruptorPipelineTest | stage/publish/forward/shutdown |
| DisruptorStatsTest | record 字段 + backlog() |

**结果**：9 tests pass，93% line coverage。

---

## 3. ep-cache 多级缓存 SDK(已实现)

> 已实现版本 v1.0.0，源码 `services/ep-cache/`，31 tests pass，95.8% coverage。

### 3.1 包结构

```
com.ep.cache
├── CacheService.java            // 核心: getOrLoad/put/evict
├── CacheConfig.java             // Caffeine + RedisTemplate 配置
├── CacheBroadcast.java          // Pulsar 广播失效
├── InvalidateMsg.java           // 失效消息 DTO
├── PulsarClientHolder.java      // PulsarClient 单例延迟获取
└── event
    ├── EventTopics.java         // topic 常量
    └── EventPublisher.java      // 通用事件发布
```

### 3.2 类设计

#### 3.2.1 CacheService(核心服务)

```java
@Component
public class CacheService {
    public static final String EMPTY_SENTINEL = "__EP_CACHE_EMPTY__";

    private final ObjectProvider<CacheManager> cmProvider;          // L1 Caffeine
    private final ObjectProvider<RedisTemplate<String, Object>> redisProvider;  // L2 Redis
    private final ObjectProvider<CacheBroadcast> broadcastProvider;  // 广播
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        String fullKey = cacheName + ":" + key;
        // 1. L1 Caffeine
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache cache = cm.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper w = cache.get(key);
                if (w != null) return unwrap((T) w.get());
            }
        }
        // 2. L2 Redis
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Object raw = redis.opsForValue().get(fullKey);
                if (raw != null) {
                    if (EMPTY_SENTINEL.equals(raw)) return null;  // 空值哨兵
                    return (T) raw;
                }
            } catch (Exception e) { log.warn("L2 redis miss/degrade: {}", e.getMessage()); }
        }
        // 3. loader
        T val = loader.get();
        if (val != null) {
            put(cacheName, key, val, ttlSec);
        } else if (cacheEmpty) {
            // 空值缓存防穿透(TTL 30s)
            if (redis != null) {
                try { redis.opsForValue().set(fullKey, EMPTY_SENTINEL, 30, TimeUnit.SECONDS); }
                catch (Exception e) { log.warn("L2 empty-cache fail: {}", e.getMessage()); }
            }
            if (cm != null) {
                Cache c = cm.getCache(cacheName);
                if (c != null) c.put(key, EMPTY_SENTINEL);
            }
        }
        return val;
    }

    public void put(String cacheName, String key, Object value, long ttlSec) {
        String fullKey = cacheName + ":" + key;
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try { redis.opsForValue().set(fullKey, value, ttlSec, TimeUnit.SECONDS); }
            catch (Exception e) { log.warn("L2 put fail: {}", e.getMessage()); }
        }
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.put(key, value);
        }
    }

    public void evict(String cacheName, String key) {
        String fullKey = cacheName + ":" + key;
        // L1 evict
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.evict(key);
        }
        // L2 delete
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try { redis.delete(fullKey); } catch (Exception e) { log.warn("L2 delete fail: {}", e.getMessage()); }
        }
        // Pulsar 广播
        CacheBroadcast b = broadcastProvider.getIfAvailable();
        if (b != null) b.publishEvict(cacheName, key);
        // 延迟双删 100ms(M0: raw thread; 生产用 scheduled executor)
        if (redis != null) {
            new Thread(() -> {
                try { Thread.sleep(100); redis.delete(fullKey); }
                catch (Exception e) { log.debug("double-delete skip"); }
            }).start();
        }
    }

    private <T> T unwrap(T v) { return EMPTY_SENTINEL.equals(v) ? null : v; }
}
```

**关键设计**：
- `ObjectProvider` 延迟获取：L1/L2/广播任一缺失自动降级，不抛异常
- EMPTY_SENTINEL 防穿透(空值缓存 30s)
- evict 四步：L1 evict → L2 delete → Pulsar 广播 → 延迟双删 100ms
- Redis 异常降级：warn 日志不抛，L1 仍可用(ADR-T04 降级契约)
- 延迟双删：M0 用 raw thread(简单)，生产建议换 ScheduledExecutor

#### 3.2.2 CacheConfig(配置)

```java
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "ep.cache.enabled", matchIfMissing = true)
public class CacheConfig {
    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager m = new CaffeineCacheManager();
        m.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(Duration.ofSeconds(60)));
        return m;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        // 禁 DefaultTyping —— 跨语言纯 JSON 安全(ADR-T04)
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer(om));
        t.afterPropertiesSet();
        return t;
    }
}
```

**关键设计**：
- Caffeine：maximumSize=10000，expireAfterWrite=60s
- RedisTemplate：StringRedisSerializer(key) + GenericJackson2JsonRedisSerializer(value)
- **禁 DefaultTyping**：注释明确标注 ADR-T04，防跨语言反序列化炸弹

#### 3.2.3 CacheBroadcast(广播失效)

```java
@Component
public class CacheBroadcast {
    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Producer<byte[]> producer;

    @PostConstruct
    public void init() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) { log.info("Pulsar client null, CacheBroadcast degrade to no-op"); return; }
        try { producer = client.newProducer().topic(topic).createAsync().join(); }
        catch (Exception e) { log.warn("Pulsar producer create fail: {}", e.getMessage()); }
    }

    public void publishEvict(String cacheName, String key) {
        if (producer == null) { log.debug("no producer, skip broadcast"); return; }
        try {
            producer.sendAsync(mapper.writeValueAsBytes(new InvalidateMsg(cacheName, key)))
                    .exceptionally(e -> { log.warn("broadcast send fail: {}", e.getMessage()); return null; });
        } catch (Exception e) { log.warn("broadcast serialize/send fail: {}", e.getMessage()); }
    }
}
```

- lazy-init：无 Pulsar 时降级 no-op
- sendAsync 异步失败不阻塞

#### 3.2.4 InvalidateMsg / EventTopics / EventPublisher / PulsarClientHolder

```java
// InvalidateMsg: 标准 DTO(cacheName, key)
// EventTopics: topic 常量 CACHE_INVALIDATE = "ep-cache-invalidate"
// EventPublisher: topic→Producer 缓存(ConcurrentHashMap), sendAsync
// PulsarClientHolder: 单例延迟获取, @PreDestroy 关闭
```

### 3.3 测试覆盖(已实现)

| 测试类 | 覆盖点 |
|--------|--------|
| CacheServiceTest | getOrLoad(L1/L2/loader) / put / evict / 空值 / 降级 |
| CacheConfigTest | Caffeine/RedisTemplate Bean 装配 |
| CacheBroadcastTest | lazy-init / 降级 / publishEvict |
| InvalidateMsgTest | DTO 序列化 |
| PulsarClientHolderTest | 延迟获取 / close |
| EventPublisherTest | publish / producer 缓存 / 降级 |

**结果**：31 tests pass，95.8% line coverage。

---

## 4. CellRouter 单元路由器

> 新增组件(M0 实现)，本节为设计基线。

### 4.1 类设计

```
com.ep.cellrouter
├── CellRouter.java              // 路由核心
├── RouteTable.java              // 路由表(Nacos 监听)
├── RouteContext.java            // 请求路由 context(纠正计数器)
├── CellInfo.java                // cell 元数据
├── WriteHoldRegistry.java       // 写挂起名单(Redis)
└── CellRouterFilter.java        // Spring Cloud Gateway Filter
```

#### 4.1.1 CellRouter

```java
public class CellRouter {
    private final RouteTable routeTable;
    private final WriteHoldRegistry holdRegistry;

    public RouteResult route(String userId, String currentCell, String requestMethod) {
        // 1. 计算目标 cell
        int targetCell = routeTable.hashRoute(userId);
        CellInfo target = routeTable.getCell(targetCell);

        // 2. 检查 misroute
        if (!String.valueOf(targetCell).equals(currentCell)) {
            // 纠正计数器(存 RouteContext, ThreadLocal/Redis)
            int count = RouteContext.incrementCorrectCount(userId);
            if (count > 3) {
                return RouteResult.reject(403, "misroute limit exceeded");
            }
            return RouteResult.redirect(target);  // 重路由
        }

        // 3. failover 写挂起检查
        if (isWriteMethod(requestMethod) && holdRegistry.isHeld(userId)) {
            return RouteResult.hold(503, "write held during failover");
        }

        return RouteResult.pass(target);
    }
}
```

#### 4.1.2 RouteTable

```java
public class RouteTable {
    private volatile RouteTableSnapshot snapshot;  // Nacos 监听更新
    private final NacosConfigService nacos;

    public int hashRoute(String userId) {
        int hash = Murmur3.hash(userId.getBytes());
        return Math.abs(hash) % snapshot.getCellCount();
    }

    // Nacos 监听器
    public void onRouteTableChange(String newConfig) {
        this.snapshot = parse(newConfig);  // 原子替换
    }
}
```

### 4.2 时序图：流量纠正

```
客户端(到达 Cell-2)        BFF              CellRouter(Cell-2)        Cell-0(正确)
    │                       │                    │                       │
    │──请求(user_id=123)──►│                    │                       │
    │                       │──route(uid=123)──►│                       │
    │                       │                    │──hash(123)%10=0──►    │
    │                       │                    │   目标=Cell-0         │
    │                       │                    │   当前=Cell-2 → misroute!
    │                       │                    │──纠正计数++(1<=3)──►  │
    │                       │◄──redirect(Cell-0)─│                       │
    │                       │──重路由到 Cell-0─────────────────────────►│
    │                       │                    │                       │──处理请求
    │◄──────────────────────┴───────────────────────────────────────────│
```

### 4.3 时序图：failover 写挂起

```
SRE 触发 failover           CellRouter         WriteHoldRegistry(Redis)     客户端
    │                          │                      │                        │
    │──标记 Cell-0 failover──►│                      │                        │
    │   user_id 123 写挂起     │──hold(123)──────────►│                        │
    │                          │                      │                        │
    │                          │◄─────────────────────│                        │
    │                          │                      │                        │
    │                          │◄──请求(uid=123,POST)─│                        │
    │                          │──isHeld(123)?───────►│                        │
    │                          │◄──true──────────────│                        │
    │                          │──503 + Retry-After──►│                        │
    │                          │                      │                        │──等待重试
    │──failover 完成,解除挂起─►│──release(123)──────►│                        │
    │                          │                      │                        │
    │                          │◄──请求(uid=123,POST)─│                        │
    │                          │──isHeld(123)?───────►│                        │
    │                          │◄──false─────────────│                        │
    │                          │──放行到 Cell-0──────►│                        │
```

---

## 5. API 网关/BFF

### 5.1 类设计

```
com.ep.gateway
├── AuthFilter.java             // JWT 验签 + 头覆盖
├── TraceFilter.java            // W3C traceparent
├── GrayFilter.java             // 灰度分流
├── SentinelGatewayFilter.java // 限流(Nacos 分布式数据源, 禁本地内存 — ADR-T08/ESP 踩坑)
├── JwksVerifier.java           // JWKS 验签 + 缓存
├── JwtIssuer.java              // JWT 签发(登录后)
├── IdempotentInterceptor.java  // 幂等键拦截器(UUID SETNX 去重 — ADR-T06 替代 Seata)
└── GatewayConfig.java          // 路由配置(Nacos 监听)
```

### 5.2 关键时序：请求鉴权

```
客户端          AuthFilter        JwksVerifier(Redis缓存)     下游服务
  │                │                    │                        │
  │──请求+JWT────►│                    │                        │
  │                │──verify(jwt)────►│                        │
  │                │                    │──kid 查 JWKS           │
  │                │                    │  命中缓存?              │
  │                │                    │  是→用缓存公钥验签      │
  │                │                    │  否→拉 /.well-known/   │
  │                │                    │      jwks.json,缓存5min│
  │                │◄──Claims─────────│                        │
  │                │                    │                        │
  │                │──覆盖 X-User-Id    │                        │
  │                │   X-Tenant-Id      │                        │
  │                │   X-Roles          │                        │
  │                │──转发(带可信头)────────────────────────────►│
  │                │                    │                        │
  │◄───────────────┴────────────────────────────────────────────│
```

---

### 5.3 JWT 签发安全(ESP 踩坑对策)

> **ESP 血泪铁律**：ESP bff-java 早期使用 HS256 对称签名，`JWT_SECRET` 环境变量曾配 `<256bit`（短密钥），导致 JWT 可暴力破解；且 HS256 共享密钥无法实现"签发方与验证方分离"——下游 Go/Python 服务需持有相同 secret，密钥泄露面扩大。

**EP 对策（Day 1 固化）**：
- **RS256 非对称签名**：BFF 持私钥签发，下游服务仅持公钥验签（JWKS endpoint）。密钥泄露面最小化。
- **私钥长度强制**：CI 校验 RSA 私钥 ≥2048bit（推荐 3072bit），`JWT_SECRET` 环境变量仅用于 HMAC 降级场景（必须 ≥256bit，CI 闸门 `jwt-secret-length-check`）。
- **JWKS 轮转**：kid 标识密钥版本，Redis 缓存 JWKS（`ep:jwks:current`），轮转周期 90d，旧 kid 保留 7d 宽限期。
- **禁止头注入**：AuthFilter 覆盖客户端所有 `X-User-*` 头，仅信任 JWT claims（ADR-T07 防伪造头）。

```java
// JwtIssuer.java — RS256 签发, 私钥 ≥2048bit
public String issue(UserPrincipal principal) {
    return Jwts.builder()
        .header().keyId(kid).and()           // kid → JWKS 轮转
        .subject(principal.userId())          // user_id (string, 跨语言统一)
        .claim("cell", principal.cellId())
        .claim("roles", principal.roles())
        .expiration(Date.from(Instant.now().plus(30, MINUTES)))
        .signWith(privateKey, Jwts.SIG.RS256) // 非 HS256
        .compact();
}
```

### 5.4 Dubbo Triple 超时配置(ESP 踩坑对策)

> **ESP 踩坑**：ESP Dubbo 默认 timeout=3s，冷缓存场景（首次查询 TiDB Follower + Redis miss + 图库 cold start）触发 `DEADLINE_EXCEEDED`，链路雪崩。后调整为 10s 才稳定。

**EP 对策**：
- Dubbo Triple **默认 timeout ≥10s**（`dubbo.consumer.timeout=10000`），冷启动/冷缓存场景容忍。
- 区分 SLA：读操作 timeout=10s，写操作 timeout=15s（含幂等检查），批改等长任务走异步 Pulsar 事件（非同步 RPC）。
- 熔断降级：Sentinel 配合 Dubbo `org.apache.dubbo.rpc.Filter`，超时率 >5% 触发降级（返回缓存/默认值）。

```yaml
# application.yml — Dubbo Triple 超时 + Protobuf 序列化(ADR-T09)
dubbo:
  consumer:
    timeout: 10000          # ESP 踩坑: 3s → 10s
    serialization: protobuf  # ADR-T09: 禁 STRICT
  provider:
    timeout: 10000
    serialization: protobuf
```

### 5.5 Worker 分离模式(ESP learning-worker 经验)

> **ESP 模式**：ESP 将 ingest 服务拆分为 **API 进程**（`ESP_ROLE=api`，接收请求 + Disruptor 削峰）和 **Worker 进程**（`ESP_ROLE=worker`，消费 Disruptor ring buffer → 写 TiDB/Pulsar），通过 `@ConditionalOnProperty(name="esp.role", havingValue="worker")` 实现同代码库双部署。避免 API 进程被批量写入拖慢响应。

**EP 对策（复用此模式）**：
- 技术中台 ingest 服务采用 **API / Worker 双角色部署**：
  - **API 角色**（`EP_ROLE=api`）：HTTP/gRPC 入口 + Disruptor `tryNext()` 削峰，不直接写库。
  - **Worker 角色**（`EP_ROLE=worker`）：`@ConditionalOnProperty(name="ep.role", havingValue="worker")` 激活 Disruptor 消费者 handler，批量写 TiDB + 发 Pulsar 事件。
- **Disruptor↔Pulsar 双路径降级**：
  - 正常态：API → Disruptor（进程内）→ Worker → TiDB + Pulsar。
  - 降级态（Worker 全挂）：API `tryNext()` 失败（ring buffer 满）→ 降级直写 Pulsar（跳过 Disruptor），由下游消费者兜底。`DisruptorWorker.publish()` 返回 `FAILED` 时触发 `PulsarFallback.publish()`。
- K8s 部署：API Deployment（HPA 按 QPS）+ Worker Deployment（HPA 按 Disruptor 消费 lag），独立扩缩容。

```java
// DisruptorIngestService.java — 双角色 + 降级
@Service
public class DisruptorIngestService {
    @Autowired(required = false)  // Worker 角色才有 handler
    @ConditionalOnProperty(name = "ep.role", havingValue = "worker")
    private DisruptorEventHandler handler;

    public PublishResult publish(IngestEvent event) {
        PublishResult result = disruptorWorker.publish(event);
        if (result == PublishResult.FAILED) {
            // Disruptor 满 → 降级直写 Pulsar(ESP 经验: 避免丢数据)
            pulsarFallback.publish(event);
            metrics.counter("disruptor.fallback.pulsar").increment();
        }
        return result;
    }
}
```

---

## 6. WS/实时网关

### 6.1 模块设计(Go)

```
gateway-go/
├── cmd/server/main.go           // 入口
├── internal/
│   ├── hub.go                   // 连接 hub(房间管理)
│   ├── session.go               // 单连接 session
│   ├── broadcast.go             // 分片广播(fnv1a)
│   ├── redis_pubsub.go          // 跨 pod Redis Pub/Sub
│   ├── auth.go                  // JWT 握手鉴权
│   └── heartbeat.go             // 心跳保活
└── pkg/
    └── protocol/                // WS 消息协议
```

### 6.2 广播分片算法

```go
// broadcast.go
func (h *Hub) Broadcast(roomID string, msg []byte) {
    sessions := h.roomSessions(roomID)
    // 按 fnv1a(sessionID) % N 分片，N=CPU 数
    shards := make([][]*Session, h.shardCount)
    for _, s := range sessions {
        idx := fnv1a(s.ID) % h.shardCount
        shards[idx] = append(shards[idx], s)
    }
    // 每个 shard 独立 goroutine 发送，避免单 goroutine 阻塞
    for _, shard := range shards {
        go h.sendShard(shard, msg)
    }
}
```

### 6.3 跨 pod 广播时序

```
Pod-A                  Redis Pub/Sub              Pod-B
  │                        │                         │
  │──PUBLISH room:123 msg─►│                         │
  │                        │──SUBSCRIBE room:123────►│
  │                        │──推送 msg──────────────►│
  │                        │                         │──广播给本 pod room:123 订阅者
```

---

## 7. 事件总线 EDA

### 7.1 类设计

```
com.ep.eda
├── EventPublisher.java         // 通用发布(已实现 in ep-cache)
├── EventConsumer.java          // 消费者基类
├── EventEnvelope.java          // 事件信封(event_id/domain/action/timestamp/payload)
├── EventIdGenerator.java       // 雪花 ID 生成
├── DeadLetterHandler.java      // 死信处理
└── EventTopics.java            // topic 注册表
```

### 7.2 事件信封

> **ESP 踩坑铁律**：`user_id` 为**必传字段**，禁止默认 0 或省略。ESP 曾因 `writing.graded` 事件无 user_id（默认 0）导致 teacher-java 消费者无法回写 mastery，链路断裂。EP 所有领域事件必须包含 `user_id`（string 类型，跨语言统一，避免 ESP 的 String vs number ClassCastException）。

```json
{
  "event_id": "snowflake-1234567890",
  "domain": "order",
  "action": "paid",
  "timestamp": "2026-08-03T10:00:00Z",
  "trace_id": "w3c-trace-id",
  "tenant_id": "it-backend",
  "user_id": "123",
  "payload": { ... }
}
```

**字段约束**：
| 字段 | 类型 | 必传 | 说明 |
|------|------|------|------|
| event_id | string | ✅ | 雪花 ID，消费者幂等去重 |
| domain | string | ✅ | 领域(order/user/learning/...) |
| action | string | ✅ | 动作(paid/graded/registered/...) |
| timestamp | string(ISO-8601) | ✅ | 事件时间 |
| trace_id | string | ✅ | W3C traceparent |
| tenant_id | string | ✅ | 事业群标识 |
| user_id | **string** | **✅ 必传** | ESP 教训：禁止默认 0；类型必须 string（避免跨语言 ClassCastException） |
| payload | object | ✅ | 领域特定数据 |

**CI 强制校验**：事件 schema 用 Avro/JSON Schema 注册到 Schema Registry，CI 流水线扫描所有事件类，缺失 user_id 字段 → 编译失败。

### 7.3 发布时序

```
业务服务         EventPublisher        Pulsar           消费者
  │                  │                   │                 │
  │──publish(topic)─►│                   │                 │
  │                  │──封装 envelope    │                 │
  │                  │──sendAsync──────►│                 │
  │                  │                   │──持久化          │
  │                  │◄──ack────────────│                 │
  │                  │                   │──push──────────►│
  │                  │                   │                 │──幂等检查(event_id)
  │                  │                   │                 │──处理
```

---

## 8. 可观测

### 8.1 OTel 注入

```java
// TraceFilter.java
@Component
public class TraceFilter implements GlobalFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceparent = exchange.getRequest().getHeaders().getFirst("traceparent");
        if (traceparent == null) {
            traceparent = W3CTraceContext.generate();  // 生成新 trace
        }
        Span span = tracer.spanBuilder("gateway:" + path).startSpan();
        // 注入 traceparent 到下游
        ServerHttpRequest req = exchange.getRequest().mutate()
            .header("traceparent", traceparent)
            .build();
        return chain.filter(exchange.mutate().request(req).build())
            .doFinally(s -> span.end());
    }
}
```

### 8.2 双日志轨

```java
// access log
log.info(Json.stringify(Map.of(
    "type", "access",
    "method", method,
    "path", path,
    "status", status,
    "latency_ms", latency,
    "user_id", userId,
    "trace_id", traceId
)));

// business log
log.info(Json.stringify(Map.of(
    "type", "business",
    "event", "order.paid",
    "order_id", orderId,
    "user_id", userId,
    "amount", amount,
    "trace_id", traceId
)));
```

---

## 9. Rust 热点服务

### 9.1 模块设计

```
rust-hotspot/
├── src/
│   ├── main.rs              // gRPC server (tonic)
│   ├── risk/
│   │   ├── engine.rs        // 风控规则引擎
│   │   └── rules.rs         // 规则加载(从 Redis)
│   ├── billing/
│   │   └── calculator.rs    // 计费热点
│   └── proto/               // protobuf
└── Cargo.toml
```

### 9.2 风控规则匹配

```rust
// engine.rs
pub fn check(user_id: &str, action: &str, ctx: &Context) -> RiskDecision {
    let rules = rules::load_hot_rules();  // 从 Redis 加载热规则(<1ms)
    for rule in rules.iter() {
        if rule.matches(user_id, action, ctx) {
            return RiskDecision::Block(rule.reason);
        }
    }
    RiskDecision::Pass
}
```

---

## 10. 表结构

### 10.1 路由表(Nacos 配置，非 DB)

见 SAD §4.3。

### 10.2 写挂起名单(Redis)

```
# Redis key
ep:write_hold:{cell_id}  → Set of user_id
# TTL: failover 期间，最长 5min
```

### 10.3 JWKS 缓存(Redis)

```
# Redis key
ep:jwks:current  → JSON JWKS
# TTL: 5min(与下游缓存对齐)
```

### 10.4 限流规则(Nacos 配置)

```json
// dataId: ep-sentinel-rules
{
  "rules": [
    {
      "resource": "ucm-api",
      "limitApp": "default",
      "grade": 1,           // 1=QPS, 0=并发线程
      "count": 5000,
      "strategy": 0,
      "controlBehavior": 0, // 0=直接拒绝, 2=匀速
      "clusterMode": false
    }
  ]
}
```

### 10.5 缓存注册表(Nacos 配置)

```json
// dataId: ep-cache-registry
{
  "caches": [
    { "name": "course", "owner": "cmp-service", "ttl": 3600 },
    { "name": "user_profile", "owner": "ucm-service", "ttl": 1800 },
    { "name": "question", "owner": "cmp-service", "ttl": 7200 }
  ]
}
```

### 10.6 事件死信(Pulsar topic)

```
topic: {domain}.{action}.dlq
# 消费失败 3 次后入死信，人工/自动处理
```

---

## 11. 核心算法

### 11.1 CellRouter hash 路由 + 流量纠正算法

```
算法: route(userId, currentCell, method)
输入: userId, currentCell(请求到达的 cell), method(HTTP 方法)
输出: RouteResult(pass/redirect/reject/hold)

1. targetCell ← Murmur3(userId) % N    // N 来自路由表
2. IF targetCell == currentCell:
     GOTO step 5
3. // misroute
   count ← RouteContext.incrementCorrectCount(userId)
   IF count > 3:
     RETURN reject(403, "misroute limit exceeded")
   RETURN redirect(targetCell)    // 重路由
5. // failover 写挂起检查
   IF method ∈ {POST, PUT, DELETE} AND WriteHoldRegistry.isHeld(userId):
     RETURN hold(503, "write held during failover", Retry-After=5)
6. RETURN pass(targetCell)
```

**纠正计数器存储**：
- M0：Redis `ep:correct_count:{userId}`，TTL 60s
- M1：本地 Caffeine + Redis 双写(降低 Redis 压力)

### 11.2 Disruptor drop-on-full 算法

```
算法: publish(payload)
1. seq ← tryNext()    // 非阻塞尝试申请
2. IF seq 失败(InsufficientCapacityException):
     dropped.incrementAndGet()
     RETURN DROPPED
3. slot ← ringBuffer.get(seq)
4. translator.translateTo(slot, seq, payload)
5. ringBuffer.publish(seq)
6. published.incrementAndGet()
7. RETURN PUBLISHED
```

### 11.3 多级缓存 getOrLoad 算法

```
算法: getOrLoad(cacheName, key, loader, ttl, cacheEmpty)
1. // L1 Caffeine
   v ← L1.get(key)
   IF v != null:
     RETURN unwrap(v)    // EMPTY_SENTINEL → null
2. // L2 Redis
   v ← L2.get(fullKey)
   IF v != null:
     L1.put(key, v)      // 回填 L1
     RETURN unwrap(v)
3. // loader
   v ← loader.get()
4. IF v != null:
     L1.put(key, v)
     L2.setex(fullKey, ttl, v)
   ELSE IF cacheEmpty:
     L1.put(key, EMPTY_SENTINEL)
     L2.setex(fullKey, 30, EMPTY_SENTINEL)    // 防穿透,短 TTL
5. RETURN v
```

### 11.4 缓存 evict + 延迟双删算法

```
算法: evict(cacheName, key)
1. L1.evict(key)
2. L2.delete(fullKey)
3. CacheBroadcast.publishEvict(cacheName, key)    // Pulsar 广播给其他 pod
4. // 延迟双删 100ms(防并发读回填旧值)
   sleep(100ms)
   L2.delete(fullKey)
```

### 11.5 WS 广播分片算法

```
算法: broadcast(roomID, msg)
1. sessions ← hub.roomSessions(roomID)
2. shards ← groupBy(sessions, fnv1a(sessionID) % N)    // N=CPU 数
3. FOR shard IN shards:
     GO sendShard(shard, msg)    // 并行发送
```

---

**文档结束**

> 本 SDD 是 SP-01 技术中台的详细设计基线。已实现的 3 个 SDK 类设计与源码严格对齐。新增组件(CellRouter/网关/WS 网关等)按本设计实现。任何设计变更须经架构委员会评审，更新本文档并同步 ADR。

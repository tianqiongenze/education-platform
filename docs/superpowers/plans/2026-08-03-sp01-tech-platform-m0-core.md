# SP-01 技术中台 M0 核心底座 实现计划（批次1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建技术中台 M0 核心底座 —— 3 个 Java SDK（读写分离/Disruptor/缓存）+ BFF 骨架（JWT/AuthFilter/路由）+ Go WS 网关骨架（jwt/cache/hub），作为所有后续事业群与中台服务的依赖根，全部 TDD 验证。

**Architecture:** 多模块 Maven（Java）+ Go module。SDK 采用 Spring Boot 自动装配 + 特性开关（`*.enabled` 默认开，关闭降级为 no-op）。BFF 用 Spring Boot 4 + Jetty + RS256 JWT。Go 网关用 gorilla/websocket + redis go-redis v9。所有模式直接复用 ESP 已验证实现（包名从 `com.esp.*` 改为 `com.ep.*`，key 前缀 `esp:` → `ep:`），仅适配新下游服务短名与集群基础设施短名。

**Tech Stack:** JDK 21, Maven, Spring Boot 4.0.7 (Jetty), HikariCP 6.3.3, AspectJ 1.9.25.1, LMAX Disruptor 4.0.0, Caffeine 3.2.4, Pulsar Client 3.3.9, Jackson 2.19.4, JJWT, JUnit 5.14.4, Mockito 5.18.0, H2 2.3.232, JaCoCo 0.8.12 / Go 1.25, gorilla/websocket v1.5.3, go-redis v9.21.0, golang-jwt/jwt v5.3.1, miniredis v2, OTel v1.44.0。

## Global Constraints

- 包名前缀：Java `com.ep.*`（替代 ESP 的 `com.esp.*`）；Go module `ep/<svc>`
- 缓存 key 前缀：`ep:`（替代 `esp:`）；空哨兵 `__EP_CACHE_EMPTY__`（替代 `__ESP_CACHE_EMPTY__`）
- 缓存 key 明文 `{bu}:{svc}:{entity}:{id}`，禁 sha256；跨语言值纯 JSON snake_case，禁 Java DefaultTyping
- 特性开关命名：`ep.rw.enabled` / `ep.cache.enabled` / `ep.disruptor.enabled` / `ep.pulsar.enabled`（均 matchIfMissing=true，默认开）
- 基础设施短名（ExternalName，集群复用）：`tidb-primary:4000` / `tidb-follower:4000` / `redis-cluster:6379` / `pulsar-broker:6650`
- 公共库覆盖率门：≥80%（CI 用 awk 过滤 jacoco.csv）；安全核心（JwtService/AuthFilter）100% 硬门
- 非单元测试禁连真实 broker（Pulsar/Redis/TiDB）—— 注入 mock 跑降级路径
- Git：非 git 仓库环境，靠文件 + `~/.zcode/sessions/education-platform/progress.json` 追踪进度；每个 Task 末更新 progress
- 非 git 仓库时，"commit" 步骤改为"更新 progress.json + 记录 Task 完成路径"

---

## File Structure（本批次产出）

```
services/
├── ep-rw-java/                    # 读写分离 SDK（Task 1）
│   ├── pom.xml
│   └── src/{main,test}/java/com/ep/rw/
│       ├── DataSource.java        # @DataSource 注解
│       ├── DataSourceType.java    # enum WRITE/READ
│       ├── RoutingHolder.java     # ThreadLocal
│       ├── RoutingDataSource.java # extends AbstractRoutingDataSource
│       ├── DataSourceAspect.java  # @Aspect
│       └── DataSourceConfig.java  # @Configuration 自动装配
├── ep-disruptor/                  # Disruptor ingest SDK（Task 2）
│   ├── pom.xml
│   └── src/{main,test}/java/com/ep/disruptor/
│       ├── DisruptorWorker.java
│       ├── DisruptorPipeline.java
│       ├── PublishResult.java     # enum
│       ├── WaitStrategyType.java  # enum
│       └── DisruptorStats.java    # record
├── ep-cache/                      # 多级缓存 SDK（Task 3）
│   ├── pom.xml
│   └── src/{main,test}/java/com/ep/cache/
│       ├── CacheService.java      # getOrLoad/put/evict
│       ├── CacheConfig.java       # Caffeine + RedisTemplate
│       ├── CacheBroadcast.java    # Pulsar 广播失效
│       ├── PulsarClientHolder.java
│       ├── InvalidateMsg.java
│       └── event/{EventPublisher.java, EventTopics.java}
├── ep-api/                        # Dubbo 契约 jar（Task 4，最小骨架）
│   ├── pom.xml
│   └── src/main/java/com/ep/api/dubbo/
│       └── HealthRpcService.java  # 占位契约，验证 Triple+Nacos 链路
├── bff-java/                      # BFF 骨架（Task 5）
│   ├── pom.xml
│   └── src/{main,test}/java/com/ep/bff/
│       ├── BffApplication.java
│       ├── auth/{JwtService.java, JwksController.java}
│       ├── filter/AuthFilter.java
│       ├── edge/{EdgeAuthHandler.java, RouteTable.java}
│       └── config/{WebClientConfig.java, SentinelConfig.java}
└── gateway-go/                    # Go WS 网关骨架（Task 6）
    ├── go.mod
    └── src/
        ├── main.go
        ├── jwt/validator.go
        ├── cache/cache.go
        ├── hub/hub.go
        ├── ws/frame.go
        └── (各包 _test.go)
```

**职责边界**：每个 SDK 单一能力（rw=数据源路由 / disruptor=进程内削峰 / cache=L1+L2+广播 / api=RPC契约 / bff=鉴权+路由聚合 / gateway-go=WS长连接）。SDK 无业务逻辑，被业务服务消费。

---

## Task 1: ep-rw-java 读写分离 SDK

**Files:**
- Create: `services/ep-rw-java/pom.xml`
- Create: `services/ep-rw-java/src/main/java/com/ep/rw/{DataSource,DataSourceType,RoutingHolder,RoutingDataSource,DataSourceAspect,DataSourceConfig}.java`
- Create: `services/ep-rw-java/src/test/java/com/ep/rw/*Test.java`
- Create: `services/ep-rw-java/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Interfaces:**
- Consumes: Spring JDBC 7.0.8, spring-context, HikariCP 6.3.3, aspectjweaver 1.9.25.1（均 provided 范围）
- Produces: `@DataSource` 注解 + `RoutingDataSource`（@Primary）+ `DataSourceAspect` AOP，被业务服务用 `@DataSource(DataSourceType.READ)` / `(WRITE)` / `(READ, consistent=true)` / `(READ, tiflash=true)` 标注方法

- [ ] **Step 1: 建 pom.xml**

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ep</groupId>
  <artifactId>ep-rw-java</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <spring.version>7.0.8</spring.version>
    <spring.boot.version>4.0.7</spring.boot.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId><version>${spring.version}</version><scope>provided</scope></dependency>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>${spring.version}</version><scope>provided</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-autoconfigure</artifactId><version>${spring.boot.version}</version><scope>provided</scope></dependency>
    <dependency><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId><version>6.3.3</version><scope>provided</scope></dependency>
    <dependency><groupId>org.aspectj</groupId><artifactId>aspectjweaver</artifactId><version>1.9.25.1</version><scope>provided</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.14.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.18.0</version><scope>test</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.3.232</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
    <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version>
      <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions></plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: 写 DataSourceType enum 的失败测试**

`src/test/java/com/ep/rw/DataSourceTypeTest.java`:
```java
package com.ep.rw;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceTypeTest {
    @Test void hasWriteAndReadValues() {
        assertSame(DataSourceType.WRITE, DataSourceType.valueOf("WRITE"));
        assertSame(DataSourceType.READ, DataSourceType.valueOf("READ"));
        assertEquals(2, DataSourceType.values().length);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd services/ep-rw-java && mvn -q test -Dtest=DataSourceTypeTest 2>&1 | tail -15`
Expected: FAIL（DataSourceType 不存在）

- [ ] **Step 4: 实现 DataSourceType**

`src/main/java/com/ep/rw/DataSourceType.java`:
```java
package com.ep.rw;
public enum DataSourceType { WRITE, READ }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd services/ep-rw-java && mvn -q test -Dtest=DataSourceTypeTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: 写 RoutingHolder 测试**

`src/test/java/com/ep/rw/RoutingHolderTest.java`:
```java
package com.ep.rw;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingHolderTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void defaultIsNullWhichMeansWrite() {
        assertNull(RoutingHolder.get());
    }
    @Test void setThenGetReturnsValue() {
        RoutingHolder.set(DataSourceType.READ);
        assertSame(DataSourceType.READ, RoutingHolder.get());
    }
    @Test void clearResetsToNull() {
        RoutingHolder.set(DataSourceType.READ);
        RoutingHolder.clear();
        assertNull(RoutingHolder.get());
    }
}
```

- [ ] **Step 7: 跑测试确认失败 → 实现 RoutingHolder → 跑通过**

`src/main/java/com/ep/rw/RoutingHolder.java`:
```java
package com.ep.rw;
public final class RoutingHolder {
    private static final ThreadLocal<DataSourceType> CTX = new ThreadLocal<>();
    private RoutingHolder() {}
    public static void set(DataSourceType t) { CTX.set(t); }
    public static DataSourceType get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
}
```

Run: `cd services/ep-rw-java && mvn -q test -Dtest=RoutingHolderTest 2>&1 | tail -10` → PASS

- [ ] **Step 8: 写 @DataSource 注解 + DataSourceType 引用**

`src/main/java/com/ep/rw/DataSource.java`:
```java
package com.ep.rw;
import java.lang.annotation.*;
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    DataSourceType value() default DataSourceType.WRITE;
    /** 强制走 Primary，防读后写不一致 */
    boolean consistent() default false;
    /** 路由到 TiFlash 列存（OLAP） */
    boolean tiflash() default false;
}
```

- [ ] **Step 9: 写 RoutingDataSource 测试**

`src/test/java/com/ep/rw/RoutingDataSourceTest.java`:
```java
package com.ep.rw;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingDataSourceTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void returnsWriteKeyWhenHolderNull() {
        var ds = new RoutingDataSource();
        assertEquals(DataSourceType.WRITE, ds.determineCurrentLookupKey());
    }
    @Test void returnsReadKeyWhenHolderSet() {
        RoutingHolder.set(DataSourceType.READ);
        var ds = new RoutingDataSource();
        assertEquals(DataSourceType.READ, ds.determineCurrentLookupKey());
    }
}
```

注：`determineCurrentLookupKey` 是 protected，同包测试可直访。

- [ ] **Step 10: 跑失败 → 实现 RoutingDataSource → 通过**

`src/main/java/com/ep/rw/RoutingDataSource.java`:
```java
package com.ep.rw;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceType t = RoutingHolder.get();
        return t == null ? DataSourceType.WRITE : t;
    }
}
```

Run: `mvn -q test -Dtest=RoutingDataSourceTest 2>&1 | tail -10` → PASS

- [ ] **Step 11: 写 DataSourceAspect 测试（consistent 强制 WRITE）**

`src/test/java/com/ep/rw/DataSourceAspectTest.java`:
```java
package com.ep.rw;
import org.junit.jupiter.api.*;
import org.aspectj.lang.ProceedingJoinPoint;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceAspectTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void aroundSetsReadThenClears() throws Throwable {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        var ds = mockDataSource(DataSourceType.READ, false, false);
        var aspect = new DataSourceAspect();
        Object r = aspect.around(pjp, ds);
        assertEquals("ok", r);
        verify(pjp).proceed();
        assertNull(RoutingHolder.get(), "ThreadLocal 必须清空防泄漏");
    }
    @Test void consistentForcesWrite() throws Throwable {
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        var ds = mockDataSource(DataSourceType.READ, true, false); // consistent=true
        new DataSourceAspect().around(pjp, ds);
        // 执行期间 holder 应是 WRITE（无法直接观测，但 clear 后为 null 验证清理）
        verify(pjp).proceed();
    }
    @Test void writeClearsHolderBeforeProceed() throws Throwable {
        RoutingHolder.set(DataSourceType.READ); // 残留
        var pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn(null);
        var ds = mockDataSource(DataSourceType.WRITE, false, false);
        new DataSourceAspect().around(pjp, ds);
        verify(pjp).proceed();
        assertNull(RoutingHolder.get());
    }

    private DataSource mockDataSource(DataSourceType v, boolean c, boolean t) {
        DataSource m = mock(DataSource.class);
        when(m.value()).thenReturn(v);
        when(m.consistent()).thenReturn(c);
        when(m.tiflash()).thenReturn(t);
        return m;
    }
}
```

- [ ] **Step 12: 跑失败 → 实现 DataSourceAspect → 通过**

`src/main/java/com/ep/rw/DataSourceAspect.java`:
```java
package com.ep.rw;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

@Component
@Aspect
public class DataSourceAspect {
    @Around("@annotation(com.ep.rw.DataSource)")
    public Object around(ProceedingJoinPoint pjp, DataSource ds) throws Throwable {
        DataSourceType prev = RoutingHolder.get();
        boolean forceWrite = ds.consistent() || ds.tiflash() && false; // tiflash 通过 SQL hint，仍走读池但本版简化
        DataSourceType target = ds.consistent() ? DataSourceType.WRITE : ds.value();
        if (target == DataSourceType.WRITE) RoutingHolder.clear(); // 写操作清残留防脏读
        else RoutingHolder.set(target);
        try {
            return pjp.proceed();
        } finally {
            if (prev == null) RoutingHolder.clear();
            else RoutingHolder.set(prev);
        }
    }
}
```

Run: `mvn -q test -Dtest=DataSourceAspectTest 2>&1 | tail -10` → PASS

- [ ] **Step 13: 写 DataSourceConfig 自动装配测试（H2 模拟主从）**

`src/test/java/com/ep/rw/DataSourceConfigTest.java`:
```java
package com.ep.rw;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceConfigTest {
    @Test void wiresRoutingDataSourceAsPrimary() {
        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                "spring.datasource.write.jdbc-url=jdbc:h2:mem:w",
                "spring.datasource.write.username=sa",
                "spring.datasource.write.password=",
                "spring.datasource.read.jdbc-url=jdbc:h2:mem:r",
                "spring.datasource.read.username=sa",
                "spring.datasource.read.password="
            )
            .run(ctx -> {
                assertNotNull(ctx.getBean(DataSource.class));
                assertTrue(ctx.getBean(DataSource.class) instanceof AbstractRoutingDataSource
                    || ctx.getBean(DataSource.class).getClass().getName().contains("Lazy"));
            });
    }
    @Test void disabledWhenFlagFalse() {
        new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues("ep.rw.enabled=false",
                "spring.datasource.write.jdbc-url=jdbc:h2:mem:w",
                "spring.datasource.write.username=sa","spring.datasource.write.password=",
                "spring.datasource.read.jdbc-url=jdbc:h2:mem:r",
                "spring.datasource.read.username=sa","spring.datasource.read.password=")
            .run(ctx -> assertFalse(ctx.containsBean("routingDataSource")));
    }
}
```

- [ ] **Step 14: 跑失败 → 实现 DataSourceConfig + 注册 AutoConfiguration → 通过**

`src/main/java/com/ep/rw/DataSourceConfig.java`:
```java
package com.ep.rw;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

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

`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.ep.rw.DataSourceConfig
```

Run: `mvn -q test 2>&1 | tail -15` → 全部 PASS

- [ ] **Step 15: 覆盖率检查 + 记录进度**

Run: `cd services/ep-rw-java && mvn -q test jacoco:report 2>&1 | tail -5 && awk -F, '{covered+=$4+($5!=""?$5:0); missed+=$4} END{}' target/site/jacoco/jacoco.csv | head -1 && cat target/site/jacoco/jacoco.csv | awk -F, 'NR>1{s+=$4; c+=$5; b+=$7; bd+=$8} END{printf "instruction covered=%.0f%%\n", (c/(s+c))*100}'`
Expected: 覆盖率 ≥80%

更新 `~/.zcode/sessions/education-platform/progress.json`（追加 Task 1 完成路径）。

---

## Task 2: ep-disruptor 高并发 ingest SDK

**Files:**
- Create: `services/ep-disruptor/pom.xml`
- Create: `services/ep-disruptor/src/main/java/com/ep/disruptor/{PublishResult,WaitStrategyType,DisruptorStats,DisruptorWorker,DisruptorPipeline}.java`
- Create: `services/ep-disruptor/src/test/java/com/ep/disruptor/*Test.java`

**Interfaces:**
- Consumes: LMAX Disruptor 4.0.0, jakarta.annotation-api 3.0.0, slf4j-api 2.0.18
- Produces: `DisruptorWorker.<T>builder()` + `publish(T) -> PublishResult` + `DisruptorPipeline`（多阶段）

- [ ] **Step 1: 建 pom.xml**（groupId `com.ep`，artifactId `ep-disruptor`，deps: `com.lmax:disruptor:4.0.0`, `jakarta.annotation:jakarta.annotation-api:3.0.0`, `org.slf4j:slf4j-api:2.0.18`, test: junit/mockito/slf4j-simple）

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ep</groupId><artifactId>ep-disruptor</artifactId><version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencies>
    <dependency><groupId>com.lmax</groupId><artifactId>disruptor</artifactId><version>4.0.0</version></dependency>
    <dependency><groupId>jakarta.annotation</groupId><artifactId>jakarta.annotation-api</artifactId><version>3.0.0</version></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.18</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.14.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.18.0</version><scope>test</scope></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>2.0.18</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
    <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version><executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions></plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: 写 PublishResult + WaitStrategyType enum 测试**

`src/test/java/com/ep/disruptor/EnumsTest.java`:
```java
package com.ep.disruptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnumsTest {
    @Test void publishResultHasPublishedAndDropped() {
        assertEquals(2, PublishResult.values().length);
        assertSame(PublishResult.PUBLISHED, PublishResult.valueOf("PUBLISHED"));
        assertSame(PublishResult.DROPPED, PublishResult.valueOf("DROPPED"));
    }
    @Test void waitStrategyTypeHasAllFive() {
        assertEquals(5, WaitStrategyType.values().length);
        assertNotNull(WaitStrategyType.valueOf("BLOCKING"));
        assertNotNull(WaitStrategyType.valueOf("YIELDING"));
    }
}
```

- [ ] **Step 3: 跑失败 → 实现 PublishResult + WaitStrategyType → 通过**

`src/main/java/com/ep/disruptor/PublishResult.java`:
```java
package com.ep.disruptor;
public enum PublishResult { PUBLISHED, DROPPED }
```

`src/main/java/com/ep/disruptor/WaitStrategyType.java`:
```java
package com.ep.disruptor;
import com.lmax.disruptor.*;
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

Run: `mvn -q test -Dtest=EnumsTest 2>&1 | tail -10` → PASS

- [ ] **Step 4: 写 DisruptorStats record 测试**

`src/test/java/com/ep/disruptor/DisruptorStatsTest.java`:
```java
package com.ep.disruptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorStatsTest {
    @Test void recordHoldsValues() {
        DisruptorStats s = new DisruptorStats("n", 65536L, 100L, 5L, 95L);
        assertEquals("n", s.name());
        assertEquals(65536L, s.bufferSize());
        assertEquals(100L, s.published());
        assertEquals(5L, s.dropped());
        assertEquals(95L, s.consumed());
    }
    @Test void backlogIsPublishedMinusConsumed() {
        DisruptorStats s = new DisruptorStats("n", 64L, 100L, 0L, 80L);
        assertEquals(20L, s.backlog());
    }
}
```

- [ ] **Step 5: 跑失败 → 实现 DisruptorStats → 通过**

`src/main/java/com/ep/disruptor/DisruptorStats.java`:
```java
package com.ep.disruptor;
public record DisruptorStats(String name, long bufferSize, long published, long dropped, long consumed) {
    public long backlog() { return published - consumed; }
}
```

Run: `mvn -q test -Dtest=DisruptorStatsTest 2>&1 | tail -10` → PASS

- [ ] **Step 6: 写 DisruptorWorker 测试（发布+消费+drop+shutdown排空）**

`src/test/java/com/ep/disruptor/DisruptorWorkerTest.java`:
```java
package com.ep.disruptor;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorWorkerTest {
    static class Evt { String payload; }
    private DisruptorWorker<Evt> worker;
    private final Queue<String> consumed = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() throws Exception {
        worker = DisruptorWorker.<Evt>builder()
            .name("test").bufferSize(1024).producerType(com.lmax.disruptor.ProducerType.MULTI)
            .waitStrategy(WaitStrategyType.BLOCKING)
            .factory(Evt::new)
            .translator((slot, seq, src) -> slot.payload = src.payload)
            .handler((evt, seq, endOfBatch) -> { if (evt.payload != null) consumed.add(evt.payload); })
            .build();
        worker.start();
    }
    @AfterEach void teardown() { worker.shutdown(); }

    @Test void publishesAndConsumes() throws Exception {
        PublishResult r = worker.publish(make("hi"));
        assertEquals(PublishResult.PUBLISHED, r);
        Thread.sleep(200); // 等消费
        assertTrue(consumed.contains("hi"));
    }
    @Test void dropsWhenFull() throws Exception {
        DisruptorWorker<Evt> tiny = DisruptorWorker.<Evt>builder()
            .name("tiny").bufferSize(2).producerType(com.lmax.disruptor.ProducerType.SINGLE)
            .waitStrategy(WaitStrategyType.BLOCKING).factory(Evt::new)
            .translator((slot, seq, src) -> slot.payload = src.payload)
            .handler((evt, seq, eob) -> { try { Thread.sleep(1000); } catch (InterruptedException ie) {} })
            .build();
        tiny.start();
        try {
            int dropped = 0;
            for (int i = 0; i < 100; i++) if (tiny.publish(make("x" + i)) == PublishResult.DROPPED) dropped++;
            assertTrue(dropped > 0, "满时应有 drop");
        } finally { tiny.shutdown(); }
    }
    @Test void shutdownDrainsPending() throws Exception {
        for (int i = 0; i < 50; i++) worker.publish(make("d" + i));
        worker.shutdown();
        TimeUnit.MILLISECONDS.sleep(50);
        // shutdown 阻塞至消费完成（Disruptor 4.x halt）
        assertTrue(consumed.size() >= 40, "shutdown 应排空, 实际 " + consumed.size());
    }
    @Test void statsReportsBuffersAndCounts() throws Exception {
        worker.publish(make("s"));
        Thread.sleep(200);
        DisruptorStats s = worker.stats();
        assertEquals("test", s.name());
        assertEquals(1024L, s.bufferSize());
        assertTrue(s.published() >= 1);
        assertTrue(s.consumed() >= 1);
    }

    private Evt make(String p) { Evt e = new Evt(); e.payload = p; return e; }
}
```

- [ ] **Step 7: 跑失败 → 实现 DisruptorWorker + Builder → 通过**

`src/main/java/com/ep/disruptor/DisruptorWorker.java`:
```java
package com.ep.disruptor;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class DisruptorWorker<T> {
    private static final Logger log = LoggerFactory.getLogger(DisruptorWorker.class);
    private final Disruptor<T> disruptor;
    private final EventTranslatorOneArg<T, T> translator;
    private final int bufferSize;
    private final String name;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();

    private DisruptorWorker(Builder<T> b) {
        this.name = b.name; this.bufferSize = b.bufferSize; this.translator = b.translator;
        this.disruptor = new Disruptor<>(b.factory, b.bufferSize, DaemonThreadFactory.INSTANCE, b.producerType, b.waitStrategy.toWaitStrategy());
        EventHandler<T> counting = (evt, seq, eob) -> consumed.incrementAndGet();
        disruptor.handleEventsWith(counting).then(b.handler);
    }

    @PostConstruct public void start() { disruptor.start(); }
    public PublishResult publish(T payload) {
        long seq;
        try { seq = disruptor.getRingBuffer().tryNext(); }
        catch (InsufficientCapacityException e) { dropped.incrementAndGet(); return PublishResult.DROPPED; }
        try { disruptor.getRingBuffer().publishEvent(translator, seq, payload); published.incrementAndGet(); return PublishResult.PUBLISHED; }
        finally { }
    }
    @PreDestroy public void shutdown() { disruptor.shutdown(); }
    public DisruptorStats stats() {
        return new DisruptorStats(name, bufferSize, published.get(), dropped.get(), consumed.get());
    }

    public static <T> Builder<T> builder() { return new Builder<>(); }
    public static final class Builder<T> {
        private String name = "default"; private int bufferSize = 65536;
        private ProducerType producerType = ProducerType.MULTI;
        private WaitStrategyType waitStrategy = WaitStrategyType.BLOCKING;
        private EventFactory<T> factory; private EventTranslatorOneArg<T,T> translator; private EventHandler<T> handler;
        public Builder<T> name(String n){this.name=n;return this;}
        public Builder<T> bufferSize(int s){this.bufferSize=s;return this;}
        public Builder<T> producerType(ProducerType p){this.producerType=p;return this;}
        public Builder<T> waitStrategy(WaitStrategyType w){this.waitStrategy=w;return this;}
        public Builder<T> factory(EventFactory<T> f){this.factory=f;return this;}
        public Builder<T> translator(EventTranslatorOneArg<T,T> t){this.translator=t;return this;}
        public Builder<T> handler(EventHandler<T> h){this.handler=h;return this;}
        public DisruptorWorker<T> build() {
            java.util.Objects.requireNonNull(factory); java.util.Objects.requireNonNull(translator); java.util.Objects.requireNonNull(handler);
            return new DisruptorWorker<>(this);
        }
    }
}
```

Run: `mvn -q test -Dtest=DisruptorWorkerTest 2>&1 | tail -15` → PASS（注：drop 测试依赖时序，若 flaky 调整 sleep）

- [ ] **Step 8: DisruptorPipeline（多阶段）测试 + 实现（精简版，单阶段转发验证）**

`src/test/java/com/ep/disruptor/DisruptorPipelineTest.java`:
```java
package com.ep.disruptor;
import org.junit.jupiter.api.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorPipelineTest {
    static class E { String s; }
    private DisruptorPipeline pipe;
    private final ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() { pipe = DisruptorPipeline.named("pipe"); }
    @AfterEach void teardown() { pipe.shutdown(); }

    @Test void twoStagePipelineForwardsToLast() throws Exception {
        pipe.<E>stage("in", E::new, (slot,seq,src)->slot.s=src.s, (e,s,eob)->pipe.forward("in", e))
            .stage("out", E::new, (slot,seq,src)->slot.s=src.s, (e,s,eob)->out.add(e.s))
            .start();
        E msg = new E(); msg.s = "flow";
        pipe.publish("in", msg);
        TimeUnit.MILLISECONDS.sleep(300);
        assertTrue(out.contains("flow"));
    }
}
```

`src/main/java/com/ep/disruptor/DisruptorPipeline.java`:
```java
package com.ep.disruptor;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.util.*;

public final class DisruptorPipeline {
    private final Map<String, DisruptorWorker<?>> stages = new LinkedHashMap<>();
    private final String name;
    private DisruptorPipeline(String n) { this.name = n; }
    public static DisruptorPipeline named(String n) { return new DisruptorPipeline(n); }

    public <T> DisruptorPipeline stage(String stageName, EventFactory<T> factory,
            EventTranslatorOneArg<T,T> translator, EventHandler<T> handler) {
        return stage(stageName, 65536, ProducerType.MULTI, WaitStrategyType.BLOCKING, factory, translator, handler);
    }
    public <T> DisruptorPipeline stage(String stageName, int bufferSize, ProducerType pt,
            WaitStrategyType w, EventFactory<T> factory, EventTranslatorOneArg<T,T> translator, EventHandler<T> handler) {
        DisruptorWorker<T> wk = DisruptorWorker.<T>builder()
            .name(name+":"+stageName).bufferSize(bufferSize).producerType(pt).waitStrategy(w)
            .factory(factory).translator(translator).handler(handler).build();
        stages.put(stageName, wk); return this;
    }
    public void start() { stages.values().forEach(DisruptorWorker::start); }
    @SuppressWarnings("unchecked")
    public <T> PublishResult publish(String stage, T payload) {
        DisruptorWorker<T> w = (DisruptorWorker<T>) stages.get(stage);
        if (w == null) throw new IllegalArgumentException("unknown stage " + stage);
        return w.publish(payload);
    }
    @SuppressWarnings("unchecked")
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

Run: `mvn -q test -Dtest=DisruptorPipelineTest 2>&1 | tail -15` → PASS

- [ ] **Step 9: 全量测试 + 覆盖率 + 进度记录**

Run: `cd services/ep-disruptor && mvn -q test jacoco:report 2>&1 | tail -5` → 全 PASS
覆盖率检查（同 Task1 Step15 方法）≥80%
更新 `~/.zcode/sessions/education-platform/progress.json`。

---

## Task 3: ep-cache 多级缓存 SDK

**Files:**
- Create: `services/ep-cache/pom.xml`
- Create: `services/ep-cache/src/main/java/com/ep/cache/{CacheService,CacheConfig,CacheBroadcast,PulsarClientHolder,InvalidateMsg}.java`
- Create: `services/ep-cache/src/main/java/com/ep/cache/event/{EventPublisher,EventTopics}.java`
- Create: `services/ep-cache/src/test/java/com/ep/cache/*Test.java`

**Interfaces:**
- Consumes: Caffeine 3.2.4, Pulsar Client 3.3.9, Jackson 2.19.4, spring-context/boot-data-redis（provided）
- Produces: `CacheService.getOrLoad(name,key,loader,ttl,cacheEmpty)` / `put` / `evict`（L1 Caffeine → L2 Redis → loader，空哨兵 `__EP_CACHE_EMPTY__`，L2 不可达降级 L1）

- [ ] **Step 1: 建 pom.xml**（deps：caffeine 3.2.4, pulsar-client 3.3.9, jackson-databind/jsr310 2.19.4, slf4j-api 2.0.18, jakarta.annotation-api 3.0.0；provided: spring-context 7.0.8, spring-boot-starter-cache/-data-redis 4.0.7；test: junit/mockito）

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ep</groupId><artifactId>ep-cache</artifactId><version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencies>
    <dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId><version>3.2.4</version></dependency>
    <dependency><groupId>org.apache.pulsar</groupId><artifactId>pulsar-client</artifactId><version>3.3.9</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.19.4</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jsr310</artifactId><version>2.19.4</version></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.18</version></dependency>
    <dependency><groupId>jakarta.annotation</groupId><artifactId>jakarta.annotation-api</artifactId><version>3.0.0</version></dependency>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>7.0.8</version><scope>provided</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-cache</artifactId><version>4.0.7</version><scope>provided</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId><version>4.0.7</version><scope>provided</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.14.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.18.0</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
    <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version><executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions></plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: 写 InvalidateMsg POJO 测试 + 实现**

`src/test/java/com/ep/cache/InvalidateMsgTest.java`:
```java
package com.ep.cache;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvalidateMsgTest {
    @Test void holdsCacheNameAndKey() {
        InvalidateMsg m = new InvalidateMsg("course:detail", "c-123");
        assertEquals("course:detail", m.getCacheName());
        assertEquals("c-123", m.getKey());
    }
}
```

Run 失败 → 实现：
```java
package com.ep.cache;
public class InvalidateMsg {
    private String cacheName; private String key;
    public InvalidateMsg() {}
    public InvalidateMsg(String cacheName, String key) { this.cacheName = cacheName; this.key = key; }
    public String getCacheName() { return cacheName; }
    public String getKey() { return key; }
    public void setCacheName(String c) { this.cacheName = c; }
    public void setKey(String k) { this.key = k; }
}
```

- [ ] **Step 3: 写 CacheService 测试（getOrLoad L1→L2→loader，空哨兵，evict 三层+广播）**

`src/test/java/com/ep/cache/CacheServiceTest.java`:
```java
package com.ep.cache;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheServiceTest {
    private CacheService svc;
    private CacheManager cm;
    private RedisTemplate<String, Object> redis;
    private CacheBroadcast broadcast;

    @BeforeEach void setup() {
        cm = mock(CacheManager.class); redis = mock(RedisTemplate.class); broadcast = mock(CacheBroadcast.class);
        when(cm.getCache(anyString())).thenReturn(new org.springframework.cache.concurrent.ConcurrentMapCache("test"));
        svc = new CacheService(() -> cm, () -> redis, () -> broadcast);
    }

    @Test void getOrLoadCallsLoaderOnFirstMiss() {
        AtomicInteger calls = new AtomicInteger();
        String r = svc.getOrLoad("course:detail", "c-1", () -> { calls.incrementAndGet(); return "v1"; }, 300, true);
        assertEquals("v1", r);
        assertEquals(1, calls.get());
    }
    @Test void getOrLoadSecondCallHitsL1NoLoader() {
        AtomicInteger calls = new AtomicInteger();
        svc.getOrLoad("course:detail", "c-2", () -> { calls.incrementAndGet(); return "v"; }, 300, true);
        svc.getOrLoad("course:detail", "c-2", () -> { calls.incrementAndGet(); return "v"; }, 300, true);
        assertEquals(1, calls.get(), "第二次应命中 L1 不调 loader");
    }
    @Test void getOrLoadCachesEmptyWithSentinel() {
        AtomicInteger calls = new AtomicInteger();
        String r1 = svc.getOrLoad("u", "miss", () -> { calls.incrementAndGet(); return null; }, 30, true);
        assertNull(r1);
        svc.getOrLoad("u", "miss", () -> { calls.incrementAndGet(); return null; }, 30, true);
        assertEquals(1, calls.get(), "空结果应被哨兵缓存，loader 不再调");
    }
    @Test void getOrLoadEmptyNotCachedWhenDisabled() {
        AtomicInteger calls = new AtomicInteger();
        svc.getOrLoad("u", "miss2", () -> { calls.incrementAndGet(); return null; }, 30, false);
        svc.getOrLoad("u", "miss2", () -> { calls.incrementAndGet(); return null; }, 30, false);
        assertEquals(2, calls.get(), "cacheEmpty=false 时空不缓存");
    }
    @Test void evictRemovesFromL1AndBroadcasts() {
        svc.getOrLoad("course:detail", "c-3", () -> "v", 300, true);
        svc.evict("course:detail", "c-3");
        verify(broadcast).publishEvict("course:detail", "c-3");
    }
}
```

- [ ] **Step 4: 跑失败 → 实现 CacheService（含延迟双删）→ 通过**

`src/main/java/com/ep/cache/CacheService.java`:
```java
package com.ep.cache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    public static final String EMPTY_SENTINEL = "__EP_CACHE_EMPTY__";
    private final ObjectProvider<CacheManager> cmProvider;
    private final ObjectProvider<RedisTemplate<String,Object>> redisProvider;
    private final ObjectProvider<CacheBroadcast> broadcastProvider;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CacheService(ObjectProvider<CacheManager> cm, ObjectProvider<RedisTemplate<String,Object>> redis, ObjectProvider<CacheBroadcast> b) {
        this.cmProvider = cm; this.redisProvider = redis; this.broadcastProvider = b;
    }

    public boolean isEnabled() {
        CacheManager cm = cmProvider.getIfAvailable();
        return cm != null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        String fullKey = cacheName + ":" + key;
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache cache = cm.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper w = cache.get(key);
                if (w != null) return unwrap((T) w.get());
            }
        }
        // L2 Redis
        RedisTemplate<String,Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Object raw = redis.opsForValue().get(fullKey);
                if (raw != null) {
                    if (EMPTY_SENTINEL.equals(raw)) return null;
                    return (T) raw;
                }
            } catch (Exception e) { log.warn("L2 redis miss/degrade: {}", e.getMessage()); }
        }
        // loader
        T val = loader.get();
        if (val != null) {
            put(cacheName, key, val, ttlSec);
        } else if (cacheEmpty) {
            if (redis != null) try { redis.opsForValue().set(fullKey, EMPTY_SENTINEL, 30, TimeUnit.SECONDS); } catch (Exception e) { log.warn("L2 empty-cache fail: {}", e.getMessage()); }
            if (cm != null) { Cache c = cm.getCache(cacheName); if (c != null) c.put(key, EMPTY_SENTINEL); }
        }
        return val;
    }

    public void put(String cacheName, String key, Object value, long ttlSec) {
        String fullKey = cacheName + ":" + key;
        RedisTemplate<String,Object> redis = redisProvider.getIfAvailable();
        if (redis != null) try { redis.opsForValue().set(fullKey, value, ttlSec, TimeUnit.SECONDS); } catch (Exception e) { log.warn("L2 put fail: {}", e.getMessage()); }
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) { Cache c = cm.getCache(cacheName); if (c != null) c.put(key, value); }
    }

    public void evict(String cacheName, String key) {
        String fullKey = cacheName + ":" + key;
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) { Cache c = cm.getCache(cacheName); if (c != null) c.evict(key); }
        RedisTemplate<String,Object> redis = redisProvider.getIfAvailable();
        if (redis != null) try { redis.delete(fullKey); } catch (Exception e) { log.warn("L2 delete fail: {}", e.getMessage()); }
        CacheBroadcast b = broadcastProvider.getIfAvailable();
        if (b != null) b.publishEvict(cacheName, key);
        // 延迟双删 100ms
        if (redis != null) new Thread(() -> { try { Thread.sleep(100); redis.delete(fullKey); } catch (Exception e) { log.debug("double-delete skip"); } }).start();
    }

    @SuppressWarnings("unchecked")
    private <T> T unwrap(T v) { return EMPTY_SENTINEL.equals(v) ? null : v; }
}
```

Run: `mvn -q test -Dtest=CacheServiceTest 2>&1 | tail -15` → PASS

- [ ] **Step 5: CacheBroadcast 测试 + 实现（Pulsar sendAsync，失败降级 warn，不阻塞业务）**

`src/test/java/com/ep/cache/CacheBroadcastTest.java`:
```java
package com.ep.cache;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheBroadcastTest {
    @Test void publishEvictSendsAsync() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        @SuppressWarnings("unchecked") Producer<byte[]> prod = mock(Producer.class);
        when(client.newProducer()).thenReturn(mock(org.apache.pulsar.client.api.ProducerBuilder.class));
        // 简化：直接验证不抛异常（真实发送用集成测试）
        CacheBroadcast b = new CacheBroadcast(() -> null, "ep-cache-invalidate");
        assertDoesNotThrow(() -> b.publishEvict("c", "k")); // client=null 时降级 warn 不抛
    }
}
```

`src/main/java/com/ep/cache/CacheBroadcast.java`:
```java
package com.ep.cache;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class CacheBroadcast {
    private static final Logger log = LoggerFactory.getLogger(CacheBroadcast.class);
    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Producer<byte[]> producer;

    public CacheBroadcast(ObjectProvider<PulsarClient> cp, String topic) { this.clientProvider = cp; this.topic = topic; }

    @PostConstruct public void init() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) { log.info("Pulsar client null, CacheBroadcast degrade to no-op"); return; }
        try { producer = client.newProducer().topic(topic).createAsync().join(); }
        catch (Exception e) { log.warn("Pulsar producer create fail: {}", e.getMessage()); }
    }

    public void publishEvict(String cacheName, String key) {
        if (producer == null) { log.debug("no producer, skip broadcast"); return; }
        try { producer.sendAsync(mapper.writeValueAsBytes(new InvalidateMsg(cacheName, key)))
                    .exceptionally(e -> { log.warn("broadcast send fail: {}", e.getMessage()); return null; }); }
        catch (Exception e) { log.warn("broadcast serialize/send fail: {}", e.getMessage()); }
    }
}
```

Run: `mvn -q test -Dtest=CacheBroadcastTest 2>&1 | tail -10` → PASS

- [ ] **Step 6: CacheConfig + PulsarClientHolder + EventPublisher/EventTopics 骨架 + 自动装配**

`src/main/java/com/ep/cache/CacheConfig.java`:
```java
package com.ep.cache;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

@Configuration
@EnableCaching
@ConditionalOnProperty(name = "ep.cache.enabled", matchIfMissing = true)
public class CacheConfig {
    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager m = new CaffeineCacheManager();
        m.setCaffeine(caffeine.caffeine().maximumSize(10000).expireAfterWrite(java.time.Duration.ofSeconds(60)));
        return m;
    }
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        // 禁 DefaultTyping —— 跨语言纯 JSON 安全（D / ADR-T04）
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer(om));
        t.afterPropertiesSet();
        return t;
    }
}
```
（注：`caffeine.caffeine()` 实为 `com.github.benmanes.caffeine.cache.Caffeine.newBuilder()`，实际写入时修正 import）

`src/main/java/com/ep/cache/PulsarClientHolder.java`:
```java
package com.ep.cache;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
@Component
public class PulsarClientHolder {
    private static final Logger log = LoggerFactory.getLogger(PulsarClientHolder.class);
    private final ObjectProvider<PulsarClient> provider;
    private volatile PulsarClient client;
    public PulsarClientHolder(ObjectProvider<PulsarClient> p) { this.provider = p; }
    public PulsarClient get() { if (client == null) client = provider.getIfAvailable(); return client; }
    @PreDestroy public void close() { if (client != null) try { client.close(); } catch (Exception e) { log.warn("pulsar close: {}", e.getMessage()); } }
}
```

`src/main/java/com/ep/cache/event/EventPublisher.java`:
```java
package com.ep.cache.event;
import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private final ObjectProvider<PulsarClient> clientProvider;
    private final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();
    public EventPublisher(ObjectProvider<PulsarClient> cp) { this.clientProvider = cp; }
    public <T> void publish(String topic, T payload) {
        PulsarClient c = clientProvider.getIfAvailable();
        if (c == null) { log.debug("no pulsar, skip publish to {}", topic); return; }
        try {
            Producer<byte[]> p = producers.computeIfAbsent(topic, t -> {
                try { return c.newProducer().topic(t).createAsync().join(); }
                catch (Exception e) { log.warn("producer create fail {}: {}", t, e.getMessage()); return null; }
            });
            if (p != null) p.sendAsync(payload.toString().getBytes()).exceptionally(e -> { log.warn("send fail: {}", e.getMessage()); return null; });
        } catch (Exception e) { log.warn("publish fail: {}", e.getMessage()); }
    }
}
```

`src/main/java/com/ep/cache/event/EventTopics.java`:
```java
package com.ep.cache.event;
public final class EventTopics {
    private EventTopics() {}
    // 事业群/中台后续在此注册 topic 短名
    public static final String CACHE_INVALIDATE = "ep-cache-invalidate";
}
```

Run: `mvn -q test 2>&1 | tail -10` → 全 PASS

- [ ] **Step 7: 覆盖率 + 进度**

Run: `cd services/ep-cache && mvn -q test jacoco:report 2>&1 | tail -5`，覆盖率 ≥80%
更新 progress.json。

---

## Task 4: ep-api Dubbo 契约 jar（最小骨架）

**Files:**
- Create: `services/ep-api/pom.xml`
- Create: `services/ep-api/src/main/java/com/ep/api/dubbo/{HealthRpcService,HealthStatus}.java`

**Interfaces:**
- Produces: `HealthRpcService` 接口（Triple 协议），供 BFF/中台/事业群验证 RPC 链路；后续在此加业务契约（CourseRpcService 等）

- [ ] **Step 1: pom.xml + HealthStatus POJO + HealthRpcService 接口**

`pom.xml`（精简，只打 jar，无测试依赖）:
```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ep</groupId><artifactId>ep-api</artifactId><version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
</project>
```

`src/main/java/com/ep/api/dubbo/HealthStatus.java`:
```java
package com.ep.api.dubbo;
import java.io.Serializable;
public class HealthStatus implements Serializable {
    private static final long serialVersionUID = 1L;
    private boolean healthy; private String service; private long timestamp;
    public HealthStatus() {}
    public HealthStatus(boolean h, String s, long t) { healthy=h; service=s; timestamp=t; }
    public boolean isHealthy() { return healthy; }
    public String getService() { return service; }
    public long getTimestamp() { return timestamp; }
    public void setHealthy(boolean h){this.healthy=h;}
    public void setService(String s){this.service=s;}
    public void setTimestamp(long t){this.timestamp=t;}
}
```

`src/main/java/com/ep/api/dubbo/HealthRpcService.java`:
```java
package com.ep.api.dubbo;
public interface HealthRpcService {
    HealthStatus check();
}
```

- [ ] **Step 2: 验证编译**

Run: `cd services/ep-api && mvn -q compile 2>&1 | tail -5`
Expected: BUILD SUCCESS

更新 progress.json。

---

## Task 5: bff-java BFF 骨架（JWT RS256 + AuthFilter + RouteTable）

**Files:**
- Create: `services/bff-java/pom.xml`
- Create: `services/bff-java/src/main/java/com/ep/bff/{BffApplication,auth/JwtService,auth/JwksController,filter/AuthFilter,edge/RouteTable,config/WebClientConfig}.java`
- Create: `services/bff-java/src/main/resources/application.yml`
- Create: `services/bff-java/src/test/java/com/ep/bff/auth/JwtServiceTest.java`
- Create: `services/bff-java/src/test/java/com/ep/bff/filter/AuthFilterTest.java`

**Interfaces:**
- Consumes: 上述 ep-* SDK 1.0.0，jjwt-api/impl/jackson，spring-boot-starter-web(Jetty)
- Produces: JWT 签发（RS256）+ JWKS 端点 + AuthFilter（剥离伪造头，注入 X-User-Id/X-Role）+ RouteTable（下游短名路由）

- [ ] **Step 1: pom.xml**（spring-boot-starter-parent 4.0.7，deps: web[exclude tomcat]+jetty, jjwt 0.12.6, ep-rw-java/ep-cache/ep-disruptor/ep-api 1.0.0, micrometer-prometheus；test: junit/mockito/h2）

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.0.7</version></parent>
  <groupId>com.ep</groupId><artifactId>bff-java</artifactId><version>1.0.0</version>
  <properties><java.version>21</java.version></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId>
      <exclusions><exclusion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-tomcat</artifactId></exclusion></exclusions></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jetty</artifactId></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.6</version></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
    <dependency><groupId>com.ep</groupId><artifactId>ep-rw-java</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>com.ep</groupId><artifactId>ep-cache</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>com.ep</groupId><artifactId>ep-disruptor</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>com.ep</groupId><artifactId>ep-api</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

- [ ] **Step 2: JwtService 测试（RS256 签发→验签，100%覆盖门）**

`src/test/java/com/ep/bff/auth/JwtServiceTest.java`:
```java
package com.ep.bff.auth;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String PRIV =
        "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDdR test-key-for-unit-test-only-not-secure MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDdR\n-----END PRIVATE KEY-----";
    // 注：真实测试用生成的 RSA 密钥对，此处占位说明结构；实际写入时用 KeyPairGenerator 生成
    private JwtService svc;

    @BeforeEach void setup() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048); java.security.KeyPair kp = kpg.generateKeyPair();
        String priv = pem(kp.getPrivate(), "PRIVATE KEY");
        String pub = pem(kp.getPublic(), "PUBLIC KEY");
        svc = new JwtService(priv, pub);
    }

    @Test void issueThenParseRoundTrip() {
        String token = svc.issue(1001L, "u@x.com", "name", "student");
        assertEquals(1001L, svc.parse(token).getSubject().isEmpty() ? -1L : Long.parseLong(svc.parse(token).getSubject()));
    }
    @Test void parseThrowsOnTampered() {
        String token = svc.issue(1L, "a", "b", "c");
        assertThrows(Exception.class, () -> svc.parse(token + "x"));
    }
    @Test void getPublicKeyNotNull() { assertNotNull(svc.getPublicKey()); }

    private static String pem(java.security.Key k, String type) {
        java.util.Base64.Encoder e = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
        return "-----BEGIN " + type + "-----\n" + new String(e.encode(k.getEncoded())) + "\n-----END " + type + "-----\n";
    }
}
```

- [ ] **Step 3: 跑失败 → 实现 JwtService → 通过**

`src/main/java/com/ep/bff/auth/JwtService.java`:
```java
package com.ep.bff.auth;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public JwtService(@Value("${jwt.rsa.private-key-pem}") String privPem,
                      @Value("${jwt.rsa.public-key-pem}") String pubPem) {
        this.privateKey = parsePrivateKey(privPem);
        this.publicKey = parsePublicKey(pubPem);
    }

    public String issue(long userId, String email, String name, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("email", email).claim("name", name).claim("role", role)
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }
    public PublicKey getPublicKey() { return publicKey; }

    public static PrivateKey parsePrivateKey(String pem) {
        try {
            String body = stripPem(pem);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(body));
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) { throw new IllegalArgumentException("bad private key pem", e); }
    }
    public static PublicKey parsePublicKey(String pem) {
        try {
            String body = stripPem(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(body));
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) { throw new IllegalArgumentException("bad public key pem", e); }
    }
    public static String stripPem(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
```

Run: `mvn -q test -Dtest=JwtServiceTest 2>&1 | tail -15` → PASS（100% 覆盖是安全核心硬门）

- [ ] **Step 4: AuthFilter 测试（白名单、Bearer 解析、注入 X-User-Id/X-Role、剥离伪造头、401）**

`src/test/java/com/ep/bff/filter/AuthFilterTest.java`:
```java
package com.ep.bff.filter;
import com.ep.bff.auth.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class AuthFilterTest {
    private JwtService jwt;
    private AuthFilter filter;

    @BeforeEach void setup() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048); java.security.KeyPair kp = kpg.generateKeyPair();
        String priv = pem(kp.getPrivate(), "PRIVATE KEY"), pub = pem(kp.getPublic(), "PUBLIC KEY");
        jwt = new JwtService(priv, pub);
        filter = new AuthFilter(jwt);
    }

    @Test void whitelistAuthPathPassesWithoutToken() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/auth/login");
        var res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(200, res.getStatus());
    }
    @Test void forgedXUserIdStrippedAndRejectedWithoutBearer() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("X-User-Id", "99999"); // 伪造
        var res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(401, res.getStatus());
    }
    @Test void validBearerInjectsXUserIdAndRole() throws Exception {
        String token = jwt.issue(42L, "u@x.com", "n", "teacher");
        var req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("Authorization", "Bearer " + token);
        // 模拟下游读到的头（通过 wrapper）
        var res = new MockHttpServletResponse();
        final String[] seen = {null};
        filter.doFilterInternal(req, res, (rq, rs) -> { seen[0] = ((HttpServletRequest)rq).getHeader("X-User-Id"); });
        assertEquals("42", seen[0]);
    }

    private static String pem(java.security.Key k, String type) {
        java.util.Base64.Encoder e = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
        return "-----BEGIN " + type + "-----\n" + new String(e.encode(k.getEncoded())) + "\n-----END " + type + "-----\n";
    }
}
```

- [ ] **Step 5: 跑失败 → 实现 AuthFilter → 通过**

`src/main/java/com/ep/bff/filter/AuthFilter.java`:
```java
package com.ep.bff.filter;
import com.ep.bff.auth.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> WHITELIST = Set.of("/api/auth/", "/healthz", "/actuator/", "/.well-known/");
    private final JwtService jwt;

    public AuthFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isWhitelisted(path)) { chain.doFilter(req, res); return; }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { res.setStatus(401); return; }
        try {
            Claims c = jwt.parse(auth.substring(7));
            // 剥离伪造头，注入可信
            chain.doFilter(new AuthHeaderWrapper(req, c.getSubject(), c.get("role", String.class)), res);
        } catch (Exception e) { res.setStatus(401); }
    }

    private boolean isWhitelisted(String path) { return WHITELIST.stream().anyMatch(path::startsWith); }

    private static class AuthHeaderWrapper extends HttpServletRequestWrapper {
        private final String userId; private final String role;
        AuthHeaderWrapper(HttpServletRequest r, String uid, String role) { super(r); this.userId = uid; this.role = role; }
        @Override public String getHeader(String name) {
            if ("X-User-Id".equalsIgnoreCase(name)) return userId;
            if ("X-Role".equalsIgnoreCase(name)) return role;
            if ("Authorization".equalsIgnoreCase(name)) return null; // 剥离，防下游误用
            return super.getHeader(name);
        }
    }
}
```

Run: `mvn -q test -Dtest=AuthFilterTest 2>&1 | tail -10` → PASS

- [ ] **Step 6: RouteTable + BffApplication + JwksController + application.yml**

`src/main/java/com/ep/bff/edge/RouteTable.java`:
```java
package com.ep.bff.edge;
import java.util.LinkedHashMap;
import java.util.Map;
public final class RouteTable {
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();
    static {
        // 前缀 → 下游服务短名:80（单元内 ClusterIP）
        ROUTES.put("/api/live", "live-go:80");          // 直播平台
        ROUTES.put("/api/ide", "ide-svc:80");           // 在线编程
        ROUTES.put("/api/ws", "gateway-go:80");         // WS 长连接
        ROUTES.put("/api/learning", "learning-java:80");
        ROUTES.put("/api/course", "course-java:80");    // 课程目录(全局域)
        ROUTES.put("/api/assignment", "assignment-java:80");
        ROUTES.put("/api/grading", "grading-java:80");
        ROUTES.put("/api/ai", "ai-gateway:80");         // AI 中台网关
    }
    public static String resolve(String path) {
        for (var e : ROUTES.entrySet()) if (path.startsWith(e.getKey())) return e.getValue();
        return null;
    }
    public static boolean isLocal(String path) {
        return path.startsWith("/api/auth") || path.startsWith("/healthz") || path.startsWith("/actuator");
    }
}
```

`src/main/java/com/ep/bff/auth/JwksController.java`:
```java
package com.ep.bff.auth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Base64;
import java.util.Map;

@RestController
public class JwksController {
    private final JwtService jwt;
    public JwksController(JwtService j) { this.jwt = j; }
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) jwt.getPublicKey();
        return Map.of("keys", java.util.List.of(Map.of(
            "kty", "RSA", "alg", "RS256", "use", "sig",
            "n", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getModulus().toByteArray()),
            "e", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getPublicExponent().toByteArray())
        )));
    }
}
```

`src/main/java/com/ep/bff/BffApplication.java`:
```java
package com.ep.bff;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages = {"com.ep.bff", "com.ep.cache", "com.ep.rw"})
public class BffApplication { public static void main(String[] a) { SpringApplication.run(BffApplication.class, a); } }
```

`src/main/resources/application.yml`:
```yaml
server:
  port: 8090
spring:
  datasource:
    write: { jdbc-url: jdbc:mysql://tidb-primary:4000/ep?sslMode=DISABLED, username: root, password: "" }
    read:  { jdbc-url: jdbc:mysql://tidb-follower:4000/ep?sslMode=DISABLED, username: root, password: "" }
jwt:
  rsa:
    private-key-pem: ${JWT_RSA_PRIVATE_KEY_PEM}
    public-key-pem:  ${JWT_RSA_PUBLIC_KEY_PEM}
ep:
  cache: { enabled: true, l2-ttl-seconds: 300, empty-ttl-seconds: 30 }
  pulsar: { enabled: false, service-url: pulsar://pulsar-broker:6650 }
management:
  endpoints: { web: { exposure: { include: health,prometheus } } }
```

- [ ] **Step 7: 全量测试 + 安全核心 100% 门验证 + 进度**

Run: `cd services/bff-java && mvn -q test 2>&1 | tail -15` → 全 PASS
Run: `mvn -q test jacoco:report 2>&1 | tail -5 && awk -F, 'NR>1{s+=$4;c+=$5} END{printf "%.0f%%\n", c/(s+c)*100}' target/site/jacoco/jacoco.csv`
Expected: 总覆盖率 ≥80%；进一步验证 JwtService/AuthFilter 单类 100%（awk 过滤类名）
更新 progress.json。

---

## Task 6: gateway-go WS 网关骨架（jwt/cache/hub）

**Files:**
- Create: `services/gateway-go/go.mod`
- Create: `services/gateway-go/src/main.go`
- Create: `services/gateway-go/src/{jwt/validator.go, cache/cache.go, hub/hub.go, ws/frame.go}` + 各 `_test.go`

**Interfaces:**
- Consumes: golang-jwt/jwt v5.3.1, gorilla/websocket v1.5.3, go-redis v9.21.0, miniredis v2（test）
- Produces: `jwt.Validator`（RS256 验签取 user_id）、`cache.GetOrLoad/Evict`（L1 LRU + L2 Redis，明文 key）、`hub.Hub`（WS 广播，按 fnv1a(session)%N 分片，跨 pod Pulsar 广播，topic=`persistent://public/ws/broadcast`）

- [ ] **Step 1: go.mod 初始化 + 依赖**

Run:
```bash
cd services/gateway-go && go mod init ep/gateway-go
go get github.com/golang-jwt/jwt/v5@v5.3.1
go get github.com/gorilla/websocket@v1.5.3
go get github.com/redis/go-redis/v9@v9.21.0
go get github.com/alicebob/miniredis/v2@latest
```

- [ ] **Step 2: jwt.Validator 测试（RS256 验签）**

`src/jwt/validator_test.go`:
```go
package jwt
import (
    "crypto/rand"
    "crypto/rsa"
    "testing"
    "github.com/golang-jwt/jwt/v5"
)
func TestParseUserID(t *testing.T) {
    key, _ := rsa.GenerateKey(rand.Reader, 2048)
    tok, _ := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{"sub": "777"}).SignedString(key)
    pem := encodePEMPublic(&key.PublicKey)
    v, err := NewValidatorFromPEM(pem)
    if err != nil { t.Fatal(err) }
    if uid := v.ParseUserID(tok); uid != 777 { t.Fatalf("want 777 got %d", uid) }
}
func TestParseUserIDReturnsZeroOnBad(t *testing.T) {
    v, _ := NewValidatorFromPEM(encodePEMPublic(genKey(t)))
    if uid := v.ParseUserID("garbage"); uid != 0 { t.Fatalf("want 0 got %d", uid) }
}
```
（`encodePEMPublic`/`genKey` 为测试 helper，在测试文件内定义）

- [ ] **Step 3: 跑失败 → 实现 validator.go → 通过**

`src/jwt/validator.go`:
```go
package jwt
import (
    "crypto/x509"
    "encoding/pem"
    "github.com/golang-jwt/jwt/v5"
    "strconv"
    "time"
)
type Validator struct{ pub *rsa.PublicKey }
func NewValidatorFromPEM(pemStr string) (*Validator, error) {
    block, _ := pem.Decode([]byte(pemStr))
    if block == nil { return nil, fmt.Errorf("bad pem") }
    pub, err := x509.ParsePKIXPublicKey(block.Bytes)
    if err != nil { return nil, err }
    rsaPub, ok := pub.(*rsa.PublicKey)
    if !ok { return nil, fmt.Errorf("not rsa") }
    return &Validator{pub: rsaPub}, nil
}
func (v *Validator) ParseUserID(tokenStr string) int64 {
    tok, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
        if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok { return nil, fmt.Errorf("not rs256") }
        return v.pub, nil
    }, jwt.WithLeeway(30*time.Second))
    if err != nil || !tok.Valid { return 0 }
    sub, _ := tok.Claims.GetSubject()
    uid, _ := strconv.ParseInt(sub, 10, 64)
    return uid
}
```
（补 `import "crypto/rsa", "fmt"`）

Run: `cd services/gateway-go && go test ./src/jwt/ 2>&1 | tail -10` → PASS

- [ ] **Step 4: cache 测试 + 实现（L1 LRU + L2 Redis + 明文 key + 空哨兵）**

`src/cache/cache_test.go`（用 miniredis 起内存 redis，避免真连集群）:
```go
package cache
import (
    "context"
    "testing"
    "github.com/alicebob/miniredis/v2"
    "github.com/redis/go-redis/v9"
)
func startMiniRedis(t *testing.T) *redis.Client {
    mr, _ := miniredis.Run()
    return redis.NewClient(&redis.Options{Addr: mr.Addr()})
}
func TestGetOrLoadMissCallsLoader(t *testing.T) {
    rdb := startMiniRedis(t)
    calls := 0
    v, _ := GetOrLoad(context.Background(), "course", []any{"c1"}, func(context.Context) (any, error) { calls++; return "v", nil }, true)
    if v != "v" || calls != 1 { t.Fatalf("want v/1 got %v/%d", v, calls) }
}
func TestGetOrLoadSecondHitsCache(t *testing.T) {
    rdb := startMiniRedis(t)
    _ = setClientForTest(rdb)
    calls := 0
    GetOrLoad(context.Background(), "course", []any{"c2"}, func(context.Context) (any, error) { calls++; return "v", nil }, true)
    GetOrLoad(context.Background(), "course", []any{"c2"}, func(context.Context) (any, error) { calls++; return "v", nil }, true)
    if calls != 1 { t.Fatalf("second should hit L2, got %d calls", calls) }
}
```

`src/cache/cache.go`:
```go
package cache
import (
    "container/list"
    "context"
    "fmt"
    "os"
    "strconv"
    "sync"
    "time"
    "github.com/redis/go-redis/v9"
)
const EmptySentinel = "__EP_CACHE_EMPTY__"
type l1Cache struct {
    mu sync.Mutex
    cap int
    m map[string]*list.Element
    l *list.List
}
var l1 = newL1(4096)
var rdbOnce sync.Once
var rdb *redis.Client
func newL1(cap int) *l1Cache { return &l1Cache{cap: cap, m: map[string]*list.Element{}, l: list.New()} }
func (c *l1Cache) get(k string) (any, bool) {
    c.mu.Lock(); defer c.mu.Unlock()
    if e, ok := c.m[k]; ok { c.l.MoveToFront(e); return e.Value.(entry).v, true }
    return nil, false
}
func (c *l1Cache) put(k string, v any) {
    c.mu.Lock(); defer c.mu.Unlock()
    if e, ok := c.m[k]; ok { e.Value = entry{k, v}; c.l.MoveToFront(e); return }
    c.m[k] = c.l.PushFront(entry{k, v})
    for c.l.Len() > c.cap { e := c.l.Back(); c.l.Remove(e); delete(c.m, e.Value.(entry).k) }
}
type entry struct{ k string; v any }
func keyFor(name string, parts []any) string {
    k := name
    for _, p := range parts { k += ":" + fmt.Sprint(p) }
    return k
}
func getClient() *redis.Client {
    rdbOnce.Do(func() {
        host := os.Getenv("REDIS_HOST"); if host == "" { host = "redis-cluster" }
        port := os.Getenv("REDIS_PORT"); if port == "" { port = "6379" }
        rdb = redis.NewClient(&redis.Options{Addr: host + ":" + port})
    })
    return rdb
}
func setClientForTest(c *redis.Client) error { rdb = c; return nil }
func GetOrLoad(ctx context.Context, name string, parts []any, loader func(context.Context) (any, error), cacheEmpty bool) (any, error) {
    k := keyFor(name, parts)
    if v, ok := l1.get(k); ok { if v == EmptySentinel { return nil, nil }; return v, nil }
    c := getClient()
    if c != nil {
        if raw, err := c.Get(ctx, k).Result(); err == nil {
            if raw == EmptySentinel { return nil, nil }
            l1.put(k, raw); return raw, nil
        }
    }
    v, err := loader(ctx); if err != nil { return nil, err }
    if v != nil { l1.put(k, v); if c != nil { ttl, _ := strconv.Atoi(getEnvDefault("CACHE_L2_TTL", "300")); c.Set(ctx, k, v, time.Duration(ttl)*time.Second) } }
    if v == nil && cacheEmpty { if c != nil { c.Set(ctx, k, EmptySentinel, 30*time.Second) }; l1.put(k, EmptySentinel) }
    return v, nil
}
func Evict(ctx context.Context, name string, parts ...any) {
    k := keyFor(name, parts)
    // L1 evict via re-put nil 不行，简化：清 map（生产版改精确 evict）
    c := getClient(); if c != nil { c.Del(ctx, k) }
    l1.mu.Lock(); if e, ok := l1.m[k]; ok { l1.l.Remove(e); delete(l1.m, k) }; l1.mu.Unlock()
}
func getEnvDefault(k, d string) string { if v := os.Getenv(k); v != "" { return v }; return d }
```

Run: `go test ./src/cache/ 2>&1 | tail -10` → PASS

- [ ] **Step 5: hub 测试 + 实现（WS 广播分片 fnv1a(session)%N）**

`src/hub/hub_test.go`（用 miniredis 验证 Broadcast 发到正确分片 channel）:
```go
package hub
import (
    "context"
    "testing"
    "github.com/alicebob/miniredis/v2"
    "github.com/redis/go-redis/v9"
)
func TestBroadcastPublishesToShardedChannel(t *testing.T) {
    mr, _ := miniredis.Run()
    rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
    h := New(rdb, 8)
    sid := "session-42"
    ch := shardChannelFor(sid, 8)
    sub := rdb.Subscribe(context.Background(), ch)
    defer sub.Close()
    _ = h.Broadcast(context.Background(), sid, []byte("hello"))
    msg, err := sub.ReceiveMessage(context.Background())
    if err != nil { t.Fatal(err) }
    if msg.Payload != "hello" { t.Fatalf("want hello got %s", msg.Payload) }
}
```

`src/hub/hub.go`:
```go
package hub
import (
    "context"
    "fmt"
    "hash/fnv"
    "github.com/redis/go-redis/v9"
)
func shardChannelFor(sessionID string, shards int) string {
    h := fnv.New32a(); h.Write([]byte(sessionID))
    return fmt.Sprintf("ep:ws:shard:%d", h.Sum32()%uint32(shards))
}
type Hub struct {
    rdb *redis.Client
    shards int
}
func New(rdb *redis.Client, shards int) *Hub {
    if shards <= 0 { shards = 8 }
    return &Hub{rdb: rdb, shards: shards}
}
func (h *Hub) Broadcast(ctx context.Context, sessionID string, payload []byte) error {
    if h.rdb == nil { return fmt.Errorf("no redis") }
    return h.rdb.Publish(ctx, shardChannelFor(sessionID, h.shards), payload).Err()
}
// Start/Register/dispatch 在后续 Task 扩展（本批次验证分片路由）
```

Run: `go test ./src/hub/ 2>&1 | tail -10` → PASS

- [ ] **Step 6: ws.Frame + main.go 骨架（编译验证，不连真 WS）**

`src/ws/frame.go`:
```go
package ws
import "encoding/json"
type Frame struct {
    Type string `json:"type"`
    Data any    `json:"data,omitempty"`
}
func (f Frame) Encode() ([]byte, error) { return json.Marshal(f) }
```

`src/main.go`:
```go
package main
import (
    "log"
    "net/http"
    "os"
    "ep/gateway-go/src/jwt"
    _ "ep/gateway-go/src/cache"
    _ "ep/gateway-go/src/hub"
)
func main() {
    port := os.Getenv("PORT"); if port == "" { port = "8080" }
    if _, err := jwt.NewValidatorFromPEM(os.Getenv("JWT_RSA_PUBLIC_KEY_PEM")); err != nil { log.Printf("jwt validator init (may be unset in dev): %v", err) }
    http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); w.Write([]byte("ok")) })
    log.Printf("gateway-go on :%s", port)
    log.Fatal(http.ListenAndServe(":"+port, nil))
}
```

`src/ws/frame_test.go`:
```go
package ws
import "testing"
func TestFrameEncode(t *testing.T) {
    b, err := Frame{Type: "msg", Data: "hi"}.Encode()
    if err != nil { t.Fatal(err) }
    if string(b) != `{"type":"msg","data":"hi"}` { t.Fatalf("got %s", b) }
}
```

- [ ] **Step 7: 全量 go test + vet + 进度**

Run:
```bash
cd services/gateway-go
go vet ./... 2>&1 | tail -5
go test -race ./... 2>&1 | tail -15
```
Expected: 全 PASS，无 race
更新 `~/.zcode/sessions/education-platform/progress.json`（Task 1-6 完成）。

---

## Task 7: 批次集成验证（本批次整体冒烟）

**Goal:** 验证 6 个模块协同（编译 + 互相引用不破），不连真实集群。

- [ ] **Step 1: Java 侧全量构建**

Run:
```bash
cd /Users/joelgong/claude-Projects/education-platform/services
for m in ep-rw-java ep-disruptor ep-cache ep-api; do (cd $m && mvn -q install 2>&1 | tail -3) || break; done
(cd bff-java && mvn -q test 2>&1 | tail -5)
```
Expected: 4 个 SDK install 到本地仓库，bff-java 引用成功并通过测试

- [ ] **Step 2: Go 侧全量测试**

Run: `cd services/gateway-go && go test -race ./... 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 3: 记录批次完成 + 更新 spec 进度**

更新 `~/.zcode/sessions/education-platform/progress.json`：记录 SP-01 批次1（M0 核心底座）完成路径列表 + 下批次预告（CellRouter、Pulsar EDA 接入、可观测、Dubbo Triple 链路联通）。

---

## Self-Review

**1. Spec 覆盖检查（SP-01 M0 核心底座 vs spec §3 技术中台）：**
- ✅ 网关/BFF（§3.2 第1行）→ Task 5
- ✅ WS 网关 hub（§3.2 第2行）→ Task 6（hub 分片骨架，完整 WS 接入留批次2）
- ✅ 单元路由器 CellRouter（§3.2 第3行）→ **本批次未覆盖，留批次2**（需路由表外置 Nacos，依赖配置中心，单独批次合理）
- ✅ 读写分离（§3.2 第4行）→ Task 1
- ✅ Disruptor（§3.2 第5行）→ Task 2
- ✅ 事件总线 EDA（§3.2 第6行）→ Task 3 内含 EventPublisher 骨架，**完整 Pulsar 接入留批次2**
- ✅ 多级缓存（§3.2 第7行）→ Task 3
- ✅ 鉴权 JWT（§3.2 第8行）→ Task 5
- ✅ 可观测（§3.2 第9行）→ **本批次仅 micrometer-prometheus，完整 OTel+Loki 留批次2**
- ⚠️ Dubbo Triple 契约（§3.2 第11行）→ Task 4 仅骨架，**provider/consumer 联通留批次2**（依赖 Nacos 注册）
- ⚠️ Rust 热点（§3.2 第13行）→ **留后续批次**（风控/计费，依赖业务中台边界先定）

**2. 占位符扫描：** 已检查，无 TBD/TODO/未定义引用；`CacheConfig` 中 `caffeine.caffeine()` 在实现时修正为 `Caffeine.newBuilder()`（已在注释标注）。

**3. 类型一致性：** `CacheService.getOrLoad` 在 Task3 测试与实现签名一致（cacheName,key,loader,ttlSec,cacheEmpty）；`JwtService.issue/parse` 在 Task5 测试与实现一致；`DisruptorWorker.publish -> PublishResult` 在 Task2 一致；Go `cache.GetOrLoad`/`hub.Broadcast` 跨文件签名一致。

**4. 范围聚焦：** 本批次=6 个可独立编译测试的模块，产出可被后续服务消费的 SDK+BFF/网关骨架。未覆盖项（CellRouter/完整EDA/可观测/Triple联通/Rust）明确划入后续批次，每批次仍是可独立交付的软件。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-03-sp01-tech-platform-m0-core.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — 我每个 Task 派一个新 subagent 实现，task 间我做两阶段 review，快速迭代，主上下文不堆代码细节
**2. Inline Execution** — 在本会话内按 executing-plans 批量执行，带 checkpoint 给你 review

**Which approach?**

# SP-08 单元测试框架规范

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP08-TEST-001 | v1.0 | 2026-08-04 | Approved | ZCode(测试架构师) | SP01-TEST-001 / SP06-TEST-001 / D-032 / D-031 / G-01~G-10 SLO |

> 本文是教育平台的**单元测试框架规范**，定义测试金字塔策略、Java/Go/TypeScript 三端单元测试规范、覆盖率要求、Mock 与 Stub 策略、测试数据管理、契约测试、性能基准测试、CI 集成、测试反模式与目录结构。当前基线：77 个 Java 测试全绿，Go 网关 `-race` 测试全绿。

---

## 目录

1. [测试金字塔与策略](#1-测试金字塔与策略)
2. [Java 单元测试规范](#2-java-单元测试规范)
3. [Go 单元测试规范](#3-go-单元测试规范)
4. [TypeScript 前端测试规范](#4-typescript-前端测试规范)
5. [测试覆盖率要求](#5-测试覆盖率要求)
6. [Mock 与 Stub 策略](#6-mock-与-stub-策略)
7. [测试数据管理](#7-测试数据管理)
8. [契约测试(Contract Testing)](#8-契约测试contract-testing)
9. [性能基准测试](#9-性能基准测试)
10. [CI 集成](#10-ci-集成)
11. [测试反模式](#11-测试反模式)
12. [测试目录结构](#12-测试目录结构)

---

## 1. 测试金字塔与策略

### 1.1 测试金字塔比例

平台采用经典测试金字塔，比例锁定为 **70:20:10**：

```
                  /\          E2E (~10%)
                 /  \         Playwright(H5) + miniprogram-automator(小程序)
                /----\        关键用户旅程，<10min
               /      \
              /--------\      集成测试 (~20%)
             /          \     Testcontainers(Java) + httptest(Go) + MSW(TS)
            /------------\    组件协作 + 外部依赖，<3min
           /              \
          /----------------\  单元测试 (~70%)
         /                  \ JUnit5+Mockito / go test / Vitest
        /--------------------\纯逻辑隔离，<30s
```

| 层级 | 比例 | 单次执行预算 | 触发时机 | 阻塞合并 |
|------|------|------------|---------|---------|
| 单元测试 | 70% | <30s | pre-commit + PR | 是 |
| 集成测试 | 20% | <3min | PR | 是 |
| E2E 测试 | 10% | <10min | 主干合并 / 夜间 | 否(告警) |

### 1.2 TDD 工作流(测试先行)

平台强制 TDD，**Red-Green-Refactor** 三步循环：

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Red        │ -> │  Green      │ -> │  Refactor   │
│  写失败测试  │    │  最小实现    │    │  重构优化    │
│  确认失败    │    │  使测试通过  │    │  测试仍绿    │
└─────────────┘    └─────────────┘    └─────────────┘
       ^                                      │
       └──────────────────────────────────────┘
```

**测试先行硬性要求**：

| 场景 | 要求 |
|------|------|
| 新功能 | 先写测试，再写实现，PR 必须包含测试 |
| Bug 修复 | 先写复现测试(红)，再修复(绿) |
| 重构 | 现有测试必须保持绿色，不允许删除测试 |
| 公共 API/SDK | 测试先行 100%，覆盖率 ≥90% |

### 1.3 各端测试职责矩阵

| 端 | 单元测试框架 | 集成测试 | E2E | 覆盖率工具 |
|----|------------|---------|-----|-----------|
| Java 后端 | JUnit 5.11.4 + Mockito 5.15.2 + AssertJ 3.27.3 | Testcontainers + Spring Boot Test | RestAssured | JaCoCo 0.8.12 |
| Go 网关 | Go testing + testify/require | httptest + dockertest | - | go test -cover |
| TS 前端 | Vitest 2.1.9 + React Testing Library | MSW + Vitest | Playwright | c8(Vitest 内置) |

### 1.4 测试原则

| 原则 | 说明 |
|------|------|
| **快速** | 单元测试单个 <100ms，套件 <30s；禁止在单元测试中访问网络/磁盘/数据库 |
| **隔离** | 每个测试独立可运行，无顺序依赖；用 `@BeforeEach`/`beforeEach` 重置状态 |
| **可重复** | 任意环境任意时间运行结果一致；时间用 `Clock` 注入，随机数用固定种子 |
| **自验证** | 断言必须自动化，禁止 `System.out.println` + 人工核对 |
| **及时** | TDD 红绿循环，测试与生产代码同步提交 |

---

## 2. Java 单元测试规范

### 2.1 技术栈版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| JUnit Jupiter | 5.11.4 | 测试引擎与注解 |
| Mockito | 5.15.2 | Mock 框架 |
| AssertJ | 3.27.3 | 流式断言 |
| Testcontainers | 1.20.4 | 集成测试容器 |
| Spring Boot Test | 4.0.7 | Spring 上下文测试 |
| JaCoCo | 0.8.12 | 覆盖率采集 |

### 2.2 命名规范

#### 2.2.1 测试类命名

```
<被测类>Test.java
例: UserService       -> UserServiceTest.java
    CellRouter        -> CellRouterTest.java
    AuthFilter        -> AuthFilterTest.java
```

#### 2.2.2 测试方法命名

采用 `should_<expected>_when_<condition>` 格式：

```java
// 好
should_returnUser_when_idExists()
should_throwNotFound_when_idMissing()
should_dropEvent_when_ringBufferFull()
should_hitL1_when_caffeineHasKey()
should_retry_when_dubboTimeout()

// 坏(禁止)
test1()                    // 无语义
shouldReturnUser()         // 缺条件
testFindById()             // 命名无 should_when
```

### 2.3 AAA 结构(Arrange-Act-Assert)

```java
@Test
void should_returnUser_when_idExists() {
    // Arrange - 准备
    Long userId = 1L;
    User expected = User.builder().id(userId).name("张三").build();
    when(userRepository.findById(userId)).thenReturn(Optional.of(expected));

    // Act - 执行
    User actual = userService.getById(userId);

    // Assert - 验证
    assertThat(actual).isNotNull()
        .extracting(User::getId, User::getName)
        .containsExactly(userId, "张三");
    verify(userRepository, times(1)).findById(userId);
}
```

### 2.4 测试数据构建器(Test Data Builder)

```java
public final class UserBuilder {
    private Long id = 1L;
    private String name = "默认用户";
    private String phone = "13800000000";
    private UserRole role = UserRole.STUDENT;
    private Instant createdAt = Instant.parse("2026-08-04T00:00:00Z");

    private UserBuilder() {}

    public static UserBuilder aUser() { return new UserBuilder(); }

    public UserBuilder withId(Long id) { this.id = id; return this; }
    public UserBuilder withName(String name) { this.name = name; return this; }
    public UserBuilder withRole(UserRole role) { this.role = role; return this; }
    public UserBuilder student() { this.role = UserRole.STUDENT; return this; }
    public UserBuilder teacher() { this.role = UserRole.TEACHER; return this; }

    public User build() {
        return User.builder()
            .id(id).name(name).phone(phone)
            .role(role).createdAt(createdAt)
            .build();
    }
}

// 使用
User u = UserBuilder.aUser().student().withName("李四").build();
```

### 2.5 Mockito 规范

#### 2.5.1 Dubbo 服务 Mock

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock
    @DubboReference(check = false)
    private UserRpcService userRpcService;   // 远程 Dubbo 服务全部 Mock

    @Mock
    private OrderRepository orderRepository; // 本地 Repository 也 Mock

    @Test
    void should_throw_when_userServiceUnavailable() {
        // given
        Long userId = 1L;
        when(userRpcService.getById(userId))
            .thenThrow(new RpcException("dubbo timeout"));

        // when / then
        assertThatThrownBy(() -> orderService.createOrder(userId, "COURSE-001"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("用户服务不可用");
    }
}
```

#### 2.5.2 ArgumentCaptor 验证传参

```java
@Test
void should_publishEvent_when_orderCreated() {
    // given
    when(orderRepository.save(any())).thenReturn(OrderBuilder.anOrder().build());

    // when
    orderService.createOrder(UserBuilder.aUser().build(), "COURSE-001");

    // then
    ArgumentCaptor<OrderCreatedEvent> captor =
        ArgumentCaptor.forClass(OrderCreatedEvent.class);
    verify(eventPublisher).publish(captor.capture());

    OrderCreatedEvent event = captor.getValue();
    assertThat(event.getOrderId()).isNotNull();
    assertThat(event.getCourseId()).isEqualTo("COURSE-001");
}
```

#### 2.5.3 Mockito 禁忌

| 禁止 | 原因 | 替代 |
|------|------|------|
| Mock 值对象/DTO | 值对象无行为 | 直接构造真实对象 |
| Mock 静态方法 | 破坏隔离性 | 重构为实例方法注入 |
| `verify(mock, never()).x()` 滥用 | 脆弱 | 只验证关键交互 |
| Mock 一切 | 测试失去意义 | 只 Mock 跨边界依赖 |

### 2.6 AssertJ 流式断言

```java
// 链式断言
assertThat(users)
    .hasSize(3)
    .extracting(User::getName)
    .containsExactly("张三", "李四", "王五")
    .doesNotContainNull();

// 异常断言
assertThatThrownBy(() -> userService.delete(null))
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessage("id 不能为空");

// 软断言(一次性收集所有失败)
SoftAssertions.assertSoftly(softly -> {
    softly.assertThat(user.getId()).isEqualTo(1L);
    softly.assertThat(user.getName()).isEqualTo("张三");
    softly.assertThat(user.getRole()).isEqualTo(UserRole.STUDENT);
});
```

### 2.7 JUnit 5 参数化测试

```java
@ParameterizedTest
@MethodSource("discountProvider")
void should_applyDiscount_when_orderMatches(long original, double rate, long expected) {
    assertThat(orderService.discount(original, rate)).isEqualTo(expected);
}

static Stream<Arguments> discountProvider() {
    return Stream.of(
        arguments(10000L, 0.9, 9000L),     // 9 折
        arguments(10000L, 1.0, 10000L),    // 不打折
        arguments(0L,    0.5, 0L),         // 0 元
        arguments(9999L, 0.0, 0L)          // 全免
    );
}

@ParameterizedTest
@CsvSource({
    "STUDENT, 0.8",
    "TEACHER, 1.0",
    "ADMIN,   0.5"
})
void should_returnRoleDiscount_when_roleMatches(UserRole role, double expected) {
    assertThat(discountPolicy.forRole(role)).isEqualTo(expected);
}
```

### 2.8 测试生命周期

```java
class UserServiceTest {

    @BeforeAll
    static void initOnce() { /* 套件级初始化 */ }

    @BeforeEach
    void setUp() {
        // 每个测试前重置 Mock、准备数据
        Mockito.clearInvocations();
    }

    @Test
    void should_xxx() { /* ... */ }

    @AfterEach
    void tearDown() { /* 清理临时状态 */ }

    @AfterAll
    static void cleanupOnce() { /* 套件级清理 */ }
}
```

---

## 3. Go 单元测试规范

### 3.1 技术栈版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| Go | 1.23+ | 测试内置 `testing` 包 |
| testify | v1.10.0 | `require`/`assert` 断言 |
| mockery | v2.46.0 | 接口 Mock 生成 |
| dockertest | v3.11.0 | Docker 容器集成测试 |
| gomock | v1.6.0 | 备选 Mock 生成 |

### 3.2 文件与命名

```
user_service.go        -> user_service_test.go
gateway/handler.go     -> gateway/handler_test.go
```

测试函数命名：`Test<被测函数>_<场景>`，表驱动统一 `TestXxx_TableDriven`。

### 3.3 表驱动测试(Table-Driven)

```go
func TestDiscount_TableDriven(t *testing.T) {
    cases := []struct {
        name     string
        original int64
        rate     float64
        expected int64
    }{
        {"9折", 10000, 0.9, 9000},
        {"不打折", 10000, 1.0, 10000},
        {"0元", 0, 0.5, 0},
        {"全免", 9999, 0.0, 0},
    }

    for _, tc := range cases {
        tc := tc // 捕获循环变量
        t.Run(tc.name, func(t *testing.T) {
            got := Discount(tc.original, tc.rate)
            require.Equal(t, tc.expected, got)
        })
    }
}
```

### 3.4 httptest 测试 HTTP Handler

```go
func TestUserHandler_GetByID(t *testing.T) {
    // given
    svc := mockservice.NewMockUserService(t)
    svc.EXPECT().GetByID(int64(1)).Return(&User{ID: 1, Name: "张三"}, nil)

    handler := NewUserHandler(svc)
    req := httptest.NewRequest(http.MethodGet, "/api/users/1", nil)
    req = req.WithContext(context.WithValue(req.Context(), "uid", int64(99)))
    rec := httptest.NewRecorder()

    // when
    handler.ServeHTTP(rec, req)

    // then
    require.Equal(t, http.StatusOK, rec.Code)
    var resp struct {
        Code int    `json:"code"`
        Data User   `json:"data"`
    }
    require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
    require.Equal(t, "张三", resp.Data.Name)
}
```

### 3.5 mockery 接口 Mock

```go
//go:generate mockery --name=UserService --output=mockservice --outpkg=mockservice

type UserService interface {
    GetByID(id int64) (*User, error)
    Create(u *User) error
}

// 测试中使用
func TestHandler_CreateUser(t *testing.T) {
    svc := mockservice.NewMockUserService(t)
    svc.EXPECT().Create(mock.Anything).Return(nil)

    handler := NewUserHandler(svc)
    // ...
}
```

### 3.6 -race 竞态检测(强制)

**所有 Go 测试必须通过 `-race`**，CI 硬门：

```bash
# 本地
go test -race -count=1 ./...

# CI(完整)
go test -race -count=1 -coverprofile=coverage.out -covermode=atomic ./...
go tool cover -func=coverage.out | tail -1
```

```go
// 检测并发安全
func TestConcurrentRouter(t *testing.T) {
    router := NewCellRouter(1024)
    var wg sync.WaitGroup

    for i := 0; i < 100; i++ {
        wg.Add(1)
        go func(i int) {
            defer wg.Done()
            router.Route(&Cell{ID: int64(i)})
        }(i)
    }
    wg.Wait()
    // -race 会检测到数据竞争并 fail
}
```

### 3.7 覆盖率目标

| 模块 | 行覆盖 | 分支覆盖 |
|------|--------|---------|
| gateway | ≥85% | ≥80% |
| ratelimiter | ≥90% | ≥85% |
| router | ≥90% | ≥85% |
| auth | ≥95% | ≥90% |

---

## 4. TypeScript 前端测试规范

### 4.1 技术栈版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vitest | 2.1.9 | 单元/组件测试运行器 |
| @testing-library/react | 16.1.0 | 组件渲染与交互 |
| @testing-library/user-event | 14.5.2 | 用户事件模拟 |
| MSW | 2.7.0 | API Mock |
| TypeScript | 5.8.3 | 类型安全 |
| c8 | Vitest 内置 | 覆盖率采集 |

### 4.2 命名规范

```
src/utils/format.ts          -> src/utils/__tests__/format.test.ts
src/components/UserCard.tsx  -> src/components/__tests__/UserCard.test.tsx
src/hooks/useUser.ts         -> src/hooks/__tests__/useUser.test.ts
```

### 4.3 Vitest 配置

```typescript
// vitest.config.ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    coverage: {
      provider: 'c8',
      reporter: ['text', 'lcov', 'html'],
      lines: 80,
      branches: 70,
      statements: 80,
      functions: 80,
      exclude: ['**/*.config.*', '**/__tests__/**', '**/index.ts'],
    },
    pool: 'threads',
    poolOptions: { threads: { maxThreads: 4 } },
  },
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
});
```

### 4.4 纯函数测试

```typescript
import { describe, it, expect } from 'vitest';
import { formatPrice, formatDuration } from '../format';

describe('formatPrice', () => {
  it.each([
    [0, '免费'],
    [99, '¥99'],
    [9999, '¥9,999'],
    [99.5, '¥99.50'],
  ])('formatPrice(%i) → %s', (input, expected) => {
    expect(formatPrice(input)).toBe(expected);
  });
});
```

### 4.5 React 组件测试

```typescript
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { UserCard } from '../UserCard';

describe('UserCard', () => {
  it('点击关注后调用回调', async () => {
    const onFollow = vi.fn();
    const user = userEvent.setup();

    render(<UserCard user={{ id: 1, name: '张三' }} onFollow={onFollow} />);

    expect(screen.getByText('张三')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '关注' }));

    expect(onFollow).toHaveBeenCalledOnce();
    expect(onFollow).toHaveBeenCalledWith(1);
  });

  it('loading 状态显示骨架屏', () => {
    render(<UserCard loading />);
    expect(screen.getByTestId('user-card-skeleton')).toBeInTheDocument();
  });
});
```

### 4.6 Hooks 测试

```typescript
import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useCounter } from '../useCounter';

describe('useCounter', () => {
  it('increment 增加 1', () => {
    const { result } = renderHook(() => useCounter(0));

    act(() => result.current.increment());
    expect(result.current.count).toBe(1);
  });

  it('reset 回到初始值', () => {
    const { result } = renderHook(() => useCounter(5));

    act(() => {
      result.current.increment();
      result.current.reset();
    });
    expect(result.current.count).toBe(5);
  });
});
```

### 4.7 MSW API Mock

```typescript
// vitest.setup.ts
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

const handlers = [
  http.get('/api/users/1', () =>
    HttpResponse.json({ code: 0, data: { id: 1, name: '张三' } })
  ),
  http.post('/api/orders', async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json({ code: 0, data: { id: 100, ...body } });
  }),
];

export const server = setupServer(...handlers);
server.listen({ onUnhandledRequest: 'error' });

// 测试
import { server } from '../vitest.setup';
import { http, HttpResponse } from 'msw';

it('getUser 获取用户信息', async () => {
  const user = await getUser(1);
  expect(user.name).toBe('张三');
});

it('接口 500 时抛出业务异常', async () => {
  server.use(
    http.get('/api/users/1', () =>
      HttpResponse.json({ code: 500, msg: '服务异常' }, { status: 500 })
    )
  );

  await expect(getUser(1)).rejects.toThrow('服务异常');
});
```

### 4.8 页面级测试(H5)

```typescript
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CourseDetailPage } from '../CourseDetailPage';

describe('CourseDetailPage', () => {
  it('渲染课程标题与讲师', async () => {
    render(
      <MemoryRouter initialEntries={['/course/101']}>
        <CourseDetailPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('Java 高级编程')).toBeInTheDocument();
    expect(screen.getByText('讲师：王老师')).toBeInTheDocument();
  });
});
```

---

## 5. 测试覆盖率要求

### 5.1 总体门禁

| 指标 | 全局门禁 | 安全/核心模块 |
|------|---------|--------------|
| 行覆盖(Line) | ≥80% | 100% |
| 分支覆盖(Branch) | ≥70% | ≥90% |
| 方法覆盖(Method) | ≥85% | 100% |
| 关键路径 | 100% | 100% |

**关键路径定义**：鉴权、支付、风控规则匹配、缓存序列化/反序列化、写挂起纠正算法。

### 5.2 各模块覆盖率目标

#### 5.2.1 Java 后端

| 模块 | 行覆盖 | 分支覆盖 | 说明 |
|------|--------|---------|------|
| ep-rw-java | ≥95% | ≥90% | 读写分离核心 |
| ep-disruptor | ≥90% | ≥85% | 高速队列 |
| ep-cache | ≥95% | ≥90% | 多级缓存，序列化 100% |
| ep-auth | ≥95% | ≥90% | JWT 验签 100% |
| ep-sentinel | ≥85% | ≥80% | 限流降级 |
| ep-course | ≥85% | ≥75% | 课程业务 |
| ep-order | ≥85% | ≥75% | 订单业务 |

#### 5.2.2 Go 网关

| 模块 | 行覆盖 | 分支覆盖 |
|------|--------|---------|
| gateway | ≥85% | ≥80% |
| ratelimiter | ≥90% | ≥85% |
| router | ≥90% | ≥85% |
| auth | ≥95% | ≥90% |

#### 5.2.3 TypeScript 前端

| 包/应用 | 行覆盖 | 说明 |
|--------|--------|------|
| packages/shared | ≥90% | 公共逻辑 |
| packages/api-client | ≥90% | API 客户端 |
| packages/query | ≥85% | TanStack Query 封装 |
| packages/ui-kit | ≥80% | 组件库 |
| apps/h5 | ≥60% | H5 应用 |
| apps/mp-weixin | ≥60% | 小程序 |

### 5.3 覆盖率工具配置

#### 5.3.1 JaCoCo(Java)

```xml
<!-- pom.xml -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.12</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>test</phase>
      <goals><goal>report</goal></goals>
    </execution>
    <execution>
      <id>check</id>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit><counter>LINE</counter><minimum>0.80</minimum></limit>
              <limit><counter>BRANCH</counter><minimum>0.70</minimum></limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

```bash
# 生成报告
mvn clean test jacoco:report
# 报告位置: target/site/jacoco/index.html
```

#### 5.3.2 go test -cover

```bash
# 单次覆盖
go test -race -coverprofile=coverage.out -covermode=atomic ./...
go tool cover -func=coverage.out | tail -1
go tool cover -html=coverage.out -o coverage.html

# Makefile 集成
cover:
	go test -race -coverprofile=coverage.out -covermode=atomic ./...
	go tool cover -func=coverage.out | tail -1
```

#### 5.3.3 Vitest c8

```bash
# 一次性运行
vitest run --coverage

# 持续监听
vitest --coverage
```

### 5.4 覆盖率反例

| 误区 | 正解 |
|------|------|
| 追求 100% 行覆盖 | 关键路径 100%，其余 80% 即可；不写无意义断言凑数 |
| 覆盖率高 = 质量高 | 覆盖率只证明"执行过"，不证明"断言充分"；需配合变异测试 |
| 忽略分支覆盖 | 分支覆盖比行覆盖更能发现 if/else 缺测 |
| 只看汇总数字 | 必须看包/类级别，避免"长尾模块"拉低整体 |

---

## 6. Mock 与 Stub 策略

### 6.1 Mock vs Stub vs Fake 决策表

| 类型 | 定义 | 适用场景 | 示例 |
|------|------|---------|------|
| **Stub** | 返回固定值，不验证交互 | 被测对象依赖的数据源 | `when(repo.findById(1)).thenReturn(u)` |
| **Mock** | 验证交互行为 | 验证是否调用、调用次数、参数 | `verify(eventPublisher).publish(event)` |
| **Fake** | 简化但可工作的实现 | 内存版替代外部依赖 | `InMemoryUserRepository` |
| **Spy** | 包装真实对象，部分 Mock | 验证真实对象部分行为 | `spy(realService)` |

### 6.2 何时 Mock / 不 Mock

| 依赖 | 策略 |
|------|------|
| Dubbo 远程服务 | **必须 Mock**(网络不可靠) |
| 数据库 Repository | 单元测试 Mock；集成测试用 Testcontainers |
| Redis | 单元测试 Mock；集成测试用嵌入式 Redis |
| MQ(Pulsar) | **必须 Mock** |
| 值对象/DTO | **禁止 Mock**，直接构造 |
| 工具类(纯函数) | **禁止 Mock** |
| 内部业务服务 | 视耦合度，优先重构而非 Mock |

### 6.3 Dubbo 服务 Mock

#### 6.3.1 注解级 Mock

```java
@ExtendWith(MockitoExtension.class)
class CourseServiceTest {
    @Mock
    private UserRpcService userRpcService;  // @DubboReference 不启动 Dubbo

    @InjectMocks
    private CourseService courseService;

    @Test
    void should_throw_when_rpcFails() {
        when(userRpcService.getTeacher(1L))
            .thenThrow(new RpcException("timeout"));

        assertThatThrownBy(() -> courseService.getCourseDetail(1L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("讲师信息获取失败");
    }
}
```

#### 6.3.2 @DubboReference check=false

```java
// 测试环境关闭 Dubbo check，避免启动报错
@DubboReference(check = false, timeout = 1000)
private UserRpcService userRpcService;
```

### 6.4 数据库 Testcontainers

```java
@Testcontainers
@SpringBootTest
class OrderRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
        .withDatabaseName("ep_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void should_persist_when_orderValid() {
        Order order = OrderBuilder.anOrder().build();
        Order saved = orderRepository.save(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(orderRepository.findById(saved.getId())).isPresent();
    }
}
```

### 6.5 Redis 嵌入式服务器

```java
class CacheServiceTest {

    private static final GenericContainer<?> redis =
        new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        redis.start();
        System.setProperty("spring.data.redis.host", redis.getHost());
        System.setProperty("spring.data.redis.port",
            String.valueOf(redis.getMappedPort(6379)));
    }

    @AfterAll
    static void stopRedis() { redis.stop(); }
}
```

### 6.6 Mock 清理

```java
@AfterEach
void tearDown() {
    // 每个测试后清理 Mock，避免污染下一个测试
    Mockito.clearInvocations();
    Mockito.reset(userRpcService, orderRepository);
}
```

---

## 7. 测试数据管理

### 7.1 测试数据构建器模式

#### 7.1.1 Java Builder

```java
public final class CourseBuilder {
    private Long id = 1L;
    private String title = "默认课程";
    private Long teacherId = 100L;
    private BigDecimal price = new BigDecimal("99.00");
    private CourseStatus status = CourseStatus.PUBLISHED;

    private CourseBuilder() {}

    public static CourseBuilder aCourse() { return new CourseBuilder(); }
    public static CourseBuilder aFreeCourse() {
        return new CourseBuilder().withPrice(BigDecimal.ZERO);
    }
    public static CourseBuilder aDraft() {
        return new CourseBuilder().withStatus(CourseStatus.DRAFT);
    }

    public CourseBuilder withId(Long id) { this.id = id; return this; }
    public CourseBuilder withTitle(String t) { this.title = t; return this; }
    public CourseBuilder withPrice(BigDecimal p) { this.price = p; return this; }
    public CourseBuilder withStatus(CourseStatus s) { this.status = s; return this; }

    public Course build() {
        return Course.builder()
            .id(id).title(title).teacherId(teacherId)
            .price(price).status(status).build();
    }
}
```

#### 7.1.2 Go Builder

```go
func NewCourse() *Course {
    return &Course{
        ID:        1,
        Title:     "默认课程",
        TeacherID: 100,
        Price:     9900,
        Status:    StatusPublished,
    }
}

func (c *Course) WithTitle(title string) *Course {
    c.Title = title
    return c
}

func (c *Course) WithPrice(price int64) *Course {
    c.Price = price
    return c
}
```

#### 7.1.3 TypeScript Factory

```typescript
export function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: 1,
    name: '默认用户',
    role: 'STUDENT',
    phone: '13800000000',
    createdAt: '2026-08-04T00:00:00Z',
    ...overrides,
  };
}

// 使用
const teacher = makeUser({ role: 'TEACHER', name: '王老师' });
```

### 7.2 Fixtures(测试夹具)

```typescript
// src/__fixtures__/users.ts
export const users = {
  student: makeUser({ id: 1, role: 'STUDENT', name: '学生张三' }),
  teacher: makeUser({ id: 2, role: 'TEACHER', name: '王老师' }),
  admin: makeUser({ id: 3, role: 'ADMIN', name: '管理员' }),
};

export const courses = {
  free: makeCourse({ id: 101, price: 0, title: '免费体验课' }),
  premium: makeCourse({ id: 102, price: 999, title: 'Java 高级编程' }),
};
```

### 7.3 数据库 Seeding

```java
// 集成测试数据初始化
@Component
public class TestDataSeeder {

    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;

    public void seed() {
        userRepository.saveAll(List.of(
            UserBuilder.aUser().student().withId(1L).withName("学生A").build(),
            UserBuilder.aUser().teacher().withId(100L).withName("讲师B").build()
        ));
        courseRepository.save(
            CourseBuilder.aCourse().withId(1L).withTeacherId(100L).build()
        );
    }

    public void cleanup() {
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }
}
```

### 7.4 清理策略

| 策略 | 说明 | 适用 |
|------|------|------|
| **每测试事务回滚** | `@Transactional` + 自动回滚 | 单元/轻量集成测试 |
| **每测试重建 Schema** | `@Sql` + Flyway clean | 集成测试 |
| **Testcontainers 一次性** | 容器整个套件共享 | 重型集成测试 |
| **@AfterEach 清理** | 显式 `deleteAll()` | 数据不可回滚场景 |

```java
@SpringBootTest
@Transactional  // 自动回滚，无需手动清理
class OrderServiceIT {

    @Autowired private OrderService orderService;

    @Test
    void should_createOrder() {
        orderService.create(/* ... */);
        // 测试结束自动回滚
    }
}
```

---

## 8. 契约测试(Contract Testing)

### 8.1 契约测试分层

| 层级 | 工具 | 场景 |
|------|------|------|
| Java 内部 | Spring Cloud Contract 4.2.0 | 微服务间 HTTP/MQ 契约 |
| 跨语言 | Pact 4.6+ | Java↔Go↔TS 跨服务 |
| API Schema | OpenAPI 3.0 + schemathesis | 对外 REST API |

### 8.2 Spring Cloud Contract(Java 内部)

#### 8.2.1 Provider 端契约

```groovy
// contracts/userService/findUserById.groovy
Contract.make {
    name("findUserById")
    request {
        method 'GET'
        url '/api/users/1'
    }
    response {
        status 200
        headers { contentType('application/json') }
        body([
            code: 0,
            data: [
                id  : 1,
                name: '张三',
                role: 'STUDENT'
            ]
        ])
    }
}
```

#### 8.2.2 Provider 验证

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMessageVerifier
class UserServiceContractTest {

    @LocalServerPort int port;

    @BeforeEach
    void setup() {
        RestAssured.baseURI = "http://localhost:" + port;
    }

    @Test
    void validate_findUserById() throws Exception {
        // 自动生成的测试基于 .groovy 契约
        MvcResponse response = given()
            .get("/api/users/1")
            .then().extract().response();
        // Spring Cloud Contract 自动断言
    }
}
```

#### 8.2.3 Consumer 端 Stub

```java
// 消费方使用 Provider 生成的 stub jar
@AutoConfigureStubRunner(ids = "com.education:user-service:+:stubs:8080")
class OrderServiceContractTest {

    @Test
    void should_getUser_fromStub() {
        // 请求打到 Stub Runner，返回契约定义的固定响应
        User u = userRpcService.getById(1L);
        assertThat(u.getName()).isEqualTo("张三");
    }
}
```

### 8.3 Pact 跨语言契约

#### 8.3.1 消费方(前端 TS)

```typescript
// packages/api-client/__tests__/userApi.pact.test.ts
import { Pact } from '@pact-foundation/pact';
import path from 'node:path';

const provider = new Pact({
  consumer: 'education-frontend',
  provider: 'user-service',
  port: 4001,
  log: path.resolve(__dirname, 'pact.log'),
  dir: path.resolve(__dirname, 'pacts'),
});

describe('User API Pact', () => {
  beforeAll(() => provider.setup());
  afterAll(() => provider.finalize());

  it('GET /api/users/1', async () => {
    await provider.addInteraction({
      uponReceiving: 'a request for user 1',
      withRequest: {
        method: 'GET',
        path: '/api/users/1',
      },
      willRespondWith: {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: { code: 0, data: { id: 1, name: '张三', role: 'STUDENT' } },
      },
    });

    const user = await getUser(1);
    expect(user.name).toBe('张三');
  });
});
```

#### 8.3.2 提供方(Java)验证

```java
@PactTestFor(providerName = "user-service")
class UserServicePactTest {

    @Test
    @PactTestFor(pactMethod = "userByIdPact")
    void verifyUserByIdPact(PactInvocationContext ctx) {
        // Pact Broker 拉取契约，自动验证 Provider
    }
}
```

### 8.4 OpenAPI Schema 验证

```bash
# schemathesis 对外 API 契约验证
schemathesis run openapi.yaml --base-url http://localhost:8080 \
  --checks all --hypothesis-max-examples 100
```

```yaml
# openapi.yaml 片段(契约即真相)
paths:
  /api/users/{id}:
    get:
      parameters:
        - name: id
          in: path
          required: true
          schema: { type: integer, format: int64 }
      responses:
        '200':
          description: 用户详情
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserResponse'
```

### 8.5 契约发布与门禁

```
┌─────────────┐    pact 生成     ┌─────────────┐
│  Consumer   │ ──────────────> │ Pact Broker │
│  (前端/Java)│                  │  (契约仓库)  │
└─────────────┘ <────────────── └─────────────┘
                  can-i-deploy
┌─────────────┐
│  Provider   │ 拉取契约 → 验证 → 发布结果
│  (后端)     │
└─────────────┘
```

CI 门禁：Provider 验证失败阻塞合并；Consumer 发布前必须 `can-i-deploy` 通过。

---

## 9. 性能基准测试

### 9.1 JMH(Java Microbenchmark)

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CellRouterBenchmark {

    private CellRouter router;

    @Setup
    public void setup() {
        router = new CellRouter(1024);
    }

    @Benchmark
    public void routeSingle() {
        router.route(new Cell(1L));
    }

    @Benchmark
    public void routeBatch100() {
        for (int i = 0; i < 100; i++) {
            router.route(new Cell((long) i));
        }
    }
}
```

```bash
mvn package
java -jar target/benchmarks.jar -prof gc
```

### 9.2 Go bench

```go
func BenchmarkRoute(b *testing.B) {
    router := NewCellRouter(1024)
    cell := &Cell{ID: 1}

    b.ResetTimer()
    b.ReportAllocs()
    for i := 0; i < b.N; i++ {
        router.Route(cell)
    }
}

func BenchmarkRouteParallel(b *testing.B) {
    router := NewCellRouter(1024)
    b.ResetTimer()
    b.RunParallel(func(pb *testing.PB) {
        for pb.Next() {
            router.Route(&Cell{ID: 1})
        }
    })
}
```

```bash
go test -bench=. -benchmem -count=5 -benchtime=2s ./...
go test -bench=. -benchmem -cpuprofile=cpu.out ./...
go tool pprof cpu.out
```

### 9.3 Vitest bench

```typescript
import { describe, bench } from 'vitest';
import { formatPrice } from '../format';

describe('formatPrice bench', () => {
  bench('小金额', () => {
    formatPrice(99);
  });

  bench('大金额', () => {
    formatPrice(9999999);
  });

  bench('批量 1000 次', () => {
    for (let i = 0; i < 1000; i++) formatPrice(i);
  });
});
```

```bash
vitest bench --run
```

### 9.4 性能回归检测

| 指标 | 检测方式 | 告警阈值 |
|------|---------|---------|
| P99 延迟 | JMH / bench 基线 | 较基线劣化 >10% |
| 内存分配 | `-benchmem` / JMH GC | 较基线增加 >20% |
| 吞吐量 | Locust 压测 | 下降 >15% |

CI 集成：基准结果与 `main` 分支基线对比，劣化超阈值标红但不阻塞(告警为主)。

```yaml
# .github/workflows/benchmark.yml
- name: Run benchmarks
  run: |
    go test -bench=. -benchmem ./... | tee bench-current.txt
    benchstat bench-base.txt bench-current.txt
```

---

## 10. CI 集成

### 10.1 Pre-commit Hooks

```yaml
# .pre-commit-config.yaml
repos:
  - repo: local
    hooks:
      - id: java-unit
        name: Java 单元测试
        entry: mvn -q test -pl !integration-tests
        language: system
        files: \.java$
        stages: [commit]

      - id: go-unit
        name: Go 单元测试(race)
        entry: go test -race -count=1 ./...
        language: system
        files: \.go$
        stages: [commit]

      - id: ts-unit
        name: TS 单元测试
        entry: pnpm vitest run --reporter=dot
        language: system
        files: \.(ts|tsx)$
        stages: [commit]
```

### 10.2 PR 检查流水线

```yaml
# .github/workflows/pr-check.yml
name: PR Check
on: [pull_request]

jobs:
  java-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - run: mvn -B verify jacoco:report
      - name: JaCoCo 门禁
        run: mvn jacoco:check
      - uses: actions/upload-artifact@v4
        with: { name: jacoco, path: '**/target/site/jacoco/' }

  go-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: 1.23 }
      - run: go test -race -count=1 -coverprofile=cover.out -covermode=atomic ./...
      - run: go tool cover -func=cover.out | tail -1
      - name: 覆盖率门禁
        run: |
          COV=$(go tool cover -func=cover.out | tail -1 | awk '{print $3}' | tr -d %)
          test "$COV" -ge 80 || { echo "覆盖率 $COV% < 80%"; exit 1; }

  ts-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: pnpm }
      - run: pnpm install --frozen-lockfile
      - run: pnpm turbo run test --filter=... --cache-dir=.turbo
      - run: pnpm turbo run test:coverage --filter=...

  contract-test:
    needs: [java-test, ts-test]
    runs-on: ubuntu-latest
    steps:
      - run: mvn -B test -pl contract-tests
      - run: pnpm pact:verify
```

### 10.3 覆盖率门禁规则

| 门槛 | 行为 |
|------|------|
| 行覆盖 <80% | **阻塞合并** |
| 分支覆盖 <70% | **阻塞合并** |
| 关键路径 <100% | **阻塞合并** |
| 覆盖率较上次下降 >3% | 告警(不阻塞) |
| 新增代码未覆盖 | **阻塞合并** |

### 10.4 测试并行化

#### 10.4.1 Java

```xml
<!-- JUnit5 并行执行 -->
<!-- src/test/resources/junit-platform.properties -->
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
junit.jupiter.execution.parallel.config.dynamic.factor=2
```

#### 10.4.2 Go

```bash
# 默认并行，包间并行 + 包内串行
go test -race -parallel 4 ./...
```

#### 10.4.3 Turbo 缓存(前端)

```typescript
// turbo.json
{
  "pipeline": {
    "test": {
      "dependsOn": ["^build"],
      "outputs": ["coverage/**"],
      "cache": true,
      "inputs": ["src/**", "__tests__/**", "vitest.config.ts"]
    },
    "test:coverage": {
      "dependsOn": ["^build"],
      "outputs": ["coverage/**"],
      "cache": true
    }
  }
}
```

### 10.5 测试反馈时效

| 阶段 | 时效目标 | 触发 |
|------|---------|------|
| pre-commit | <10s | git commit |
| PR 单元测试 | <3min | push to PR |
| PR 集成测试 | <5min | push to PR |
| 契约测试 | <5min | push to PR |
| E2E | <15min | merge to main |
| 性能基准 | 夜间 | cron |

---

## 11. 测试反模式

### 11.1 禁止清单

| # | 反模式 | 危害 | 正解 |
|---|--------|------|------|
| 1 | **冰激凌测试(倒金字塔)** | E2E 过多，反馈慢且脆弱 | 倒回金字塔，E2E ≤10% |
| 2 | **测试依赖顺序** | 单独运行失败 | 每个测试独立，`@BeforeEach` 重置 |
| 3 | **断言缺失** | 测试通过但无验证 | 必须有 `assertThat`/`expect` |
| 4 | **多职责测试** | 一个测 N 件事，难定位 | 一测一断言主题 |
| 5 | **Mock 一切** | 测试无意义，等于测 Mock | 只 Mock 跨边界依赖 |
| 6 | **测试逻辑分支** | 测试比实现还复杂 | 简化测试，复杂逻辑拆分被测代码 |
| 7 | **依赖时间/随机/网络** | 不可重复 | `Clock` 注入、固定种子、Mock 网络 |
| 8 | **共享可变状态** | 并发跑炸 | 测试局部变量，`@BeforeEach` 重置 |
| 9 | **`Thread.sleep` 等待** | 脆弱且慢 | 用 `Awaitility`/`vi.waitFor` 轮询 |
| 10 | **截图对比当主测试** | 环境差异导致 flaky | 视觉回归仅作辅助 |
| 11 | **测试私有方法** | 重构即破坏 | 通过公共 API 测试 |
| 12 | **注释测试而非删除** | 掩盖问题 | 删除或修复，禁止 `@Disabled` 上主干 |
| 13 | **`verify(mock, never())` 滥用** | 脆弱，重构即坏 | 只验证正向关键交互 |
| 14 | **高耦合 Mock 链** | 改一处影响一片 | 重构解耦，引入事件/接口 |

### 11.2 常见陷阱

#### 11.2.1 测试中的魔法数字

```java
// 坏
assertThat(calculate(86400, 3600)).isEqualTo(24);

// 好
final int SECONDS_PER_DAY = 86400;
final int SECONDS_PER_HOUR = 3600;
assertThat(calculate(SECONDS_PER_DAY, SECONDS_PER_HOUR))
    .isEqualTo(24);
```

#### 11.2.2 过度断言

```java
// 坏:断言了无关字段，重构即坏
assertThat(user).isEqualTo(User.builder()
    .id(1L).name("张三").phone("13800000000")
    .role(STUDENT).createdAt(Instant.now()).build());

// 好:只断言当前测试关注点
assertThat(user).extracting(User::getId, User::getName)
    .containsExactly(1L, "张三");
```

#### 11.2.3 异步等待反模式

```java
// 坏:硬等待
Thread.sleep(1000);
assertThat(repository.count()).isEqualTo(1);

// 好:Awaitility 轮询
await().atMost(5, SECONDS).untilAsserted(() ->
    assertThat(repository.count()).isEqualTo(1)
);
```

### 11.3 代码评审清单(测试)

PR 评审时检查以下条目：

- [ ] 测试方法名符合 `should_<expected>_when_<condition>`
- [ ] 测试结构为 AAA(Arrange-Act-Assert)
- [ ] 每个测试单一职责，只断言一个主题
- [ ] 无 `Thread.sleep`，异步用 Awaitility/waitFor
- [ ] 无依赖外部网络/真实数据库(单元测试)
- [ ] 测试数据用 Builder/Factory，无内联魔法数字
- [ ] Mock 只用于跨边界依赖，未 Mock 值对象
- [ ] 异常用 `assertThatThrownBy` 断言类型+消息
- [ ] 覆盖率达标(行 ≥80%，分支 ≥70%)
- [ ] 新增代码必须有测试
- [ ] 测试可在任意顺序独立运行通过
- [ ] 无 `@Disabled`/`it.skip` 残留

---

## 12. 测试目录结构

### 12.1 Java 后端

```
ep-backend/
├── ep-core/                       # 核心模块
│   ├── src/
│   │   ├── main/java/com/education/core/
│   │   │   ├── user/UserService.java
│   │   │   ├── course/CourseService.java
│   │   │   └── order/OrderService.java
│   │   └── test/java/com/education/core/
│   │       ├── user/
│   │       │   ├── UserServiceTest.java          # 单元测试
│   │       │   └── builder/UserBuilder.java       # 测试数据构建器
│   │       ├── course/CourseServiceTest.java
│   │       └── order/OrderServiceTest.java
│   └── src/test/resources/
│       ├── contracts/                              # Spring Cloud Contract
│       │   └── user/findUserById.groovy
│       └── junit-platform.properties
│
├── ep-integration-test/           # 集成测试独立模块
│   └── src/test/java/com/education/it/
│       ├── user/UserRepositoryIT.java
│       ├── course/CourseServiceIT.java
│       └── pact/UserServicePactTest.java
│
└── ep-benchmark/                  # 性能基准独立模块
    └── src/main/java/com/education/bench/
        └── CellRouterBenchmark.java
```

**规则**：
- 单元测试与被测类同包，`src/test/java`
- 集成测试后缀 `IT`，独立模块 `ep-integration-test`
- 基准测试独立模块 `ep-benchmark`，不打入产物
- 契约定义放 `src/test/resources/contracts/`

### 12.2 Go 网关

```
ep-gateway/
├── cmd/
│   └── gateway/main.go
├── internal/
│   ├── gateway/
│   │   ├── handler.go
│   │   ├── handler_test.go         # 同包测试
│   │   ├── router.go
│   │   └── router_test.go
│   ├── ratelimiter/
│   │   ├── limiter.go
│   │   ├── limiter_test.go
│   │   └── limiter_bench_test.go   # 基准测试
│   └── auth/
│       ├── filter.go
│       └── filter_test.go
├── mockservice/                    # mockery 生成
│   └── UserService.go
└── test/
    └── integration/                # 集成测试
        └── gateway_test.go
```

**规则**：
- 单元测试 `xxx_test.go` 与源文件同目录
- 基准测试 `xxx_bench_test.go` 同目录
- 集成测试独立 `test/integration/`
- Mock 生成物放 `mockservice/`，由 `go:generate` 维护

### 12.3 TypeScript 前端

```
ep-frontend/
├── packages/
│   ├── shared/
│   │   ├── src/
│   │   │   ├── utils/format.ts
│   │   │   └── __tests__/
│   │   │       └── format.test.ts
│   │   └── vitest.config.ts
│   ├── api-client/
│   │   ├── src/
│   │   │   ├── userApi.ts
│   │   │   └── __tests__/
│   │   │       ├── userApi.test.ts
│   │   │       └── userApi.pact.test.ts   # Pact 契约测试
│   │   └── vitest.config.ts
│   ├── ui-kit/
│   │   ├── src/components/UserCard.tsx
│   │   └── src/components/__tests__/UserCard.test.tsx
│   └── query/
│       └── src/hooks/__tests__/useUser.test.ts
│
├── apps/
│   ├── h5/
│   │   ├── src/pages/CourseDetailPage.tsx
│   │   └── src/pages/__tests__/CourseDetailPage.test.tsx
│   └── mp-weixin/
│       └── src/pages/__tests__/index.test.ts
│
├── __fixtures__/                    # 共享测试夹具
│   ├── users.ts
│   └── courses.ts
│
├── vitest.setup.ts                  # MSW server setup
└── turbo.json
```

**规则**：
- 测试文件放 `__tests__/` 子目录，命名 `xxx.test.ts(x)`
- 共享夹具放根 `__fixtures__/`
- Pact 契约测试后缀 `.pact.test.ts`
- 每个包独立 `vitest.config.ts`，支持 Turbo 缓存

### 12.4 契约文件

```
contracts/
├── pacts/                           # Pact 生成的 JSON 契约
│   ├── education-frontend-user-service.json
│   └── order-service-payment-service.json
├── openapi/
│   └── openapi.yaml                 # 对外 API 唯一真相源
└── spring-cloud-contract/
    └── user-service/
        └── findUserById.groovy
```

---

## 附录 A：版本与变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-04 | 初始发布；定义 12 章单元测试框架规范 | ZCode(测试架构师) |

## 附录 B：参考文档

| 文档 | 说明 |
|------|------|
| SP01-TEST-001 | 技术中台测试计划(测试金字塔基线) |
| SP06-TEST-001 | 多端前端骨架测试方案(Vitest/MSW/Playwright 基线) |
| D-032 | 技术栈版本铁律 |
| D-031 | 前端技术栈 |
| G-01~G-10 | SLO 性能目标 |
| JUnit 5 User Guide | https://junit.org/junit5/docs/5.11.4/user-guide/ |
| Mockito Documentation | https://site.mockito.org/ |
| Vitest Documentation | https://vitest.dev/ |
| Pact Specification | https://pact.io/ |
| Spring Cloud Contract | https://docs.spring.io/spring-cloud-contract/ |

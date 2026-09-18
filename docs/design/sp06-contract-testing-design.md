# SP-06 契约测试设计 (Pact File-Based)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp06` for reference continuity. Per D-137, sp06 → portal-bff. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 |
|------|------|------|------|
| SP06-CT-001 | v1.0 | 2026-08-04 | Approved |

## 1. 背景

A-16 审计项: M0 阶段 Pact 契约测试使用文件交换模式, 不部署 Pact Broker。
M2+ 阶段评估升级到 Pact Broker + CI pipeline。

## 2. 文件交换模式

### 2.1 Consumer 端 (前端)

1. Consumer 运行 Pact 测试 → 生成 Pact JSON 文件
2. Pact 文件输出到 `pacts/` 目录
3. CI 将 `pacts/` 目录作为 artifact 上传

### 2.2 Provider 端 (BFF Java)

1. Provider CI 下载 `pacts/` artifact
2. Provider 运行 verification, 读取本地 Pact JSON
3. 验证失败则 CI fail

### 2.3 目录结构

```
frontend/
  pacts/                          # Pact JSON 文件 (gitignored, CI artifact)
    admin-web-bff-java.json
    student-h5-bff-java.json
    student-mp-bff-java.json
  packages/api-client/
    pact.config.ts                # Pact consumer config
    __tests__/contract/           # Consumer pact tests
```

### 2.4 Consumer 配置 (TypeScript)

```typescript
// packages/api-client/pact.config.ts
import { Pact } from '@pact-foundation/pact';

export const pactConfig = {
  consumer: 'admin-web',
  provider: 'bff-java',
  dir: '../../pacts',           // 文件输出目录
  log: '../../logs/pact.log',
  logLevel: 'warn',
  spec: 2,                       // Pact spec v2
};
```

### 2.5 Provider 验证 (Java)

```java
// bff-java: PactVerifierIT.java
@PactFolder("pacts")  // 从本地 pacts/ 目录读取
@Provider("bff-java")
@VerificationMocks(value = {
  @VerificationMock(consumer = "admin-web"),
  @VerificationMock(consumer = "student-h5"),
  @VerificationMock(consumer = "student-mp")
})
class PactVerifierIT {
  @Test
  void verifyPacts() { ... }
}
```

### 2.6 CI Pipeline

```yaml
# .github/workflows/contract-test.yml
jobs:
  consumer-test:
    runs-on: ubuntu-latest
    steps:
      - run: pnpm test:contract
      - uses: actions/upload-artifact@v4
        with:
          name: pacts
          path: frontend/pacts/
  
  provider-verify:
    needs: consumer-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
        with:
          name: pacts
          path: services/bff-java/pacts/
      - run: mvn verify -Ppact-verify
```

## 3. 升级路径 (M2+)

M2+ 评估升级到 Pact Broker:
- Pact Broker Docker 部署在集群内 (PIPL合规, 数据不出集群)
- can-i-deploy check before deployment
- 自动 contract version tagging
- Pact Broker 高可用 (PostgreSQL backend)

## 4. 追溯

| 审计项 | 状态 | 方案 |
|--------|------|------|
| A-16 | 已覆盖 | M0 文件交换模式, M2+ 评估 Pact Broker |

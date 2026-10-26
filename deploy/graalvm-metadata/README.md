# GraalVM Native-Image 元数据（参考制品，D-236 归位）

> **性质**：本目录的 `native-image.properties` / `reflection-meta.json` / `resources-meta.json` 是 D-199「GraalVM AOT 门禁」时期创建的元数据**参考制品**。v7 复审（D-236）核实：这些文件历史上被放在多模块父 pom 的顶层 `src/main/resources/` 下——**该位置不在任何 Maven 模块的打包路径内，从未被任何构建管线消费过**（native-image agent 从各模块自身 jar 的 `META-INF/native-image/` 读取，父 pom 顶层 src 不产生 jar）。

## 为什么归位到 deploy/

按铁律 9（运行验证真相律）与「配置存在 ≠ 能力生效」的教训（D-223 同型教训）：无效配置不应伪装成有效能力。归位到 `deploy/graalvm-metadata/` 明确其**参考制品**性质，并从 services/ 目录树移除（消除"父级顶层 src"的结构性歧义，v7 评分卡 D5 残留项之一）。

## 如果未来启用 AOT 构建的正确做法

1. 将对应服务的元数据复制到**该模块自己的** `src/main/resources/META-INF/native-image/com.ep/<module>/`
2. 跑通 `./scripts/check-native-ready.sh`（Iron Law 7 执行体）
3. 在 CI 的 AOT 门禁 workflow 中验证 `native:compile` 真实成功（铁律 9）

## 来源映射

| 服务目录 | 原位置（已移除） |
|---|---|
| `knowledge-platform/` | `services/knowledge-platform/src/main/resources/META-INF/native-image/com.ep/knowledge-platform/` |
| `order-platform/` | `services/order-platform/src/main/resources/META-INF/native-image/com.ep/order-platform/` |
| `payment-platform/` | `services/payment-platform/src/main/resources/META-INF/native-image/com.ep/payment-platform/` |

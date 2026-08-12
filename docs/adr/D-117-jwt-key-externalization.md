# D-117: JWT 密钥外化 — K8s SealedSecret + 环境变量文件路径

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-117 | v1.0 | 2026-08-09 | 已批准 | 安全 + DevOps 组 | 架构委员会 |

> 关联：ADR-A02（RS256 签名）、D-035（K8s 部署运维铁律）、D-114（GitOps/ArgoCD）、铁律 1（会话状态外化）、铁律 8（决策对比分析 ≥3 方案）、审计项 A-22（密钥生成脚本）。

## 1. 问题陈述与约束条件

仓库根目录历史提交了 JWT RS256 私钥 / 公钥与 env 文件：

- `jwt-rs256-private.pem`（PKCS#8 私钥，明文）
- `jwt-rs256-public.pem`
- `jwt-rs256.env`（单行转义 PEM，被 BFF 与 gateway-go 直接 `os.Getenv` / `@Value` 读取）
- `jwt-rs256-private-bff-running.pem`、`jwt-rs256-public-bff-running.pem`（运行态快照）

**风险等级：严重。** 任何拥有仓库读权限者（含离职成员、fork、CI 日志泄露）即可伪造任意用户/角色的 JWT，绕过 `AuthFilter` 与 gateway-go WS 鉴权。即便删除文件，Git 历史仍可回溯——必须假设私钥已泄露并轮换。

约束条件：

1. **零停机迁移**：bff-java（3 副本）与 gateway-go（3 副本）滚动更新期间不能因密钥缺失而启动失败。
2. **dev 兼容**：本地 `mvn spring-boot:run -Dspring-boot.run.profiles=dev-local` 仍可用 `jwt-rs256.env`（由 `scripts/gen-rs256-keys.sh` 生成，不入库）。
3. **GitOps 友好**：所有密钥以可入库形式（SealedSecret）提交，由 ArgoCD 同步，无人工 `kubectl create secret`。
4. **多语言对称**：bff-java（Java 21 + Spring）与 gateway-go（Go）必须用同一密钥来源与命名。
5. **铁律 8**：≥3 方案对比后定论。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Sealed Secrets**（已选） | 用集群内 Sealed Secrets Controller 私钥加密，密文入库 GitOps，仅目标命名空间可解密 |
| B | **External Secrets + Vault** | External Secrets Operator 从 HashiCorp Vault / 云 SM 拉取，Git 只存 ExternalService 引用 |
| C | **Cloud KMS（AWS KMS / GCP KMS）** | 私钥永不出 KMS，应用调用 KMS Sign/Verify API，仓库只存 keyId |

## 3. 对比维度表

| 维度 | A. Sealed Secrets（已选） | B. ESO + Vault | C. Cloud KMS |
|------|---------------------------|----------------|--------------|
| **加密位置** | 集群内 Controller 私钥（对称加密） | 外部 Vault / 云 SM | 云 KMS（HSM） |
| **GitOps 适配** | 强——密文 YAML 直接入库，ArgoCD 同步即解密 | 中——Git 存 ExternalSecret 引用，需 ESO controller 在线 | 弱——Git 仅存 keyId，密钥状态完全在云侧 |
| **离线/本地开发** | 强——kubeseal 可本地加密，dev 用 .env 兜底 | 弱——本地需起 Vault dev server | 不可行——本地无 KMS |
| **运维复杂度** | 低——单一 controller，无额外基础设施 | 高——需部署 Vault + ESO + 凭据轮换 | 中——需云 IAM 权限与 KMS 策略 |
| **跨云/多云（D-041 多活）** | 中——每集群独立 Controller，密文不通用 | 强——Vault 集群可跨区复制 | 弱——KMS 区域绑定，跨云需同步 |
| **密钥泄露面** | Controller 私钥泄露即全仓库密文可解 | Vault token 泄露面，但可短期 + 审计 | 私钥永不出 HSM，泄露面最小 |
| **签名性能** | 应用内本地 RS256（μs 级） | 应用内本地 RS256 | 每次签名一次 KMS API 调用（ms 级 + 网络延迟） |
| **成本** | 0（开源 controller） | Vault license / 自建运维 | KMS API 按调用计费 |
| **与现有栈集成** | 原生——SealedSecret CRD 已是 GitOps 常见组件 | 需引入 ESO + Vault 两套组件 | 需引入云 SDK + IAM 信任 |

## 4. 决策

**选方案 A：Sealed Secrets。**

理由：

1. 教育平台已采用 ArgoCD GitOps（D-114），SealedSecret 是 GitOps + K8s 原生的密钥入库方案，密文 YAML 与其它清单一同由 ArgoCD 同步，无需额外基础设施。
2. 方案 B（Vault）虽审计更强，但引入 Vault 集群 + ESO + 凭据轮换的运维成本，与当前团队规模不匹配；可在后续安全等级提升时再迁移。
3. 方案 C（Cloud KMS）每次签名一次 API 调用，对 BFF 高 QPS 鉴权场景引入不可控延迟，且当前部署尚未绑定单一云厂商（D-041 多活跨区），KMS 区域绑定会加剧复杂度。

### 4.1 实现清单

| 变更 | 文件 |
|------|------|
| JwtService 改为 env-var / 文件路径加载，base64 内联兜底 | `services/bff-java/src/main/java/com/ep/bff/auth/JwtService.java` |
| JwtService 新增 D-117 加载路径测试（temp file / base64 / Spring 兜底 / 缺失报错） | `services/bff-java/src/test/java/com/ep/bff/auth/JwtServiceTest.java` |
| application.yml 注释说明密钥来自 K8s Secret | `services/bff-java/src/main/resources/application.yml` |
| gateway-go main.go 改用 `jwt.LoadPublicKeyPEM()` | `services/gateway-go/src/main.go` |
| ep-go-common/jwt 新增 `LoadPublicKeyPEM` / `LoadPrivateKeyPEM` + 测试 | `services/ep-go-common/jwt/validator.go`、`validator_test.go` |
| SealedSecret 清单（占位密文 + kubeseal 生成步骤） | `deploy/gitops/sealed-secrets/jwt-sealedsecret.yaml` |
| bff-java Deployment 挂载 Secret + env | `deploy/k8s/base/bff-java.yaml` |
| gateway-go Deployment 挂载 Secret + env | `deploy/k8s/base/gateway-go.yaml` |
| .gitignore 加 `*.pem` `*.key` `jwt-*` `*.env`，白名单 `jwt-keys.example.env` | `.gitignore` |
| 删除入库密钥：`jwt-rs256-private.pem` / `-public.pem` / `.env` / `-bff-running.pem` ×2 | 仓库根 |
| 占位 env 模板 | `jwt-keys.example.env` |
| 本 ADR | `docs/adr/D-117-jwt-key-externalization.md` |

### 4.2 密钥加载优先级（bff-java 与 gateway-go 对称）

1. `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` 指向的文件（生产默认 `/etc/jwt/*.pem`，K8s Secret 挂载点）
2. `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` base64 编码的内联值（CI / 无文件系统场景）
3. `JWT_RSA_PRIVATE_KEY_PEM` / `JWT_RSA_PUBLIC_KEY_PEM` 单行转义 PEM（dev 向后兼容，`jwt-rs256.env`）
4. 全部缺失 → 抛 `IllegalStateException`（Java）/ error（Go），**绝不静默失败**

### 4.3 迁移路径（运维执行清单）

1. **生成新密钥**（仅一次，私钥永不入库）：
   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-rs256-private.pem
   openssl rsa -in jwt-rs256-private.pem -pubout -out jwt-rs256-public.pem
   ```
2. **构造临时 Secret 清单并用 kubeseal 加密**（见 `deploy/gitops/sealed-secrets/jwt-sealedsecret.yaml` 顶部步骤）。
3. **提交 SealedSecret**，ArgoCD 同步后 Controller 解密为 `Secret/jwt-keys`（namespace `education-platform`）。
4. **滚动更新 bff-java / gateway-go**：Deployment 新增 `volumeMounts /etc/jwt` + `env JWT_*_KEY_PATH`，K8s 自动滚动重启，新 Pod 从 Secret 读密钥。
5. **销毁本地私钥**：`shred -u jwt-rs256-private.pem jwt-rs256-public.pem`。
6. **轮换 Git 历史**：本 ADR 假定旧私钥已泄露。如需彻底清除 Git 历史，另起 `git filter-repo` 任务（本决策不强制，因 SealedSecret 已替换密钥，旧密钥失效）。

## 5. 后果

**正面：**

- 私钥不再入库，仓库读权限不再等于 JWT 伪造能力，风险从严重降至中（仍需 Controller 私钥保护）。
- GitOps 流程统一：密钥与其它清单一同由 ArgoCD 同步，无人工 `kubectl`。
- dev / prod 加载路径对称，本地仍可用 `jwt-rs256.env`。

**负面 / 风险：**

- **Sealed Secrets Controller 私钥是新的单点**：Controller 私钥泄露则所有 SealedSecret 可解。缓解：Controller 私钥本身用 KMS / 云 KMS 加密备份（后续可演进为方案 B）。
- **密钥轮换需手动**：当前 `rotation-policy: manual-rotate-on-incident`。后续可引入 External Secrets 自动轮换。
- **Git 历史仍含旧密钥**：本决策不强制重写历史（成本高、协作风险大），靠轮换密钥使旧密钥失效。

**后续演进：**

- 当安全等级要求提升或团队引入 Vault 时，迁移至方案 B（ESO + Vault），本 ADR 的 env-var 抽象层无需改动（仅 Secret 来源切换）。

# local-verify overlay

本地验证 overlay，用于在 **Rancher Desktop 单节点 k3s** 集群上验证
education-platform 的 K8s YAML 可部署性（语法、调度、Service 路由）。

> 此 overlay 仅用于本地验证，**不是生产部署**。所有业务镜像被替换为
> `nginx:1.27-alpine` 占位镜像，Pod 内不会运行真实业务逻辑。

## 适配项

| 问题 | 处理方式 |
|------|----------|
| base Deployment 引用 `harbor.edu.internal/...` 内部仓库（不存在） | 用 `nginx:1.27-alpine` 占位镜像替换全部 Deployment 镜像 |
| `topologySpreadConstraints` 用 `DoNotSchedule`，单节点无法满足 | 移除该字段 |
| `envFrom` 引用 `common-config`/`dubbo-config` 中指向不存在服务的 DNS | 移除 `envFrom`（保留 ConfigMap 本身，用于验证 configmap 可创建） |
| readiness/liveness/startupProbe 路径是 `/actuator/health/...`，端口是业务端口（如 8080） | 路径改为 `/`、端口改为 `80`（nginx 默认监听 80 返回 200） |
| `ai-platform` 用 `granian` 命令启动（nginx 镜像无此可执行文件） | 移除 `command`/`args`，让 nginx 用默认 ENTRYPOINT |
| `otel-collector` 健康探针端口 13133 在 nginx 中未监听 | 探针端口改到 80，并移除 otel 自定义 command/args |
| base 端口名 `metrics-internal`（16 字符）超过 k8s 15 字符上限，k3s 拒绝 | 缩短为 `metrics-int`（Deployment 端口名 + Service targetPort 同步改） |
| `ServiceMonitor`（`monitoring.coreos.com/v1`）CRD 未安装 | apply 时报错被脚本忽略（见下） |
| `PropagationPolicy`/`OverridePolicy`（Karmada）CRD 未安装 | 同上，apply 时报错被忽略 |
| base HPA `minReplicas` 为 2-3，强制多副本，单节点 CPU 不足导致 Pending | HPA `minReplicas` 全部设为 1 |
| base Deployment 资源 requests 过大（500m-1000m CPU），单节点 9 核已 95% 占用 | requests/limits 缩小为 100m/500m CPU、64Mi/256Mi 内存 |
| `dubbo-config` 在 base/ 中存在但被引用 | 已包含，仅合并覆盖地址为本地值 |
| ConfigMap 中 `nacos.edu.svc` 等集群内 DNS | 合并覆盖为 `127.0.0.1` |

## 文件

- `kustomization.yaml` — overlay 主配置（patches + configMapGenerator）
- `README.md` — 本文档

## 使用方法

### 一键验证（推荐）

```bash
bash deploy/scripts/k3s-verify.sh
```

该脚本会依次执行：kustomize 渲染 -> apply（忽略 CRD 错误）-> 等待 Pod 就绪 ->
输出部署结果。脚本末尾会打印清理命令。

### 手动执行

```bash
# 1. 仅渲染查看
kubectl kustomize deploy/k8s/overlays/local-verify/

# 2. 应用
kubectl apply -k deploy/k8s/overlays/local-verify/
# ServiceMonitor / PropagationPolicy 会报 CRD 缺失错误, 可忽略

# 3. 查看
kubectl get all -n education-platform

# 4. 清理
kubectl delete -k deploy/k8s/overlays/local-verify/
```

## 预期结果

- 6 个 Deployment 各 1 副本，全部 Running（nginx 占位镜像）：
  `edu-portal-bff`、`edu-ws-gateway`、`edu-core-business`、`edu-ai-platform`、
  `edu-grading-service`、`edu-otel-collector`
- 对应 6 个 Service
- ConfigMap：`edu-common-config`、`edu-dubbo-config`、`edu-otel-collector-config`
- 5 个 HPA（minReplicas=1）、6 个 PDB、NetworkPolicy、Ingress
- `ServiceMonitor` / `PropagationPolicy` / `OverridePolicy` 因 CRD 缺失而跳过（预期）

实际验证结果（Rancher Desktop k3s v1.34.6，单节点 lima-rancher-desktop）：

```
NAME                                 READY   STATUS    RESTARTS   AGE
edu-portal-bff-7df95b9849-h2rp4         1/1     Running   0          94s
edu-ws-gateway-58c7c8dc7-wcnds        1/1     Running   0          94s
edu-otel-collector-6f7b9b9d4d-lg2kx   1/1     Running   0          94s
edu-core-business-799c44dd4f-vrf5t    1/1     Running   0          94s
edu-ai-platform-8469b86d59-t5jxd          1/1     Running   0          93s
edu-grading-service-5bdc7945d-5fnp9    1/1     Running   0          93s
```

6 个 Pod 全部 1/1 Running，无重启。

## 约束

- 不修改 `deploy/k8s/base/` 下任何文件，所有适配通过 overlay 完成
- 不影响 `ekb-m2-*` / `esp-prod` / `esp-observability` 等共享基础设施命名空间
- `namePrefix: edu-` 来自 base，所有资源名带 `edu-` 前缀

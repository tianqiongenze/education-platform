# 局域网测试操作手册 (LAN Test Runbook)

> **Iron Law 9 合规文档** — 本手册指导如何在局域网内对教育平台进行人工测试。
> **最后更新**: 2026-08-10 | **验证状态**: ✅ 13/13 服务 Running（nginx 占位）

---

## 1. 环境概览

| 组件 | 值 | 说明 |
|------|-----|------|
| K8s 集群 | Rancher Desktop k3s | 节点 `lima-rancher-desktop`, IP `192.168.5.15` |
| 命名空间 | `education-platform` | 13 个业务服务 |
| Ingress | traefik | `192.168.5.15:80` |
| 基础设施 | 共享命名空间 `ekb-m2-aws-us-east-1` | TiDB/Redis/Pulsar/Milvus（只读访问） |

## 2. 当前部署状态（D-142 验证）

### 2.1 Pod 状态

```
13/13 Pod Running + Ready (1/1)
```

| 服务 | 端口 | Pod IP | 镜像 |
|------|------|--------|------|
| bff-java | 8090 | 10.42.0.216 | nginx:1.27-alpine（占位） |
| gateway-go | 8081 | 10.42.0.217 | nginx:1.27-alpine |
| sp02-data | 8082 | 10.42.0.204 | nginx:1.27-alpine |
| sp03-business | 8083 | 10.42.0.98 | nginx:1.27-alpine |
| sp03-order | 8084 | 10.42.0.227 | nginx:1.27-alpine |
| sp03-payment | 8087 | 10.42.0.226 | nginx:1.27-alpine |
| sp05-itbackend | 8085 | 10.42.0.97 | nginx:1.27-alpine |
| sp04-ai | 8086 | 10.42.0.205 | nginx:1.27-alpine |
| sp07-knowledge | 8071 | 10.42.0.209 | nginx:1.27-alpine |
| sp07-bff | 8070 | 10.42.0.208 | nginx:1.27-alpine |
| sp07-retrieval | 8072 | 10.42.0.210 | nginx:1.27-alpine |
| sp07-agent | 8073 | 10.42.0.207 | nginx:1.27-alpine |
| sp07-tenant | 8074 | 10.42.0.211 | nginx:1.27-alpine |

> ⚠️ **Iron Law 9 诚实声明**: 当前所有 Pod 运行的是 `nginx:1.27-alpine` 占位镜像，**不包含真实业务逻辑**。
> 这验证了 K8s 部署 YAML 的正确性（调度/网络/Ingress/Service 全通），但**不等同于业务功能可用**。
> 要进行真实功能测试，需要构建真实镜像替换占位镜像（见 §4）。

### 2.2 Ingress 路由

| 域名 | 路径 | 后端 Service | 说明 |
|------|------|-------------|------|
| `www.edu.com` | `/` | frontend-portal:80 | 前端门户（当前 scaled to 0） |
| `api.edu.com` | `/` | bff-java:80 | BFF API 网关 |
| `api.edu.com` | `/ws` | gateway-go:8081 | WebSocket 网关 |

### 2.3 访问方式

在测试机的 `/etc/hosts` 添加:
```
192.168.5.15  api.edu.com www.edu.com
```

然后通过 `curl http://api.edu.com/` 访问 BFF。

## 3. 快速验证命令

```bash
# 1. 检查所有 Pod
kubectl get pods -n education-platform

# 2. 检查 Service
kubectl get svc -n education-platform | grep edu-

# 3. 检查 Ingress
kubectl get ingress -n education-platform

# 4. Health check（从集群内）
kubectl exec -n education-platform edu-bff-java -- wget -qO- http://edu-sp03-business/actuator/health

# 5. Port-forward 到本机直连
kubectl port-forward -n education-platform svc/edu-bff-java 8090:80
curl http://localhost:8090/actuator/health
```

## 4. 构建真实镜像（替换 nginx 占位）

> 当前状态: **未执行**（harbor.edu.internal 不可达，需用本地 registry）

```bash
# 1. 启动本地 registry
docker run -d -p 5000:5000 --name registry registry:2

# 2. 构建镜像（需要 Java 21 / Go / Python 工具链）
REGISTRY=localhost:5000/education-platform ./deploy/build/build-all-images.sh

# 3. 导入 k3s（k3s 使用 containerd）
docker save localhost:5000/education-platform/bff-java:latest | k3s ctr images import -

# 4. 修改 local-verify overlay，将 nginx 替换为真实镜像
# 编辑 deploy/k8s/overlays/local-verify/kustomization.yaml
# 将 value: nginx:1.27-alpine 改为真实镜像名

# 5. 重新部署
kubectl apply -k deploy/k8s/overlays/local-verify/
```

## 5. 基础设施 port-forward

```bash
# 启动 port-forward（连共享集群基础设施，只读）
./scripts/port-forward-infra.sh start

# 转发端口:
# TiDB:    localhost:4000
# Redis:   localhost:6379
# Nacos:   localhost:8848
# Seata:   localhost:7091
# Milvus:  localhost:19530
# Pulsar:  localhost:6650
```

## 6. 已知限制

1. **nginx 占位**: 当前 Pod 不含业务逻辑，health check 返回 nginx 默认页（非 actuator/health）
2. **frontend-portal scaled to 0**: harbor 镜像不可达，已临时关闭
3. **CPU 资源紧张**: 单节点 k3s 9 核 CPU，13 服务已占 96%
4. **jwt-keys Secret**: 使用 dummy RSA 密钥（非生产密钥）
5. **CRD 缺失**: ServiceMonitor/TidbCluster/PropagationPolicy 等 CRD 未安装，apply 时会警告但不影响核心服务

## 7. 后续步骤

1. 构建真实镜像替换 nginx 占位（§4）
2. 补齐 sp03-business 的 Chapter/Lesson/Enrollment 实体（教育基座）
3. 实现 gateway-go WebSocket handler（实时通信底座）
4. 部署康养养老事业群（sp08-elder）
5. 部署棋牌教育+对弈事业群（sp09-boardgame）

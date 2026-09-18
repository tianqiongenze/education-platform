# SP-05 IT 后端 MVP 软件详细设计文档（SDD）

> **D-200 Naming Note:** This document's filename retains the legacy code `sp05` for reference continuity. Per D-137, sp05 → grading-service. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-05-SDD | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-05-SRS v1.0 + SP-05-SAD v1.0 + ADR-I01..I05 |

> 本 SDD 兑现深度：4 子平台详细设计（聚合/状态机/表结构 SQL DDL/算法伪代码/接口）、关键时序图（ASCII）。承接 SAD ADR-I01..I05，细化到可编码程度。

---

## 1. 引言

### 1.1 目的
本文档定义 SP-05 IT 后端 MVP 4 子平台的详细设计，作为编码与测试的上游输入。包含：聚合/状态机、表结构 SQL DDL、算法伪代码、接口契约、关键时序图。

### 1.2 范围
- 直播：LiveRoom 聚合/状态机、SRS origin 路由算法、LiveKit SFU 集成接口、弹幕削峰 pipeline
- 在线编程：IDE 会话管理、WebContainer 集成、Firecracker SandboxWarmPool CRD 设计、judge0 队列设计、Yjs 协作架构
- 作业：Assignment/Submission/Grade/Regrade 表结构+状态机、MinHash+LSH 查重算法伪代码、Dolos/JPlag 集成
- 评分：分层 pipeline 设计、LLM rubric CoT prompt 设计、self-consistency k 采样实现、成绩聚合算法
- 关键时序图：作业提交→评测→出分全链路、直播推流→观看全链路

### 1.3 依据
- SP-05-SRS v1.0、SP-05-SAD v1.0
- ADR-I01..I05
- 顶层蓝图 EP-SPEC-001 §7

---

## 2. 直播平台 Live 详细设计

### 2.1 LiveRoom 聚合与状态机

```
LiveRoom 聚合根(Domain-Driven Design):
  - roomId: String (一致性哈希 room→cell)
  - courseId: String
  - teacherId: String
  - title: String
  - mode: Enum {INTERACTIVE, BROADCAST}  // 互动 WebRTC / 大众 LL-HLS
  - status: Enum {CREATED, STREAMING, PAUSED, ENDED, ARCHIVED}
  - ingestUrl: String   // WHIP/RTMP/SRT 推流地址
  - playUrl: String     // WebRTC(LL-HLS) 播放地址
  - sfuRoomId: String   // LiveKit SFU 房间 ID(互动模式)
  - recordUri: String   // 对象存储录制 URI
  - viewerLimit: Int    // 互动模式硬上限(几百)
  - startedAt: Timestamp
  - endedAt: Timestamp
```

状态机：
```
                create()
  [初始] ─────────────────► CREATED
                              │
                              │ startStream()(SRS 收到推流)
                              ▼
                          STREAMING ◄────── resume() ──────┐
                              │                               │
                              │ pause()                       │
                              ▼                               │
                           PAUSED ───────────► STREAMING ─────┘
                              │
                              │ endStream()
                              ▼
                           ENDED
                              │
                              │ archive()(录制处理完成)
                              ▼
                          ARCHIVED
```

非法迁移直接拒绝并返回 `INVALID_TRANSITION`。

### 2.2 SRS origin 路由算法

SRS origin cluster 无状态，靠 Redis 路由流定位：

```
算法: routeStream(roomId, viewerCell)
输入: roomId, 观众所在 cell
输出: origin 节点地址

1. originNode = Redis.GET("stream:" + roomId)
2. IF originNode != nil:
2.1    RETURN originNode              // 流已在某 origin, 复用
3. // 流不存在, 选负载最低的 origin
4. candidates = K8s.GetServiceEndpoints("srs-origin")
5. minLoad = MAX_INT; target = nil
6. FOR node IN candidates:
6.1    load = Redis.GET("origin:load:" + node)  // 当前流数
6.2    IF load < minLoad: minLoad = load; target = node
7. Redis.SETNX("stream:" + roomId, target)      // 抢占(SETNX 原子)
8. Redis.INCR("origin:load:" + target)
9. RETURN target

注: SRS origin 之间走 origin cluster 协议(无状态代理),
    边缘 edge 从任意 origin 拉流, 不缓存 HLS(前叠 Squid/ATS)
```

### 2.3 LiveKit SFU 集成接口

```
接口: LiveKitSFUIntegration(Java 房间管理 → LiveKit SFU)

createRoom(roomId, viewerLimit):
  POST LiveKit /twirp/livekit.RoomService/CreateRoom
  body: {name: roomId, empty_timeout: 300s, max_participants: viewerLimit}
  return: sfuRoomId

addParticipant(userId, roomId, role):
  POST LiveKit /twirp/livekit.RoomService/CreateToken
  body: {room: roomId, identity: userId, grants: {canPublish: role==TEACHER|COHOST, canSubscribe: true}}
  return: jwtToken  // 客户端用 token 连 SFU

removeParticipant(userId, roomId):
  POST LiveKit /twirp/livekit.RoomService/RemoveParticipant
  body: {room: roomId, identity: userId}

muteParticipant(userId, roomId, muted):
  POST LiveKit /twirp/livekit.RoomService/MutePublishedTrack
  body: {room: roomId, identity: userId, track_sid: "audio", muted}

观众超限降级:
  IF room.viewerCount > viewerLimit:
      新观众 playUrl = LL-HLS CDN 地址(不走 SFU)
```

### 2.4 弹幕削峰 pipeline

```
弹幕消息流:
  学员端 → WS 网关(Go) → Redis(会话路由 fnv1a(session)%N)
         → Pulsar topic "live.danmaku.{roomId}" (削峰缓冲)
         → 弹幕消费服务(Java) → 广播到房间所有 WS 连接

削峰配置:
  - WS 网关: 单节点 50 万连接, 集群百万级
  - Redis: 会话路由表 session:{userId} → wsNode
  - Pulsar: topic 分区按 roomId 哈希, 消费者组按分区扩
  - 消费端: 聚合同房间消息 100ms 窗口批量广播(降消息数)
  - 背压: 消费 lag > 5s 触发限流(新弹幕排队, 旧弹幕丢弃)

伪代码: 弹幕广播
  function onDanmaku(msg):
      Pulsar.send("live.danmaku." + msg.roomId, msg)
  function consume():
      batches = Pulsar.poll("live.danmaku.*", window=100ms)
      grouped = groupBy(batches, roomId)
      FOR room, msgs IN grouped:
          wsNodes = Redis.SMEMBERS("room:ws:" + room)
          FOR node IN wsNodes:
              node.broadcast(room, msgs)  // 批量发送
```

---

## 3. 在线编程 IDE 详细设计

### 3.1 IDE 会话管理

```
IDESession 聚合:
  - sessionId: String
  - userId: String
  - courseId: String
  - assignmentId: String (可选)
  - mode: Enum {TEACHING, JUDGE}  // 教学 WebContainer / 评测 Firecracker
  - language: Enum {JAVA, PYTHON, GO, JS, CPP}
  - workspaceUri: String  // 对象存储工作区快照
  - yjsDocId: String      // Yjs CRDT 文档 ID(协作)
  - status: Enum {ACTIVE, IDLE, CLOSED}
  - createdAt: Timestamp
  - lastActiveAt: Timestamp

会话生命周期:
  openSession() → ACTIVE
  idle > 30min → IDLE(WebContainer 卸载, 快照存对象存储)
  resume() → ACTIVE(从快照恢复)
  close() → CLOSED(资源释放, 快照保留 7 天)
```

### 3.2 WebContainer 集成（教学 IDE）

```
教学 IDE 后端(轻量, 仅协调, 算力在客户端):
  - 前端加载 WebContainer(StackBlitz WASM 运行时)
  - 工作区文件树存对象存储, 启动时拉取
  - Yjs CRDT 文档同步多端编辑
  - 运行: 客户端 WebContainer 内执行(WASM, 毫秒级)
  - 后端不执行代码, 仅存快照+协调协作

接口:
  openTeachingSession(userId, courseId, assignmentId):
      workspace = ObjectStorage.restore("workspace:" + userId + ":" + assignmentId)
      yjsDoc = YjsServer.createDoc("ide:" + userId + ":" + assignmentId)
      return {sessionId, workspace, yjsDocId, webcontainerConfig}

  saveSnapshot(sessionId):
      snapshot = WSGateway.getLatestDoc(sessionId)  // Yjs 文档状态
      ObjectStorage.put("workspace:" + userId + ":" + assignmentId, snapshot)
```

### 3.3 Firecracker SandboxWarmPool CRD 设计

```yaml
# SandboxWarmPool CRD: 预热 Firecracker microVM snapshot 池
apiVersion: ide.itbackend/v1
kind: SandboxWarmPool
metadata:
  name: judge-warm-pool
  namespace: it-backend
spec:
  # 每语言维护预热 snapshot
  languages:
    - language: JAVA
      image: firecracker-jdk21
      snapshotCount: 20        # 预热 20 个 snapshot
      memMB: 512
      cpuCount: 1
    - language: PYTHON
      image: firecracker-python3.12
      snapshotCount: 20
      memMB: 256
      cpuCount: 1
    - language: GO
      image: firecracker-go1.22
      snapshotCount: 10
      memMB: 256
      cpuCount: 1
    - language: CPP
      image: firecracker-gcc13
      snapshotCount: 10
      memMB: 512
      cpuCount: 1
  # 预热策略
  warmupStrategy:
    minReady: 5                # 最少保持 5 个就绪
    replenishThreshold: 50%    # 低于 50% 补充
  # 资源限制(每个 microVM)
  limits:
    cpuMicros: 1000000         # 1 vCPU
    memMB: 512
    netEgressBps: 0            # 禁网络出口(除非作业需联网)
    timeoutSec: 30             # 执行超时
status:
  readyCounts:
    JAVA: 18
    PYTHON: 19
    GO: 9
    CPP: 9
```

CRD 控制器逻辑（伪代码）：
```
reconcile():
  FOR lang IN spec.languages:
      ready = status.readyCounts[lang]
      target = lang.snapshotCount
      IF ready < target * warmupStrategy.replenishThreshold:
          need = target - ready
          PARALLEL i IN 1..need:
              vm = createMicroVM(lang.image, lang.memMB, lang.cpuCount)
              snapshot = takeSnapshot(vm)        // 创建 snapshot
              pool.enqueue(lang, snapshot)
              status.readyCounts[lang]++
```

### 3.4 judge0 队列设计

```
judge0 集成(REST + Redis worker 队列):
  - 提交: POST /submissions?wait=false → 异步, 返回 submissionId
  - 查询: GET /submissions/{token} → 轮询/Webhook
  - worker: Redis BRPOP 队列, 取任务跑 Firecracker

Firecracker 后端(judge0 custom runner):
  function judge0Worker():
      WHILE true:
          task = Redis.BRPOP("judge0:queue", timeout=5s)
          IF task:
              lang = task.language
              snapshot = pool.dequeue(lang)       // 从预热池取 snapshot
              vm = restoreFromSnapshot(snapshot)  // 5-30ms
              execResult = vm.run(task.code, task.stdin, task.expected)
              pool.replenish(lang)                // 异步补充
              Redis.SET("judge0:result:" + task.token, execResult)
              Webhook.notify(task.callback, execResult)

资源隔离:
  - 每 microVM: cpu 1 vCPU, mem 256-512MB, net 禁出口, timeout 30s
  - 千级 microVM/宿主(K8s 节点)
  - GPU(ML 作业): VFIO 穿透
```

### 3.5 Yjs 协作架构

```
Yjs CRDT 协作编辑:
  - 前端: Monaco editor + y-monaco 绑定
  - 后端: y-websocket server(技术中台 WS 网关复用)
  - 文档: Yjs Doc = CRDT, 多端并发编辑自动合并(无冲突)
  - 持久化: Yjs Update 事件 → 对象存储增量快照

架构:
  学员A(Monaco) ─┐
  学员B(Monaco) ─┼─► y-websocket server(Go) ─► Yjs Doc ─► 对象存储快照
  教师(Monaco)  ─┘

  - 离线优先: 断网时本地 CRDT 继续, 重连后自动 merge
  - p2p 结对编程: 可选 WebRTC 直连(不经服务器)
  - 权限: 教师可锁定文件/只读模式
```

---

## 4. 作业平台 Assignment 详细设计

### 4.1 表结构 SQL DDL（TiDB，user_id 分表）

```sql
-- 作业定义（全局域共享，按 course_id）
CREATE TABLE assignment (
    id              BIGINT PRIMARY KEY AUTO_RANDOM(5),
    course_id       BIGINT NOT NULL,
    teacher_id      BIGINT NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    assignment_type ENUM('CODING','ESSAY','PROJECT','EXAM') NOT NULL,
    language        ENUM('JAVA','PYTHON','GO','JS','CPP','TEXT') NOT NULL,
    max_score       INT NOT NULL DEFAULT 100,
    due_at          TIMESTAMP NOT NULL,
    grace_period_min INT NOT NULL DEFAULT 0,        -- 宽限期(分钟)
    allow_late      TINYINT NOT NULL DEFAULT 1,
    max_attempts    INT NOT NULL DEFAULT 3,         -- multi-attempt 上限
    plagiarism_check TINYINT NOT NULL DEFAULT 1,    -- 查重开关
    judge_config    JSON,                           -- 评测配置(测试用例/超时)
    rubric          JSON,                           -- LLM rubric(评分量规)
    status          ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_course (course_id, status)
) PRE_SPLIT_REGIONS=10;

-- 提交（用户域，按 user_id 分表，强单元化）
CREATE TABLE submission (
    id              BIGINT PRIMARY KEY AUTO_RANDOM(5),
    assignment_id   BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,                -- 分表键
    attempt_no      INT NOT NULL,                   -- 第几次尝试
    code_uri        VARCHAR(512) NOT NULL,          -- 对象存储代码 URI
    code_hash       VARCHAR(64) NOT NULL,           -- 完整性 hash
    submitted_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status          ENUM('UNSUBMITTED','DRAFT','SUBMITTED','GRADED','REVIEWED','ARCHIVED') NOT NULL,
    plagiarism_flag TINYINT DEFAULT 0,              -- 查重 flag(仅标记)
    proctor_flag    TINYINT DEFAULT 0,              -- AI proctoring flag
    UNIQUE KEY uk_user_attempt (user_id, assignment_id, attempt_no),
    KEY idx_assignment (assignment_id, status)
) PARTITION BY HASH(user_id) PARTITIONS 16;

-- 评分（用户域，按 user_id 分表）
CREATE TABLE grade (
    id              BIGINT PRIMARY KEY AUTO_RANDOM(5),
    submission_id   BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,                -- 分表键
    assignment_id   BIGINT NOT NULL,
    stage1_io_score     INT,            -- ①IO/单元
    stage2_static_score INT,            -- ②静态
    stage3_security_score INT,          -- ③安全
    stage4_style_score   INT,           -- ④Style
    stage5_llm_score     INT,           -- ⑤LLM rubric
    stage5_llm_feedback  TEXT,          -- LLM 形成性反馈
    stage5_leniency      FLOAT,         -- Leniency metric
    final_score      INT NOT NULL,      -- 聚合最终分
    graded_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewer_id      BIGINT,            -- 人工复核教师
    reviewed_at      TIMESTAMP,
    KEY idx_submission (submission_id)
) PARTITION BY HASH(user_id) PARTITIONS 16;

-- 申诉（用户域）
CREATE TABLE regrade (
    id              BIGINT PRIMARY KEY AUTO_RANDOM(5),
    submission_id   BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    reason          TEXT NOT NULL,
    status          ENUM('PENDING','APPROVED','REJECTED') DEFAULT 'PENDING',
    reviewer_id     BIGINT,
    review_note     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewed_at     TIMESTAMP,
    KEY idx_submission (submission_id)
) PARTITION BY HASH(user_id) PARTITIONS 16;

-- 查重桶（Tier1 MinHash+LSH 输出）
CREATE TABLE plagiarism_bucket (
    id              BIGINT PRIMARY KEY AUTO_RANDOM(5),
    assignment_id   BIGINT NOT NULL,
    bucket_hash     VARCHAR(64) NOT NULL,           -- LSH 桶 hash
    submission_ids  JSON NOT NULL,                  -- 桶内 submission 列表
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_assignment_bucket (assignment_id, bucket_hash)
);
```

### 4.2 Submission 状态机

```
                  save(draft)
  [初始] ─────────────────────► DRAFT
                                 │
                                 │ submit()(code_hash 校验)
                                 ▼
                             SUBMITTED
                                 │
                                 │ grade()(pipeline 完成)
                                 ▼
                              GRADED
                                 │
                                 │ review()(教师签核/高风险)
                                 ▼
                            REVIEWED
                                 │
                                 │ archive()(课程结束)
                                 ▼
                            ARCHIVED

  超时未提交(过了 due_at+grace):
      DRAFT/UNSUBMITTED → 自动锁定(不允许 submit)

  申诉 regrade:
      REVIEWED/GRADED → regrade.status=PENDING → APPROVED 则回 GRADED 重评
```

### 4.3 MinHash+LSH 查重算法伪代码（Tier1）

```
算法: Tier1PlagiarismBucket(assignmentId)
输入: 某 assignment 下所有 submission 代码
输出: plagiarism_bucket 记录(桶内近重复候选)

1. submissions = DB.query("SELECT id, code_uri FROM submission WHERE assignment_id=? AND status IN ('SUBMITTED','GRADED','REVIEWED')", assignmentId)
2. FOR sub IN submissions:
2.1    code = ObjectStorage.get(sub.code_uri)
2.2    tokens = tokenize(code, lang)              // tree-sitter token 化
2.3    shingles = kShingles(tokens, k=5)          // k-shingle
2.4    minhash = MinHash(num_hashes=128)
2.5    FOR s IN shingles:
2.6        minhash.update(s)
2.7    sub.minhash = minhash.signature            // 128 维签名
3. // LSH 分桶: 将 128 维签名分 32 个 band, 每 band 4 个 hash
4. bands = 32; rows = 4
5. buckets = {}  // bucket_hash → [submission_ids]
6. FOR sub IN submissions:
6.1    FOR b IN 0..bands-1:
6.2        band_sig = sub.minhash[b*rows : (b+1)*rows]
6.3        bucket_hash = sha1(band_sig)
6.4        buckets[bucket_hash].append(sub.id)
7. // 仅保留 size>1 的桶(候选近重复)
8. FOR bucket_hash, ids IN buckets:
8.1    IF len(ids) > 1:
8.2        DB.insert("plagiarism_bucket", {assignment_id, bucket_hash, submission_ids: ids})
9. RETURN buckets

复杂度: O(n) — MinHash 签名 O(n·k), LSH 分桶 O(n·bands)
可扩十亿级(LSHBloom 证明)

注: 同桶不等于抄袭, 仅是候选, 需 Tier2 精查
```

### 4.4 Dolos/JPlag 集成（Tier2）

```
算法: Tier2PlagiarismPairwise(assignmentId)
输入: plagiarism_bucket 记录(桶内候选)
输出: 相似度矩阵 + 高亮 diff

1. buckets = DB.query("SELECT * FROM plagiarism_bucket WHERE assignment_id=?", assignmentId)
2. results = []
3. FOR bucket IN buckets:
3.1    ids = bucket.submission_ids
3.2    // 桶内 pairwise(桶内 n 小, O(n²) 可接受)
3.3    FOR i, j IN combinations(ids, 2):
3.4        codeA = ObjectStorage.get(subA.code_uri)
3.5        codeB = ObjectStorage.get(subB.code_uri)
3.6        // Dolos: tree-sitter AST 比对
3.7        dolosResult = Dolos.run("--language", lang, codeA, codeB)
3.8        similarity = dolosResult.similarity    // 0-1
3.9        IF similarity > THRESHOLD(0.3):
3.10           results.append({subA, subB, similarity, diff: dolosResult.diff})
4. // 写 flag 到 submission(仅标记, 不扣分)
5. FOR r IN results:
6.     DB.update("submission", {plagiarism_flag: 1}, r.subA.id)
7.     DB.update("submission", {plagiarism_flag: 1}, r.subB.id)
8. RETURN results

Dolos 集成方式: CLI 调用(dolos-cli) 或 REST API(dolos-web)
JPlag 集成方式: JAR 调用(jplag.jar) 备选
MOSS 弃用(无 API, 单批提交受限)
```

---

## 5. 评分平台 Grading 详细设计

### 5.1 分层 pipeline 设计

```
pipeline 输入: submission(submissionId, code_uri, judge_config, rubric)
pipeline 输出: grade(stage1..5 + final_score + feedback)

每层设计:

①IO/单元测试(同步实时, 确定, 唯一可信高风险)
  输入: 代码 + 测试用例(judge_config.test_cases)
  执行: Firecracker microVM 内运行, stdin→stdout 比对
  输出: {pass_count, fail_count, coverage, stage1_io_score}
  超时: 30s/用例
  评分: stage1_io_score = (pass_count / total) * max_stage1

②静态分析(同步, Semgrep)
  输入: 源码
  执行: Semgrep --config rule_set
  输出: {violations[], stage2_static_score}
  超时: 10s
  评分: 扣分制(每违规扣分, 下限 0)
  (必含规则: 必含 X / 必不含 AI 生成模式)

③安全扫描(同步, SAST/CodeQL)
  输入: 源码
  执行: Semgrep SAST + CodeQL(可选)
  输出: {vulns[], stage3_security_score}
  超时: 30s
  评分: 严重漏洞直接 0 分, 一般漏洞扣分

④Style(同步, checkstyle/prettier)
  输入: 源码
  执行: checkstyle(Java)/prettier(JS)/autopep8(Python)
  输出: {style_violations[], stage4_style_score}
  超时: 10s
  评分: 扣分制

⑤LLM rubric 评分(异步批, CoT+self-consistency)
  输入: 源码 + rubric(每步 1-2 分)
  执行: AI 网关调用 LLM, k 采样
  输出: {stage5_llm_score, stage5_llm_feedback, stage5_leniency}
  超时: 60s/批
  评分: 见 §5.2

确定性阶段①-④: 同步实时返回(sub-second~秒级)
LLM 阶段⑤: 异步批, 永不放同步路径(ADR-I04)
```

### 5.2 LLM rubric CoT prompt 设计

```
prompt 模板:
  """
  你是代码评分助教。请按以下 Rubric 评分。

  【作业题目】{problem_statement}
  【评分 Rubric】(每步 1-2 分, 无部分分)
  - 准则1: {criterion_1} (0-2 分)
  - 准则2: {criterion_2} (0-2 分)
  - ...
  - 准则N: {criterion_N} (0-2 分)

  【学员代码】
  ```{language}
  {student_code}
  ```

  请按以下步骤输出:
  1. 对每个准则, 先推理(Chain of Thought)分析代码是否满足
  2. 给出该准则分数(0/1/2, 无部分分)
  3. 汇总总分
  4. 给出形成性反馈(改进建议)

  输出 JSON:
  {
    "reasoning": [{"criterion": "...", "thought": "...", "score": 0-2}, ...],
    "total_score": int,
    "feedback": "..."
  }
  """

CoT 原则(ADR-I05):
  - 每准则推理后再出分(Rubric Is All You Need 2025, 降 MAE)
  - 无部分分(0/1/2, 避免模糊)
  - 形成性反馈(不只对错)
```

### 5.3 self-consistency k 采样实现

```
算法: LLMRubricGrade(submission, rubric, k=5)
输入: 提交代码, rubric, 采样数 k
输出: 最终分数 + 反馈 + leniency

1. results = []
2. FOR i IN 1..k:
2.1    // 温度采样(temperature=0.7, 不同推理路径)
2.2    resp = AIGateway.chat({
2.3        model: "gpt-4o",  // 或模型路由选定
2.4        temperature: 0.7,
2.5        messages: [buildCoTPrompt(submission, rubric)]
2.6    })
2.7    parsed = parseJSON(resp.content)
2.8    results.append(parsed)
3. // 多数表决降方差(Towards Reliable LLM Grading 2025)
4. scores = [r.total_score FOR r IN results]
5. final_score = mode(scores)  // 众数
6. // 若无众数(全不同), 取中位数
7. IF final_score == nil:
8.     final_score = median(scores)
9. // Leniency metric: 监控模型严格度漂移
10. leniency = mean(scores) - rubric.max_score/2   // 正=偏宽, 负=偏严
11. IF abs(leniency) > LENIENCY_THRESHOLD(1.0):
12.   Alert("LLM 评分漂移", {submission, leniency, scores})
13. // 取最接近 final_score 的 feedback
14. feedback = (results.find(r => r.total_score == final_score)).feedback
15. RETURN {stage5_llm_score: final_score, stage5_llm_feedback: feedback, stage5_leniency: leniency}

成本控制(AI 网关成本栈, D-018):
  - 语义缓存(Redis LangCache): 同题相似代码命中缓存
  - 模型路由(RouteLLM): 简单题走小模型, 复杂题走大模型
  - k 采样并行(非串行), 总延迟 ≈ 单次延迟
```

### 5.4 成绩聚合算法

```
算法: AggregateGrades(userId, termId)
输入: userId, 学期
输出: 成绩单(per-course → per-term)

1. // per-assignment 成绩(已由 pipeline 产出)
2. assignmentGrades = DB.query("SELECT * FROM grade WHERE user_id=? AND ...", userId)
3. // per-course 聚合(按 course 加权方案)
4. courseGrades = {}
5. FOR ag IN assignmentGrades:
5.1    assignment = DB.get("assignment", ag.assignment_id)
5.2    courseId = assignment.course_id
5.3    weight = assignment.weight  // 加权方案
5.4    courseGrades[courseId].total += ag.final_score * weight
5.5    courseGrades[courseId].weightSum += weight
6. FOR course IN courseGrades:
7.     course.finalScore = course.total / course.weightSum
8. // per-term 聚合
9. termGPA = weightedAvg(courseGrades, courseCredit)
10. // 申诉 regrade 接入
11. FOR ag IN assignmentGrades:
12.     regrades = DB.query("SELECT * FROM regrade WHERE submission_id=?", ag.submission_id)
13.     IF any(regrades.status == 'PENDING'):
14.         ag.flag = 'UNDER_REVIEW'
15. RETURN {courseGrades, termGPA}

加权方案: 教师配置 assignment.weight, 缺省平均
申诉 regrade: APPROVED 则回 GRADED 重评, 不自动改分
查重 flag: 接入但不自动零分, 人工签核
```

---

## 6. 关键时序图

### 6.1 作业提交→评测→出分全链路

```
学员    BFF    作业平台    评分pipeline    Firecracker    AI网关(LLM)    数据中台
 │       │       │            │               │              │             │
 │──POST /submissions──►      │               │              │             │
 │       │──路由(user单元)──► │               │              │             │
 │       │       │            │               │              │             │
 │       │       │ 存submission│               │              │             │
 │       │       │ (code_uri,  │               │              │             │
 │       │       │  code_hash) │               │              │             │
 │       │       │            │               │              │             │
 │       │       │──触发评测──►│               │              │             │
 │       │       │            │               │              │             │
 │       │       │            │ ①IO/单元(同步)│              │             │
 │       │       │            │──取 microVM──►│              │             │
 │       │       │            │◄──结果────────│              │             │
 │       │       │            │ (pass/fail)   │              │             │
 │       │       │            │               │              │             │
 │       │       │            │ ②静态(Semgrep)│              │             │
 │       │       │            │ ③安全(SAST)   │              │             │
 │       │       │            │ ④Style        │              │             │
 │       │       │            │ (秒级,同步)   │              │             │
 │       │       │            │               │              │             │
 │       │       │◄──stage1-4────────────────│              │             │
 │       │       │   同步返回  │               │              │             │
 │       │◄──────│            │               │              │             │
 │◄──────│       │            │               │              │             │
 │       │       │            │               │              │             │
 │       │       │            │ ⑤LLM(异步批) │              │             │
 │       │       │            │──k采样评分──────────────────►│             │
 │       │       │            │◄──k scores───────────────────│             │
 │       │       │            │ self-consistency             │             │
 │       │       │            │ 多数表决                      │             │
 │       │       │            │               │              │             │
 │       │       │            │──写 grade─────│              │             │
 │       │       │            │               │              │             │
 │       │       │            │──学情事件────────────────────────────────►│
 │       │       │            │               │              │ (Iceberg)   │
 │       │       │            │               │              │             │
 │       │       │            │──通知(NTF)────│              │             │
 │       │       │            │ (评分完成)    │              │             │
 │       │       │            │               │              │             │
 │──GET /grades/{submissionId}────────────────│              │             │
 │       │       │            │               │              │             │
 │       │       │──查询 grade│               │              │             │
 │       │       │◄───────────│               │              │             │
 │       │◄──────│            │               │              │             │
 │◄──────│       │            │               │              │             │
 │ (含 LLM feedback + 查重flag + 申诉入口)    │              │             │
```

### 6.2 直播推流→观看全链路

```
教师    BFF    直播房间    SRS origin    LiveKit SFU    CDN    学员(互动)  学员(大众)
 │       │       │            │              │         │         │            │
 │──POST /live/rooms──►       │              │         │         │            │
 │       │──创建房间────────► │              │         │         │            │
 │       │       │──路由(room→cell)         │         │         │            │
 │       │       │──createRoom(SFU)────────────────►│         │            │
 │       │       │◄──sfuRoomId──────────────────────│         │            │
 │       │       │──生成 ingestUrl(推流)  │          │         │            │
 │       │◄──────│            │              │         │         │            │
 │◄──────│       │            │              │         │         │            │
 │ (推流地址)    │            │              │         │         │            │
 │       │       │            │              │         │         │            │
 │──OBS推流(WHIP/RTMP)────────►│              │         │         │            │
 │       │       │            │              │         │         │            │
 │       │       │            │ SRS 收流     │         │         │            │
 │       │       │            │──Redis登记 stream:{room}│         │            │
 │       │       │            │──转码(多清晰度)        │         │            │
 │       │       │            │──录制 FLV/MP4→对象存储 │         │            │
 │       │       │            │              │         │         │            │
 │       │       │            │──分发互动流──►│        │         │            │
 │       │       │            │              │ SFU 转发│         │            │
 │       │       │            │              │         │         │            │
 │       │       │            │──分发 LL-HLS──────────────────────►│            │
 │       │       │            │              │         │ (CDN边缘) │            │
 │       │       │            │              │         │         │            │
 │       │       │            │              │         │         │            │
 │       │       │ (互动学员≤几百)           │         │         │            │
 │       │       │            │              │         │──WebRTC连 SFU          │
 │       │       │            │              │         │◄──亚秒流──────────────│
 │       │       │            │              │         │ (延迟<800ms)           │
 │       │       │            │              │         │         │            │
 │       │       │            │              │         │         │ (大众学员) │
 │       │       │            │              │         │         │   ──LL-HLS─►│
 │       │       │            │              │         │         │ (延迟2-4s) │
 │       │       │            │              │         │         │            │
 │       │       │            │              │         │         │            │
 │──弹幕(WS)──► WS网关 ──►Redis ──►Pulsar(削峰)──►消费 ──►广播──►│            │
 │       │       │            │              │         │ (批量100ms窗口)       │
 │       │       │            │              │         │         │            │
 │──endStream──►│            │              │         │         │            │
 │       │──结束房间────────► │              │         │         │            │
 │       │       │──停止推流  │              │         │         │            │
 │       │       │──录制处理→对象存储(回放)  │         │         │            │
 │       │       │──状态 ENDED→ARCHIVED      │         │         │            │
```

---

## 7. 接口契约摘要

### 7.1 直播接口
```
POST /api/v1/live/rooms              创建直播间 → {roomId, ingestUrl}
GET  /api/v1/live/rooms/{id}/play    获取播放地址 → {webbrtcUrl, hlsUrl, mode}
WS   /ws/live/{roomId}               弹幕/聊天/连麦 WS
POST /api/v1/live/rooms/{id}/connect/{userId}  连麦申请
```

### 7.2 在线编程接口
```
POST /api/v1/ide/sessions            开 IDE 会话 → {sessionId, yjsDocId}
POST /api/v1/ide/sessions/{id}/run   运行代码(WebContainer 客户端) → {stdout, stderr}
POST /api/v1/ide/sessions/{id}/save  保存快照 → {ok}
WS   /ws/ide/{sessionId}             Yjs 协作 WS
POST /api/v1/judge/run               运行代码(Judge) → {token}
GET  /api/v1/judge/result/{token}    查询评测结果 → {stage1..5, finalScore}
```

### 7.3 作业接口
```
POST /api/v1/assignments             创建作业(教师)
GET  /api/v1/assignments             作业列表(学员)
POST /api/v1/submissions             提交作业 → {submissionId, status}
GET  /api/v1/submissions/{id}/plagiarism  查重报告 → {flag, pairs[]}
POST /api/v1/regrades                发起申诉 → {regradeId}
```

### 7.4 评分接口
```
GET  /api/v1/grades/{submissionId}   查询评分 → {stage1..5, finalScore, feedback, flags}
GET  /api/v1/transcripts/{termId}    成绩单 → {courseGrades[], termGPA}
```

---

## 8. 风险与开放问题

| ID | 风险 | 缓解 |
|----|------|------|
| R-SDD-01 | Firecracker snapshot 池冷启动尖峰 | 按并发预测池大小，闲时缩容，忙时预热 |
| R-SDD-02 | MinHash+LSH 桶过大 pairwise 爆炸 | 桶内超阈值二次 LSH 细分 |
| R-SDD-03 | LLM k 采样成本高 | 语义缓存+模型路由+k 并行 |
| R-SDD-04 | 直播房间跨 cell 故障切换状态丢失 | 房间状态持久化 Redis+对象存储，恢复重建 |
| R-SDD-05 | Yjs 大文档协作性能 | 增量快照+文档分片 |
| O-SDD-01 | Dolos vs JPlag 选型(默认 Dolos) | SDD 阶段验证后定 |
| O-SDD-02 | LLM k 值(默认 5)调优 | 评测一致性测试后定 |

---

**下一步**：本 SDD 经评审通过后，作为 SP-05 编码与测试的上游输入。

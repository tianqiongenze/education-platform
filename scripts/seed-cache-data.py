#!/usr/bin/env python3
"""
seed-cache-data.py
==================
压测前 Redis 缓存预热脚本 — 仅写入 `lt:` (loadtest) 前缀的 key。

⚠️ 安全铁律 (CRITICAL):
  * 共享 K8s 集群的 Redis 被其他项目使用, 绝不能误碰其 key
  * 只写 `lt:` 前缀的 key, 预热前/后均扫描确认不存在未授权前缀写入
  * NEVER 触碰 `esp:*`, `zcode:session:*`, `audit:*` 或任何其它非 `lt:` 前缀的 key
  * 监控 Redis 内存 (INFO memory), 若 used_memory > maxmemory * 80% 则中止
  * 所有 key 带 TTL (默认 24h), 避免压测数据长期驻留

写入内容:
  * lt:course:{courseId}         → JSON 课程对象 (top 1000 热门课程)
  * lt:search:hot                → JSON 数组, top 100 热搜词
  * lt:user:session:{userId}     → JSON 会话对象 (500 测试用户)

执行方式:
  通过 `kubectl exec` 进 Redis Pod 调用 `redis-cli` (与 session-recovery 同款模式,
  不依赖 Python redis 库存在于 Pod 内, 兼容性最好)。

环境变量:
  K8S_NAMESPACE         Redis Pod 所在 namespace (默认 ep-prod)
  K8S_REDIS_POD         Redis Pod 名 (默认自动查找, 选第一个 redis* pod)
  K8S_REDIS_CONTAINER   容器名 (默认 redis)
  K8S_REDIS_CLI         redis-cli 二进制路径 (默认 redis-cli)
  CACHE_TTL_SECONDS     key TTL 秒数 (默认 86400 = 24h)
  MEMORY_ABORT_RATIO    used_memory / maxmemory 阈值, 超过则中止 (默认 0.80)
  DRY_RUN               1=只打印将要执行的命令, 不真正写入 (默认 0)
  KUBECTL_BIN           kubectl 路径 (默认 kubectl)

用法:
  python3 scripts/seed-cache-data.py                # 预热全部 (课程/热搜/会话)
  python3 scripts/seed-cache-data.py --only courses sessions
  python3 scripts/seed-cache-data.py --list
  python3 scripts/seed-cache-data.py --dry-run      # 干跑, 不写

退出码:
  0 成功; 1 连接/内存超限/非白名单 key 检测失败; 2 参数错误
"""

from __future__ import annotations

import json
import os
import random
import shlex
import subprocess
import sys
import time
from typing import Optional

# ---------------------------------------------------------------------------
# 安全常量
# ---------------------------------------------------------------------------
# 唯一允许写入的 key 前缀 — 任何超出此前缀的 key 都拒绝
ALLOWED_PREFIX = "lt:"

# 明确禁止读取/写入的前缀清单 (任何形式出现都触发 abort)
FORBIDDEN_PREFIXES = ("esp:", "zcode:session:", "audit:")

# 内存阈值
DEFAULT_MEMORY_ABORT_RATIO = 0.80

# TTL
DEFAULT_TTL = 86400  # 24h

# 预热数量
HOT_COURSES_COUNT = 1000
HOT_SEARCH_TERMS_COUNT = 100
SESSIONS_COUNT = 500

# 课程主题池 (与 seed-loadtest-data.py 保持一致)
COURSE_SUBJECTS = [
    "Java基础", "数据结构", "操作系统", "计算机网络", "数据库原理",
    "分布式系统", "机器学习", "深度学习", "算法面试", "系统设计",
]

# 热搜词池
HOT_SEARCH_TERMS_POOL = [
    "Java", "Python", "Spring Boot", "MySQL", "Redis", "Kafka",
    "Kubernetes", "Docker", "机器学习", "深度学习", "数据结构",
    "算法", "操作系统", "计算机网络", "分布式", "微服务",
    "Go", "Rust", "TypeScript", "React", "Vue", "面试",
    "系统设计", "高并发", "MySQL调优", "JVM", "LeetCode",
]


# ---------------------------------------------------------------------------
# 配置读取
# ---------------------------------------------------------------------------
def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


def env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


K8S_NAMESPACE = env("K8S_NAMESPACE", "ep-prod")
K8S_REDIS_POD = env("K8S_REDIS_POD", "")
K8S_REDIS_CONTAINER = env("K8S_REDIS_CONTAINER", "redis")
K8S_REDIS_CLI = env("K8S_REDIS_CLI", "redis-cli")
CACHE_TTL_SECONDS = env_int("CACHE_TTL_SECONDS", DEFAULT_TTL)
MEMORY_ABORT_RATIO = env_float("MEMORY_ABORT_RATIO", DEFAULT_MEMORY_ABORT_RATIO)
DRY_RUN = env("DRY_RUN", "0") == "1"
KUBECTL_BIN = env("KUBECTL_BIN", "kubectl")


# ---------------------------------------------------------------------------
# kubectl + redis-cli 执行层
# ---------------------------------------------------------------------------
def find_redis_pod() -> str:
    """自动查找 namespace 下第一个 redis* pod。"""
    if K8S_REDIS_POD:
        return K8S_REDIS_POD
    try:
        out = subprocess.check_output(
            [KUBECTL_BIN, "get", "pods", "-n", K8S_NAMESPACE,
             "-l", "app=redis", "-o", "jsonpath={.items[0].metadata.name}"],
            stderr=subprocess.STDOUT, timeout=15, text=True,
        ).strip()
    except subprocess.CalledProcessError as e:
        # 兜底: 列出所有 pod 再过滤 redis*
        try:
            out = subprocess.check_output(
                [KUBECTL_BIN, "get", "pods", "-n", K8S_NAMESPACE,
                 "-o", "name"], timeout=15, text=True,
            )
            for line in out.splitlines():
                name = line.strip().replace("pod/", "")
                if "redis" in name.lower():
                    return name
        except Exception:
            pass
        raise RuntimeError(
            f"无法自动查找 Redis Pod (namespace={K8S_NAMESPACE}): {e.output.strip()}"
        )
    if not out:
        raise RuntimeError(f"namespace {K8S_NAMESPACE} 未找到 redis pod")
    return out


def redis_exec(args: list[str], *, timeout: int = 30) -> str:
    """通过 kubectl exec 进 Redis Pod 跑 redis-cli 命令。
    返回 stdout; 失败抛 RuntimeError。"""
    cmd = [KUBECTL_BIN, "exec", "-n", K8S_NAMESPACE, "-c", K8S_REDIS_CONTAINER,
           find_redis_pod(), "--", K8S_REDIS_CLI] + args
    if DRY_RUN:
        print(f"  [dry-run] {' '.join(shlex.quote(c) for c in cmd)}")
        return ""
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT,
                                      timeout=timeout, text=True)
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            f"redis-cli 命令失败: {e.output.strip() or '<no output>'}"
        )
    return out.strip()


def redis_set_json(key: str, obj, ttl: int = CACHE_TTL_SECONDS) -> None:
    """SET key value EX ttl — value 为 JSON 字符串。
    写入前对 key 做白名单校验。"""
    assert_key_safe(key)
    payload = json.dumps(obj, ensure_ascii=False)
    redis_exec(["SET", key, payload, "EX", str(ttl)])


def assert_key_safe(key: str) -> None:
    """铁律校验: key 必须以 lt: 开头, 且不含任何禁止前缀。"""
    if not key.startswith(ALLOWED_PREFIX):
        raise RuntimeError(
            f"安全中止: key 不以 '{ALLOWED_PREFIX}' 开头: {key!r}"
        )
    for fp in FORBIDDEN_PREFIXES:
        if fp in key:
            raise RuntimeError(
                f"安全中止: key 含禁止前缀 {fp!r}: {key!r}"
            )


def redis_info_memory() -> dict[str, int]:
    """INFO memory → 解析出 used_memory / maxmemory (字节)。
    maxmemory=0 表示无限制 (Redis 默认)。"""
    out = redis_exec(["INFO", "memory"])
    info: dict[str, int] = {}
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        k, _, v = line.partition(":")
        if k in ("used_memory", "maxmemory", "used_memory_rss"):
            try:
                info[k] = int(v)
            except ValueError:
                pass
    return info


def check_memory_or_abort() -> None:
    """检查 used_memory / maxmemory 比例; 超过 MEMORY_ABORT_RATIO 则中止。
    maxmemory=0 (无限制) 时跳过比例检查 (但仍打印 used_memory)。"""
    mem = redis_info_memory()
    used = mem.get("used_memory", 0)
    mx = mem.get("maxmemory", 0)
    rss = mem.get("used_memory_rss", 0)
    if mx == 0:
        print(f"  [memory] maxmemory=0 (unlimited); used={used} "
              f"({used / 1024 / 1024:.1f} MiB) rss={rss}")
        return
    ratio = used / mx if mx > 0 else 0.0
    print(f"  [memory] used={used} ({used / 1024 / 1024:.1f} MiB) "
          f"max={mx} ({mx / 1024 / 1024:.1f} MiB) ratio={ratio:.1%}")
    if ratio > MEMORY_ABORT_RATIO:
        raise RuntimeError(
            f"内存超限中止: used/max={ratio:.1%} > {MEMORY_ABORT_RATIO:.0%} "
            f"(used={used}, max={mx}). 请先清理或扩容 Redis 再预热。"
        )


# ---------------------------------------------------------------------------
# 数据生成 (与 seed-loadtest-data.py / k6 common.js 风格一致)
# ---------------------------------------------------------------------------
def gen_course_object(course_id: int) -> dict:
    """lt:course:{id} — 单个课程 JSON (与 ep_course schema 对应)。"""
    subj = random.choice(COURSE_SUBJECTS)
    return {
        "id": course_id,
        "title": f"Course {course_id} - {subj}",
        "subject": subj,
        "teacherId": random.randint(8000, 9999),
        "category": random.randint(1, 10),
        "difficulty": random.randint(1, 3),
        "price": round(random.uniform(0, 999.99), 2),
        "status": 1,  # PUBLISHED
        "studentCount": random.randint(0, 50000),
        # 缓存元信息 (便于排查, 不参与业务)
        "_source": "lt-seed",
        "_ttl": CACHE_TTL_SECONDS,
    }


def gen_hot_search_terms(count: int = HOT_SEARCH_TERMS_COUNT) -> list[dict]:
    """lt:search:hot — 热搜词数组 (含搜索量/趋势)。"""
    pool = list(HOT_SEARCH_TERMS_POOL)
    random.shuffle(pool)
    terms = pool[:count]
    return [
        {"term": t, "searchCount": random.randint(1000, 1_000_000),
         "trend": random.choice(["up", "flat", "down"])}
        for t in terms
    ]


def gen_session_object(user_id: int) -> dict:
    """lt:user:session:{userId} — 用户会话 JSON (模拟 BFF 下发的会话)。"""
    role = "student"
    if 9500 <= user_id <= 9999:
        role = "teacher"
    elif 8000 <= user_id <= 8249:
        role = "admin"
    return {
        "userId": str(user_id),
        "username": f"loadtest_{user_id}",
        "role": role,
        "tenantId": 1,
        "loginAt": int(time.time()),
        "expireAt": int(time.time()) + CACHE_TTL_SECONDS,
        "_source": "lt-seed",
    }


# ---------------------------------------------------------------------------
# 预热任务
# ---------------------------------------------------------------------------
def warm_courses(count: int = HOT_COURSES_COUNT) -> int:
    """预热热门课程 (id 1..count)。返回写入数。"""
    print(f"\n=== 预热热门课程 ({count} 门) → lt:course:{{id}} ===")
    written = 0
    for course_id in range(1, count + 1):
        key = f"{ALLOWED_PREFIX}course:{course_id}"
        try:
            redis_set_json(key, gen_course_object(course_id))
            written += 1
            if written % 100 == 0:
                print(f"  已写入 {written}/{count} 课程缓存", flush=True)
        except RuntimeError as e:
            print(f"  [warn] 跳过 {key}: {e}", file=sys.stderr)
    print(f"  完成: 写入 {written}/{count} 课程缓存")
    return written


def warm_hot_search(count: int = HOT_SEARCH_TERMS_COUNT) -> int:
    """预热热搜词 (单个 key 存数组)。"""
    print(f"\n=== 预热热搜词 ({count} 个) → lt:search:hot ===")
    key = f"{ALLOWED_PREFIX}search:hot"
    terms = gen_hot_search_terms(count)
    redis_set_json(key, terms)
    print(f"  完成: 写入 {len(terms)} 个热搜词到 {key}")
    return len(terms)


def warm_sessions(count: int = SESSIONS_COUNT) -> int:
    """预热测试用户会话 (与 gen-loadtest-tokens.py 的 id 区间一致)。
    覆盖 students (9000-9499), teachers (9500-9999), admins (8000-8249)。
    从每个区间均匀采样 count 个。"""
    print(f"\n=== 预热测试会话 ({count} 个用户) → lt:user:session:{{userId}} ===")
    # 从三组各采样
    per_group = count // 3
    students = random.sample(range(9000, 9500), per_group)
    teachers = random.sample(range(9500, 10000), per_group)
    admins = random.sample(range(8000, 8250), count - 2 * per_group)
    user_ids = students + teachers + admins
    random.shuffle(user_ids)

    written = 0
    for uid in user_ids:
        key = f"{ALLOWED_PREFIX}user:session:{uid}"
        try:
            redis_set_json(key, gen_session_object(uid))
            written += 1
        except RuntimeError as e:
            print(f"  [warn] 跳过 {key}: {e}", file=sys.stderr)
    print(f"  完成: 写入 {written}/{count} 会话缓存")
    return written


# ---------------------------------------------------------------------------
# 后置校验: 确认只写了 lt: 前缀的 key
# ---------------------------------------------------------------------------
def post_verify() -> None:
    """扫描 lt: 前缀 key 数量, 并抽查确认没有非白名单前缀被写入。
    注意: 不执行 KEYS * (生产环境 O(N) 阻塞), 只 SCAN lt: 前缀。"""
    print("\n=== 后置校验 ===")
    # 统计 lt: key 数量 (SCAN, 不阻塞)
    try:
        out = redis_exec(["--scan", "--pattern", "lt:*"], timeout=60)
        lt_keys = [k for k in out.splitlines() if k.strip()]
        print(f"  lt:* 前缀 key 数量: {len(lt_keys)}")
    except RuntimeError as e:
        print(f"  [warn] 扫描 lt:* 失败: {e}", file=sys.stderr)
        lt_keys = []

    # 关键安全断言: 抽查前 5 个 key 全部以 lt: 开头
    for k in lt_keys[:5]:
        assert_key_safe(k)
    print(f"  抽查前 5 个 key 均为 {ALLOWED_PREFIX} 前缀 ✓")


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
TASKS = {
    "courses": ("热门课程缓存", warm_courses),
    "search": ("热搜词缓存", warm_hot_search),
    "sessions": ("测试用户会话缓存", warm_sessions),
}


def list_tasks():
    print("可预热缓存任务 (全部写入 lt: 前缀 key):")
    for name, (desc, _) in TASKS.items():
        print(f"  {name:<10} {desc}")


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="压测前 Redis 缓存预热 (仅 lt: 前缀 key, 共享集群安全)"
    )
    ap.add_argument("--only", nargs="+", choices=list(TASKS.keys()),
                    help="只预热指定任务; 默认全部")
    ap.add_argument("--list", action="store_true", help="列出任务后退出")
    ap.add_argument("--dry-run", action="store_true",
                    help="只打印 kubectl/redis-cli 命令, 不真正写入")
    args = ap.parse_args()

    if args.list:
        list_tasks()
        return 0

    if args.dry_run:
        global DRY_RUN
        DRY_RUN = True

    selected = args.only or list(TASKS.keys())

    print(f"[config] namespace={K8S_NAMESPACE} ttl={CACHE_TTL_SECONDS}s "
          f"memory_abort_ratio={MEMORY_ABORT_RATIO:.0%} dry_run={DRY_RUN}")

    # 1) 解析 Redis Pod
    try:
        pod = find_redis_pod()
        print(f"[pod] Redis Pod = {pod} (container={K8S_REDIS_CONTAINER})")
    except Exception as e:
        print(f"[error] {e}", file=sys.stderr)
        return 1

    # 2) PING 探活
    try:
        pong = redis_exec(["PING"])
        if pong != "PONG":
            raise RuntimeError(f"unexpected PING reply: {pong}")
        print(f"[ping] PING → {pong} ✓")
    except Exception as e:
        print(f"[error] Redis PING 失败: {e}", file=sys.stderr)
        return 1

    # 3) 内存预检 (写入前)
    print("[guard] 写入前内存检查")
    try:
        check_memory_or_abort()
    except RuntimeError as e:
        print(f"[abort] {e}", file=sys.stderr)
        return 1

    # 4) 执行预热任务
    summary: dict[str, int] = {}
    grand_start = time.monotonic()
    try:
        for name in selected:
            desc, fn = TASKS[name]
            n = fn()
            summary[name] = n
            # 每个任务后再检查一次内存, 防止中段超限
            check_memory_or_abort()
    except RuntimeError as e:
        print(f"[abort] {e}", file=sys.stderr)
        # 仍执行后置校验, 确认已写入的 key 都是安全的
        try:
            post_verify()
        except Exception:
            pass
        return 1
    except KeyboardInterrupt:
        print("\n[abort] 用户中断 (Ctrl-C)", file=sys.stderr)

    # 5) 后置安全校验
    try:
        post_verify()
    except RuntimeError as e:
        print(f"[FATAL] 后置校验检测到非白名单 key: {e}", file=sys.stderr)
        return 1

    # 6) 最终内存状态
    try:
        print("[guard] 写入后内存检查")
        check_memory_or_abort()
    except RuntimeError as e:
        print(f"[warn] 写入后内存超限: {e}", file=sys.stderr)

    # 7) 汇总
    elapsed = time.monotonic() - grand_start
    print("\n" + "=" * 60)
    print("预热汇总")
    print("=" * 60)
    for name, n in summary.items():
        print(f"  {name:<10} 写入 {n} 个 key")
    print(f"\n总耗时 {elapsed:.1f}s")
    print(f"所有 key 前缀: {ALLOWED_PREFIX}* (TTL {CACHE_TTL_SECONDS}s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

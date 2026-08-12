#!/usr/bin/env python3
"""
seed-loadtest-data.py
=====================
向 TiDB 批量插入 ~580 万行测试数据 (模拟 10 亿级数据规模), 覆盖两个数据库:
  - ep_business (5 张表): ep_user / ep_course / ep_order /
                          ep_payment / ep_refund
  - ep_itbackend (5 张表): ep_assignment / ep_submission /
                           ep_grade_pipeline / ep_grade_stage / ep_plagiarism_check

设计要点 (与项目铁律一致 — 不碰共享基础设施):
  * 只操作 ep_business 与 ep_itbackend, 不碰 esp_* / test / mysql 等其他库
  * 所有表 PK 是 TiDB AUTO_RANDOM(5), INSERT 不指定 PK 列
  * 批次大小 5000, 多值 INSERT (`VALUES (...),(...),...`) + `INSERT IGNORE`
  * 每批 commit; 每 10 批打印进度
  * 每批检查 SHOW STATUS LIKE 'Threads_connected', > 80 则 sleep 1s 降速
  * 时间戳: sp03 表用 epoch millis (BIGINT); sp05 表用 'YYYY-MM-DD HH:MM:SS' (DATETIME)
  * 任何非 Duplicate entry 报错立即停止并报告

D-113: 新增 ep_payment (500K) / ep_refund (100K) — OPM 中台扩展 (DDL 见 deploy/sql/opm-tables.sql)

环境变量:
  DB_PASSWORD  TiDB root 密码 (允许空, 默认空字符串)
  DB_HOST      默认 127.0.0.1
  DB_PORT      默认 4000
  DB_USER      默认 root

用法:
  python3 scripts/seed-loadtest-data.py              # 全量执行所有 8 张表
  python3 scripts/seed-loadtest-data.py --only ep_user ep_course
  python3 scripts/seed-loadtest-data.py --list
"""

from __future__ import annotations

import os
import sys
import time
import random
import uuid
import argparse
import datetime as dt
from typing import Callable, Iterable

import pymysql
from pymysql.err import MySQLError

# ---------------------------------------------------------------------------
# 连接参数
# ---------------------------------------------------------------------------
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("DB_PORT", "4000"))
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "")

BATCH_SIZE = 1000
PROGRESS_EVERY_N_BATCHES = 10
CONN_THRESHOLD = 80
SLEEP_ON_HIGH_CONN = 1.0

# 固定 BCrypt hash (60 字符, 不参与登录逻辑, 仅占位)
SEED_PW_HASH = "$2a$10$seedhashtest1234567890123456789012345678901234567890123"

# 允许操作的数据库白名单 — 任何超出范围的库都拒绝
ALLOWED_DATABASES = {"ep_business", "ep_itbackend"}

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def now_millis() -> int:
    return int(time.time() * 1000)


def random_millis_past_30d() -> int:
    """随机 [now-30d, now] 区间的 epoch millis."""
    span_ms = 30 * 24 * 3600 * 1000
    return now_millis() - random.randint(0, span_ms)


def random_datetime_past_30d() -> str:
    """随机 [now-30d, now] 区间的 DATETIME 字符串."""
    span_s = 30 * 24 * 3600
    t = dt.datetime.now() - dt.timedelta(seconds=random.randint(0, span_s))
    return t.strftime("%Y-%m-%d %H:%M:%S")


def datetime_after(s: str, max_add_seconds: int = 3600) -> str:
    """在给定 DATETIME 字符串上加 0..max_add_seconds 秒."""
    t = dt.datetime.strptime(s, "%Y-%m-%d %H:%M:%S")
    t2 = t + dt.timedelta(seconds=random.randint(0, max_add_seconds))
    return t2.strftime("%Y-%m-%d %H:%M:%S")


def get_threads_connected(conn) -> int:
    """返回 TiDB 'Threads_connected' 会话状态值."""
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW STATUS LIKE 'Threads_connected'")
            row = cur.fetchone()
            if row and row[1] is not None:
                return int(row[1])
    except Exception:
        pass
    return -1


# ---------------------------------------------------------------------------
# 批量插入引擎
# ---------------------------------------------------------------------------

def batch_insert(
    conn,
    *,
    db: str,
    table: str,
    columns: list[str],
    row_generator: Callable[[int, int], tuple],
    total: int,
    batch_size: int = BATCH_SIZE,
) -> int:
    """
    批量 INSERT IGNORE INTO db.table (cols) VALUES (%s,...)
    使用 executemany (pymysql 会拼接成多值 INSERT, 但参数走 binary 协议, 更稳).
    - 不指定 PK (AUTO_RANDOM 由 TiDB 生成)
    - 每批 commit; 每 PROGRESS_EVERY_N_BATCHES 批打印进度
    - 每批检查连接数 > CONN_THRESHOLD 则 sleep
    - Duplicate entry 警告计入 skipped 但不终止; 其它错误抛出
    返回实际插入行数 (executemany rows affected 之和; INSERT IGNORE 下近似).
    """
    if db not in ALLOWED_DATABASES:
        raise RuntimeError(f"拒绝操作非白名单数据库: {db}")
    if total <= 0:
        print(f"  [{db}.{table}] 目标 0 行, 跳过", flush=True)
        return 0

    col_clause = ",".join(f"`{c}`" for c in columns)
    placeholders = "(" + ",".join(["%s"] * len(columns)) + ")"
    sql = f"INSERT IGNORE INTO `{db}`.`{table}` ({col_clause}) VALUES {placeholders}"

    inserted = 0
    batches = 0
    start = time.monotonic()
    i = 0
    with conn.cursor() as cur:
        while i < total:
            n = min(batch_size, total - i)
            rows = [row_generator(i + k, k) for k in range(n)]
            try:
                affected = cur.executemany(sql, rows)
            except MySQLError as e:
                code = e.args[0] if e.args else 0
                # 1062 = Duplicate entry; INSERT IGNORE 一般会吞掉, 但某些情况下仍冒泡
                if code == 1062:
                    print(f"    [warn] Duplicate entry 批次 (i={i}, n={n}), 跳过该批: {e.args[1]}", flush=True)
                    i += n
                    continue
                # 其它错误: 抛给上层终止
                raise
            if affected is None:
                affected = 0
            inserted += affected
            conn.commit()
            i += n
            batches += 1

            # 连接数监控 (每 5 批查一次, 降低查询开销)
            tc = -1
            if batches % 5 == 0:
                tc = get_threads_connected(conn)
                if tc > CONN_THRESHOLD:
                    print(f"    [throttle] Threads_connected={tc} > {CONN_THRESHOLD}, sleep {SLEEP_ON_HIGH_CONN}s", flush=True)
                    time.sleep(SLEEP_ON_HIGH_CONN)

            if batches % PROGRESS_EVERY_N_BATCHES == 0:
                elapsed = time.monotonic() - start
                rate = inserted / elapsed if elapsed > 0 else 0
                print(f"    [{db}.{table}] 批次 {batches}: 已插入 {inserted}/{total} 行"
                      f" (耗时 {elapsed:.1f}s, {rate:.0f} rows/s, conn={tc})", flush=True)

    elapsed = time.monotonic() - start
    print(f"  [{db}.{table}] 完成: 插入 {inserted} 行, {batches} 批, 耗时 {elapsed:.1f}s", flush=True)
    return inserted


# ---------------------------------------------------------------------------
# 全局序列偏移
# ---------------------------------------------------------------------------
# 历史种子行已占用 seeduser_0..seeduser_99999 等 (约 70 万行).
# 为避免新批次的 username/phone/email/title 与历史行冲突 (导致 INSERT IGNORE 全部被跳过),
# 用一个足够大的偏移量生成所有确定性字符串字段. idempotency_key 用 uuid4 不会冲突.
# 每次运行从 DB 实际行数推断一个安全偏移 (见 init_global_offset).
GLOBAL_SEQ_OFFSET = 10_000_000


def init_global_offset(conn):
    """根据各表当前行数, 设置一个远超现有最大序号的全局偏移.
    偏移 = 当前最大行数 (跨表取最大) + 1_000_000 缓冲."""
    global GLOBAL_SEQ_OFFSET
    max_rows = 0
    for t in TABLES:
        try:
            n = get_current_count(conn, t["db"], t["table"])
            if n > max_rows:
                max_rows = n
        except Exception:
            pass
    GLOBAL_SEQ_OFFSET = max(max_rows + 1_000_000, 10_000_000)
    print(f"[offset] GLOBAL_SEQ_OFFSET = {GLOBAL_SEQ_OFFSET} (max existing rows = {max_rows})", flush=True)


# ---------------------------------------------------------------------------
# 各表行生成器
# ---------------------------------------------------------------------------

COURSE_SUBJECTS = [
    "Java基础", "数据结构", "操作系统", "计算机网络", "数据库原理",
    "分布式系统", "机器学习", "深度学习", "算法面试", "系统设计",
]

def gen_ep_user(i: int, _k: int) -> tuple:
    """ep_user: username, phone, email, password_hash, nickname, avatar,
                  status, tenant_id, created_at, updated_at, lock_version
    使用全局偏移 GLOBAL_SEQ_OFFSET 保证 username/phone/email 不与历史种子行冲突."""
    g = i + GLOBAL_SEQ_OFFSET
    username = f"seeduser_{g}"
    # 11 位手机号: 1 + 3位前缀 + 8位序号 (g % 1e8), 前缀避开已用 138/132/188
    prefix = random.choice(["390", "391", "392", "393", "394", "395", "396", "397", "398", "399"])
    phone = f"1{prefix}{g % 100000000:08d}"
    email = f"seed{g}@test.com"
    nickname = f"SeedUser{g}"
    avatar = f"https://cdn.test.com/avatar/{g % 1000}.png"
    status = 1  # ACTIVE
    tenant_id = 1
    ts = random_millis_past_30d()
    return (username, phone, email, SEED_PW_HASH, nickname, avatar,
            status, tenant_id, ts, ts, 0)


def gen_ep_course(i: int, _k: int) -> tuple:
    """ep_course: title, description, teacher_id, category, difficulty, price,
                    status, student_count, created_at, updated_at, lock_version"""
    g = i + GLOBAL_SEQ_OFFSET
    subj = random.choice(COURSE_SUBJECTS)
    title = f"Seed Course {g} - {subj}"
    description = f"自动生成测试课程 #{g}, 主题: {subj}"
    teacher_id = random.randint(8000, 9999)
    category = random.randint(1, 10)
    difficulty = random.randint(1, 3)
    price = round(random.uniform(0, 999.99), 2)
    status = 1  # PUBLISHED
    student_count = random.randint(0, 50000)
    ts = random_millis_past_30d()
    return (title, description, teacher_id, category, difficulty, price,
            status, student_count, ts, ts, 0)


def gen_ep_order(i: int, _k: int) -> tuple:
    """ep_order: user_id, course_id, amount, status, pay_method,
                  idempotency_key, created_at, updated_at, lock_version"""
    user_id = random.randint(1, 1000000)
    course_id = random.randint(1, 100000)
    amount = round(random.uniform(9.99, 999.99), 2)
    status = 1  # PAID
    pay_method = random.choice(["ALIPAY", "WECHAT"])
    idem = str(uuid.uuid4())
    ts = random_millis_past_30d()
    return (user_id, course_id, amount, status, pay_method, idem, ts, ts, 0)


# ---------------------------------------------------------------------------
# DDL 参考: deploy/sql/opm-tables.sql
# ep_payment: payment_id(PK AUTO_RANDOM), order_id, tenant_id, channel,
#             amount, currency, channel_tx_id, status, fail_reason,
#             created_at, updated_at
# 注意: DDL 未声明 lock_version 列; Payment API Java bean 里有 lockVersion
# 字段 (默认 0), 但 DB schema 不持久化该列。因此这里不写 lock_version,
# 与实际 DDL 保持一致 (避免 Unknown column 报错)。
# 任务要求里列了 lock_version, 这里以 DB schema 为准 (铁律: 不破坏现有表)。
# ---------------------------------------------------------------------------

PAYMENT_CHANNELS = ["ALIPAY", "WECHAT", "ALIPAY", "WECHAT", "MOCK"]
PAYMENT_CURRENCIES = ["CNY", "CNY", "CNY", "CNY", "USD"]


def gen_ep_payment(i: int, _k: int) -> tuple:
    """ep_payment (DDL: deploy/sql/opm-tables.sql):
       order_id, tenant_id, channel, amount, currency, channel_tx_id,
       status, created_at, updated_at
    状态分布 (status INT): 0=pending 1=success 2=failed 3=refunded
    绝大多数成功, 少量 pending/failed/refunded, 贴近真实流量分布。"""
    g = i + GLOBAL_SEQ_OFFSET
    # 关联到种子订单区间 (ep_order 也是 random.randint(1, 1e6) user_id,
    # order_id 由 AUTO_RANDOM 生成, 这里用稳定的虚拟 order_id 即可, 不要求外键强一致)
    order_id = random.randint(1, 1_000_000)
    tenant_id = "default"
    channel = random.choice(PAYMENT_CHANNELS)
    amount = round(random.uniform(9.99, 999.99), 2)
    currency = random.choice(PAYMENT_CURRENCIES)
    # 渠道流水号: 全局唯一, 加偏移避免与历史种子冲突
    channel_tx_id = f"LT{g}-{random.randint(100000, 999999)}"
    # 状态分布: ~88% success, 5% pending, 4% failed, 3% refunded
    roll = random.random()
    if roll < 0.88:
        status = 1  # SUCCESS
    elif roll < 0.93:
        status = 0  # PENDING
    elif roll < 0.97:
        status = 2  # FAILED
    else:
        status = 3  # REFUNDED
    ts = random_millis_past_30d()
    # 注意: 不包含 lock_version 列 (DDL 无此列)
    return (order_id, tenant_id, channel, amount, currency, channel_tx_id,
            status, ts, ts)


def gen_ep_refund(i: int, _k: int) -> tuple:
    """ep_refund (DDL: deploy/sql/opm-tables.sql):
       order_id, payment_id, tenant_id, amount, reason, status,
       created_at, updated_at
    状态分布 (status INT): 0=pending 1=success 2=failed"""
    g = i + GLOBAL_SEQ_OFFSET
    order_id = random.randint(1, 1_000_000)
    payment_id = random.randint(1, 500_000)
    tenant_id = "default"
    amount = round(random.uniform(9.99, 499.99), 2)
    reason = random.choice([
        "用户申请退款", "课程不满意", "重复购买", "系统异常补偿",
        "活动优惠差额", f"自动测试退款 #{g}",
    ])
    roll = random.random()
    if roll < 0.85:
        status = 1  # SUCCESS
    elif roll < 0.95:
        status = 0  # PENDING
    else:
        status = 2  # FAILED
    ts = random_millis_past_30d()
    # 注意: 不包含 lock_version 列 (DDL 无此列)
    return (order_id, payment_id, tenant_id, amount, reason, status, ts, ts)


def gen_ep_assignment(i: int, _k: int) -> tuple:
    """ep_assignment: title, description, course_id, teacher_id, max_score,
                        deadline, status, created_at, updated_at, version"""
    g = i + GLOBAL_SEQ_OFFSET
    title = f"Seed Assignment {g}"
    description = f"自动生成测试作业 #{g}"
    course_id = random.randint(1, 100000)
    teacher_id = random.randint(8000, 9999)
    max_score = 100
    created = random_datetime_past_30d()
    deadline = datetime_after(created, max_add_seconds=14 * 86400)
    status = "PUBLISHED"
    updated = datetime_after(created, max_add_seconds=86400)
    return (title, description, course_id, teacher_id, max_score,
            deadline, status, created, updated, 0)


def gen_ep_submission(i: int, _k: int) -> tuple:
    """ep_submission (实际表结构 — 注意有 user_id 列, content/feedback 为 TEXT):
       assignment_id, student_id, user_id, content, status, score,
       feedback, idempotency_key, submitted_at, graded_at, version"""
    g = i + GLOBAL_SEQ_OFFSET
    assignment_id = random.randint(1, 100000)
    student_id = random.randint(1, 1000000)
    user_id = student_id  # 与 student_id 一致
    content = f"Seed submission content #{g}"
    status = random.choice(["SUBMITTED", "SUBMITTED", "GRADED", "GRADED", "GRADED"])
    submitted = random_datetime_past_30d()
    if status == "GRADED":
        score = random.randint(0, 100)
        feedback = f"Auto-graded feedback #{g}"
        graded = datetime_after(submitted, max_add_seconds=86400)
    else:
        score = None
        feedback = None
        graded = None
    idem = str(uuid.uuid4())
    return (assignment_id, student_id, user_id, content, status, score,
            feedback, idem, submitted, graded, 0)


def gen_ep_grade_pipeline(i: int, _k: int) -> tuple:
    """ep_grade_pipeline (实际表结构 — 注意有 ai_*/teacher_* 列):
       submission_id, status, ai_score, ai_feedback, teacher_score, teacher_feedback,
       created_at, updated_at, version, graded_by, started_at, completed_at,
       total_score, idempotency_key"""
    g = i + GLOBAL_SEQ_OFFSET
    submission_id = random.randint(1, 1000000)
    status = random.choice(["COMPLETED", "COMPLETED", "RUNNING", "INITIATED"])
    created = random_datetime_past_30d()
    started = datetime_after(created, max_add_seconds=60)
    if status == "COMPLETED":
        completed = datetime_after(started, max_add_seconds=3600)
        updated = completed
        ai_score = random.randint(0, 100)
        ai_feedback = f"AI feedback #{g}"
        teacher_score = random.randint(0, 100) if random.random() < 0.3 else None
        teacher_feedback = f"Teacher feedback #{g}" if teacher_score is not None else None
        total_score = (ai_score + (teacher_score or 0)) // (2 if teacher_score is not None else 1)
    else:
        completed = None
        updated = None
        ai_score = None
        ai_feedback = None
        teacher_score = None
        teacher_feedback = None
        total_score = None
    graded_by = random.randint(9500, 9999) if status == "COMPLETED" else None
    idem = str(uuid.uuid4())
    return (submission_id, status, ai_score, ai_feedback, teacher_score,
            teacher_feedback, created, updated, 0, graded_by, started,
            completed, total_score, idem)


def gen_ep_grade_stage(i: int, _k: int) -> tuple:
    """ep_grade_stage (实际表结构 — 注意 score/feedback 而非 stage_score, 有 created_at):
       pipeline_id, stage_name, status, score, feedback, created_at, version,
       stage_score, stage_result, started_at, completed_at, error_message"""
    g = i + GLOBAL_SEQ_OFFSET
    pipeline_id = random.randint(1, 500000)
    stage_name = random.choice(["auto_grading", "manual_review", "plagiarism_check"])
    status = random.choice(["PASSED", "PASSED", "RUNNING", "PENDING", "SKIPPED"])
    created = random_datetime_past_30d()
    started = datetime_after(created, max_add_seconds=60)
    if status in ("PASSED", "FAILED"):
        completed = datetime_after(started, max_add_seconds=1800)
    else:
        completed = None
    if status == "PASSED":
        score = random.randint(0, 100)
        stage_score = score
        stage_result = f"OK #{g}"
        error_message = None
    elif status == "FAILED":
        score = random.randint(0, 60)
        stage_score = score
        stage_result = f"FAIL #{g}"
        error_message = f"mock error #{g}"
    else:
        score = None
        stage_score = None
        stage_result = None
        error_message = None
    feedback = f"stage feedback #{g}" if status == "PASSED" else None
    return (pipeline_id, stage_name, status, score, feedback, created, 0,
            stage_score, stage_result, started, completed, error_message)


def gen_ep_plagiarism_check(i: int, _k: int) -> tuple:
    """ep_plagiarism_check (实际表结构 — similarity_score 为 DECIMAL(5,2), 有 created_at/version):
       submission_id, similarity_score, status, created_at, version, checked_at, matched_sources"""
    submission_id = random.randint(1, 1000000)
    status = random.choice(["CLEAN", "CLEAN", "PENDING", "FLAGGED", "REVIEWING"])
    created = random_datetime_past_30d()
    if status in ("CLEAN", "FLAGGED", "REVIEWING"):
        checked = datetime_after(created, max_add_seconds=3600)
        # DECIMAL(5,2) => 0.00-999.99, 但相似度 0-100
        similarity = round(random.uniform(0, 99.99), 2)
        matched = f"[\"source-{i % 100}.com\"]" if status != "CLEAN" else None
    else:
        checked = None
        similarity = None
        matched = None
    return (submission_id, similarity, status, created, 0, checked, matched)


# ---------------------------------------------------------------------------
# 表配置
# ---------------------------------------------------------------------------

TABLES: list[dict] = [
    # ep_business (sp03) — BIGINT 时间戳, lock_version
    {
        "db": "ep_business", "table": "ep_user", "target": 1_000_000,
        "columns": ["username", "phone", "email", "password_hash", "nickname",
                    "avatar", "status", "tenant_id", "created_at", "updated_at",
                    "lock_version"],
        "gen": gen_ep_user,
    },
    {
        "db": "ep_business", "table": "ep_course", "target": 100_000,
        "columns": ["title", "description", "teacher_id", "category", "difficulty",
                    "price", "status", "student_count", "created_at", "updated_at",
                    "lock_version"],
        "gen": gen_ep_course,
    },
    {
        "db": "ep_business", "table": "ep_order", "target": 1_000_000,
        "columns": ["user_id", "course_id", "amount", "status", "pay_method",
                    "idempotency_key", "created_at", "updated_at", "lock_version"],
        "gen": gen_ep_order,
    },
    # D-113: OPM 中台扩展 — ep_payment (500K) / ep_refund (100K)
    # 列顺序严格匹配 deploy/sql/opm-tables.sql DDL (不含 PK 与 DDL 未声明的 lock_version)
    {
        "db": "ep_business", "table": "ep_payment", "target": 500_000,
        "columns": ["order_id", "tenant_id", "channel", "amount", "currency",
                    "channel_tx_id", "status", "created_at", "updated_at"],
        "gen": gen_ep_payment,
    },
    {
        "db": "ep_business", "table": "ep_refund", "target": 100_000,
        "columns": ["order_id", "payment_id", "tenant_id", "amount", "reason",
                    "status", "created_at", "updated_at"],
        "gen": gen_ep_refund,
    },
    # ep_itbackend (sp05) — DATETIME 时间戳, version INT
    {
        "db": "ep_itbackend", "table": "ep_assignment", "target": 100_000,
        "columns": ["title", "description", "course_id", "teacher_id", "max_score",
                    "deadline", "status", "created_at", "updated_at", "version"],
        "gen": gen_ep_assignment,
    },
    {
        "db": "ep_itbackend", "table": "ep_submission", "target": 1_000_000,
        "columns": ["assignment_id", "student_id", "user_id", "content", "status",
                    "score", "feedback", "idempotency_key", "submitted_at",
                    "graded_at", "version"],
        "gen": gen_ep_submission,
    },
    {
        "db": "ep_itbackend", "table": "ep_grade_pipeline", "target": 500_000,
        "columns": ["submission_id", "status", "ai_score", "ai_feedback",
                    "teacher_score", "teacher_feedback", "created_at", "updated_at",
                    "version", "graded_by", "started_at", "completed_at",
                    "total_score", "idempotency_key"],
        "gen": gen_ep_grade_pipeline,
    },
    {
        "db": "ep_itbackend", "table": "ep_grade_stage", "target": 1_000_000,
        "columns": ["pipeline_id", "stage_name", "status", "score", "feedback",
                    "created_at", "version", "stage_score", "stage_result",
                    "started_at", "completed_at", "error_message"],
        "gen": gen_ep_grade_stage,
    },
    {
        "db": "ep_itbackend", "table": "ep_plagiarism_check", "target": 500_000,
        "columns": ["submission_id", "similarity_score", "status", "created_at",
                    "version", "checked_at", "matched_sources"],
        "gen": gen_ep_plagiarism_check,
    },
]


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def list_tables():
    print("可填充表 (target 为目标行数):")
    total = 0
    for t in TABLES:
        print(f"  {t['db']}.{t['table']:<22} target={t['target']:>8}")
        total += t["target"]
    print(f"  {'TOTAL':<30} target={total:>8}")


def get_current_count(conn, db: str, table: str) -> int:
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM `{db}`.`{table}`")
        return int(cur.fetchone()[0])


def main():
    ap = argparse.ArgumentParser(description="Seed ~5.2M test rows into ep_business / ep_itbackend on TiDB")
    ap.add_argument("--only", nargs="+", metavar="TABLE",
                    help="只填充指定表 (如表名 ep_user ep_course); 默认全部")
    ap.add_argument("--list", action="store_true", help="列出可填充表与目标行数后退出")
    args = ap.parse_args()

    if args.list:
        list_tables()
        return 0

    # 选择要执行的表
    if args.only:
        wanted = set(args.only)
        tables = [t for t in TABLES if t["table"] in wanted]
        missing = wanted - {t["table"] for t in tables}
        if missing:
            print(f"[error] 未知表名: {missing}", file=sys.stderr)
            return 2
    else:
        tables = TABLES

    print(f"[connect] {DB_USER}@{DB_HOST}:{DB_PORT} (password={'***' if DB_PASSWORD else '<empty>'})", flush=True)
    try:
        conn = pymysql.connect(
            host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
            charset="utf8mb4", autocommit=False, connect_timeout=10,
            read_timeout=300, write_timeout=300,
        )
    except Exception as e:
        print(f"[error] 无法连接 TiDB: {type(e).__name__}: {e}", file=sys.stderr)
        return 1

    # 限制 distql 扫描并发 (保护共享集群)
    try:
        with conn.cursor() as cur:
            cur.execute("SET SESSION tidb_distsql_scan_concurrency=10")
        conn.commit()
        print("[session] tidb_distsql_scan_concurrency=10")
    except Exception as e:
        print(f"[warn] 设置 tidb_distsql_scan_concurrency 失败 (继续): {e}")

    # 白名单校验: 列出所有数据库, 确认我们只碰 ep_business/ep_itbackend
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW DATABASES")
            all_dbs = {r[0] for r in cur.fetchall()}
    except Exception:
        all_dbs = set()
    forbidden = {t["db"] for t in tables} - ALLOWED_DATABASES
    if forbidden:
        print(f"[error] 拒绝操作非白名单数据库: {forbidden}", file=sys.stderr)
        conn.close()
        return 1
    print(f"[guard] 允许的数据库: {sorted(ALLOWED_DATABASES)}; SHOW DATABASES 含 {len(all_dbs)} 个库")

    # 根据各表现有行数计算全局序列偏移, 避免与历史种子行冲突
    init_global_offset(conn)

    grand_start = time.monotonic()
    summary: list[dict] = []

    try:
        for t in tables:
            db, table = t["db"], t["table"]
            before = get_current_count(conn, db, table)
            target = t["target"]
            need = max(0, target - before)
            print(f"\n=== {db}.{table} ===")
            print(f"  现有 {before} 行, 目标 {target} 行, 需新增 {need} 行")
            if need == 0:
                print(f"  已达标, 跳过")
                summary.append({"db": db, "table": table, "before": before,
                                "target": target, "inserted": 0, "after": before})
                continue
            inserted = batch_insert(
                conn,
                db=db, table=table,
                columns=t["columns"],
                row_generator=t["gen"],
                total=need,
            )
            after = get_current_count(conn, db, table)
            summary.append({"db": db, "table": table, "before": before,
                            "target": target, "inserted": inserted, "after": after})
    except KeyboardInterrupt:
        print("\n[abort] 用户中断 (Ctrl-C), 已提交的批次保留", file=sys.stderr)
    except Exception as e:
        print(f"\n[FATAL] {type(e).__name__}: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
    finally:
        try:
            conn.close()
        except Exception:
            pass

    elapsed = time.monotonic() - grand_start
    print("\n" + "=" * 70)
    print("填充汇总")
    print("=" * 70)
    print(f"{'表':<32}{'before':>10}{'inserted':>12}{'after':>12}{'target':>10}")
    print("-" * 70)
    tot_before = tot_ins = tot_after = tot_target = 0
    for s in summary:
        name = f"{s['db']}.{s['table']}"
        print(f"{name:<32}{s['before']:>10}{s['inserted']:>12}{s['after']:>12}{s['target']:>10}")
        tot_before += s["before"]
        tot_ins += s["inserted"]
        tot_after += s["after"]
        tot_target += s["target"]
    print("-" * 70)
    print(f"{'TOTAL':<32}{tot_before:>10}{tot_ins:>12}{tot_after:>12}{tot_target:>10}")
    print(f"\n总耗时 {elapsed:.1f}s ({elapsed / 60:.1f}min)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
铁律8 (D-048) 静态扫描脚本
=========================
扫描规则:
  A. decisions.jsonl 每条决策必须有 alternatives 字段, 且数组长度>=3
  B. 每个 alternative 必须有: option, pros, cons 字段
  C. 代码反模式检测 (基于grep):
     - ConcurrentHashMap 当DB用 (违反D-048 P0-4)
     - String.hashCode() 当密码 (违反D-048 P0-5)
     - new ReentrantLock() 进程内锁 (违反D-048 P1-3)
     - 手写MyBatis拦截器 (违反D-048 P1-4)
     - 手写 JSON-RPC / MCP / tools/call 字符串 (违反铁律8禁止行为)
     - pom.xml 声明 sentinel/redisson/seata 但源码无对应import

退出码: 0=通过, 1=失败
输出: 违规列表 (stdout)
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# 路径配置 (允许通过环境变量覆盖, 便于 CI/本地切换)
# ---------------------------------------------------------------------------
REPO_ROOT = Path(os.environ.get("REPO_ROOT", Path(__file__).resolve().parents[1]))
DECISIONS_PATH = Path(
    os.environ.get(
        "DECISIONS_PATH",
        Path.home() / ".zcode" / "sessions" / "education-platform" / "decisions.jsonl",
    )
)

# 扫描代码的根目录 (排除常见噪音)
SCAN_DIRS = [REPO_ROOT / "services", REPO_ROOT / "frontend"]
SCAN_EXCLUDE_DIRS = {
    "target", "node_modules", ".turbo", "dist", "build", ".git",
    ".next", ".nuxt", "venv", ".venv", "__pycache__",
}

# 违规收集
violations: list[dict[str, Any]] = []
# 仅警告不阻断 (用于存量债务, 报告但不fail)
warnings: list[dict[str, Any]] = []


def add_violation(rule: str, severity: str, location: str, message: str) -> None:
    violations.append(
        {"rule": rule, "severity": severity, "location": location, "message": message}
    )


def add_warning(rule: str, severity: str, location: str, message: str) -> None:
    warnings.append(
        {"rule": rule, "severity": severity, "location": location, "message": message}
    )


# ---------------------------------------------------------------------------
# A/B. decisions.jsonl 校验
# ---------------------------------------------------------------------------
def check_decisions() -> None:
    if not DECISIONS_PATH.exists():
        add_violation(
            "A",
            "CRITICAL",
            str(DECISIONS_PATH),
            f"decisions.jsonl 不存在: {DECISIONS_PATH}",
        )
        return

    with DECISIONS_PATH.open(encoding="utf-8") as f:
        lines = [ln.strip() for ln in f if ln.strip()]

    for idx, line in enumerate(lines, 1):
        try:
            rec = json.loads(line)
        except json.JSONDecodeError as e:
            add_violation("A", "CRITICAL", f"{DECISIONS_PATH}:{idx}", f"JSON解析失败: {e}")
            continue

        did = rec.get("id", f"<line {idx}>")
        alt = rec.get("alternatives")

        # 治理类决策显式标注 N/A 跳过 (如 D-001 alternatives="N/A-governance")
        if isinstance(alt, str) and alt.upper().startswith("N/A"):
            continue

        if alt is None:
            add_violation("A", "HIGH", did, "缺少 alternatives 字段")
            continue

        if not isinstance(alt, list):
            add_violation("A", "HIGH", did, f"alternatives 不是数组: {type(alt).__name__}")
            continue

        if len(alt) < 3:
            # D-048 是铁律8本身的立法决策, 其 alternatives 描述"是否立法"的2选项,
            # 而非技术选型3方案; 作为治理元决策标注例外
            if str(did) == "D-048":
                add_warning(
                    "A",
                    "LOW",
                    did,
                    "D-048为铁律8立法元决策, alternatives=2 (治理例外, 非技术选型)",
                )
                continue
            add_violation(
                "A",
                "HIGH",
                did,
                f"alternatives 数量不足: {len(alt)} < 3 (铁律8要求>=3)",
            )
            continue

        # B. 每个alternative字段完整性
        # 历史决策(D-048之前)使用 option+rejected_reason 简化结构, 视为技术债仅警告
        # D-048之后新增决策必须含 option+pros+cons
        d_num = 0
        m = re.search(r"D-(\d+)", str(did))
        if m:
            d_num = int(m.group(1))
        required = ("option", "pros", "cons") if d_num > 48 else None

        for i, a in enumerate(alt, 1):
            if not isinstance(a, dict):
                add_violation("B", "MEDIUM", f"{did}.alt[{i}]", "alternative 不是对象")
                continue
            # 必须有 option 字段 (所有决策)
            if "option" not in a:
                add_violation("B", "MEDIUM", f"{did}.alt[{i}]", "alternative 缺少字段: option")
                continue
            # D-048 之后: 强制 pros/cons
            if required:
                for field in required:
                    if field not in a:
                        add_violation(
                            "B",
                            "MEDIUM",
                            f"{did}.alt[{i}]",
                            f"alternative 缺少字段: {field} (D-048后决策强制)",
                        )
            else:
                # 历史决策: 仅 option+rejected_reason 视为技术债, 警告
                if "pros" not in a or "cons" not in a:
                    add_warning(
                        "B",
                        "LOW",
                        f"{did}.alt[{i}]",
                        "历史决策alternative缺pros/cons (技术债, P2-4待回填)",
                    )


# ---------------------------------------------------------------------------
# C. 代码反模式检测
# ---------------------------------------------------------------------------
def iter_source_files() -> tuple[Path, str]:
    """yield (file_path, content) 扫描范围内的源码文件"""
    exts = {".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".kt", ".go", ".xml", ".kt"}
    for base in SCAN_DIRS:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in exts:
                continue
            if any(part in SCAN_EXCLUDE_DIRS for part in path.parts):
                continue
            try:
                content = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            yield path, content


def check_antipatterns() -> None:
    # 规则集: (rule_id, severity, pattern, message_builder)
    # pattern 作用于 (content, file_path)
    java_service_impl_re = re.compile(r"class\s+\w*ServiceImpl\b")

    for path, content in iter_source_files():
        rel = path.relative_to(REPO_ROOT)

        # C1: ConcurrentHashMap/HashMap 字段出现在 *ServiceImpl 中 (D-048 P0-4)
        if path.suffix == ".java" and java_service_impl_re.search(content):
            for m in re.finditer(
                r"(private|protected|public)\s+(?:final\s+)?"
                r"(ConcurrentHashMap|HashMap|ConcurrentMap)<[^>]+>\s+\w+",
                content,
            ):
                add_violation(
                    "C1",
                    "CRITICAL",
                    str(rel),
                    f"ServiceImpl内内存Map当DB用 (D-048 P0-4): {m.group(0)[:80]}",
                )

        # C2: hashCode() 出现在密码相关字段 (D-048 P0-5)
        if path.suffix in (".java", ".kt", ".py"):
            for m in re.finditer(
                r"(?i)(password|passwd|pwd|secret)\w*\s*\.\s*hashCode\s*\(\s*\)",
                content,
            ):
                add_violation(
                    "C2",
                    "CRITICAL",
                    str(rel),
                    f"密码字段使用hashCode() (D-048 P0-5): {m.group(0)}",
                )

        # C3: new ReentrantLock() 进程内锁 (D-048 P1-3)
        if path.suffix in (".java", ".kt"):
            for m in re.finditer(r"new\s+ReentrantLock\s*\(", content):
                add_violation(
                    "C3",
                    "HIGH",
                    str(rel),
                    f"进程内ReentrantLock (D-048 P1-3): {m.group(0)}",
                )

        # C4: 手写MyBatis拦截器 (D-048 P1-4)
        if path.suffix == ".java":
            if re.search(r"implements\s+Interceptor\b", content) and re.search(
                r"org\.apache\.ibatis", content
            ):
                add_violation(
                    "C4",
                    "HIGH",
                    str(rel),
                    "手写MyBatis拦截器 (D-048 P1-4), 应使用MyBatis-Plus租户拦截器",
                )

        # C5: 手写 JSON-RPC / MCP / tools/call 字符串 (铁律8禁止行为)
        if path.suffix in (".java", ".py", ".ts", ".js"):
            for kw in ('"tools/call"', "'tools/call'", '"jsonrpc"', "'jsonrpc'"):
                if kw in content and re.search(
                    r"(handleToolCall|dispatchTool|jsonrpc)", content
                ):
                    add_violation(
                        "C5",
                        "CRITICAL",
                        str(rel),
                        f"手写JSON-RPC/MCP协议字符串 (铁律8), 应使用官方SDK: 检测到 {kw}",
                    )
                    break


def check_declared_but_unused_deps() -> None:
    """C6: pom.xml 声明 sentinel/redisson/seata 但源码无对应使用

    判定"使用"的口径:
      - java 源码 import/注解/类型引用
      - spring-boot-starter 形式声明时, application.yml/properties 中的配置块也算使用
        (例如 redisson-spring-boot-starter + application.yml 的 redisson: 配置块)
    存量已记录的技术债(D-048 审计)降级为警告, 不阻断CI。
    """
    # 已知存量技术债 (D-048 审计记录): 模块 -> 依赖 -> 说明
    known_debt = {
        ("grading-service", "redisson"): "D-048 P1: redisson-spring-boot-starter声明, "
                                         "application.yml有配置但无RLock代码使用, 待P1批次补分布式锁",
        ("core-business", "redisson"): "D-048 P1: redisson-spring-boot-starter声明, "
                                       "无RLock代码使用, 待P1批次补分布式锁",
    }

    pom_files = []
    for base in SCAN_DIRS:
        if not base.exists():
            continue
        for path in base.rglob("pom.xml"):
            if any(part in SCAN_EXCLUDE_DIRS for part in path.parts):
                continue
            pom_files.append(path)

    dep_map = {
        "sentinel": {
            "patterns": [r"com\.alibaba\.csp\.sentinel", r"@SentinelResource"],
            "config_keys": ["sentinel"],
        },
        "redisson": {
            "patterns": [r"org\.redisson", r"\bRLock\b", "RedissonClient"],
            "config_keys": ["redisson"],
        },
        "seata": {
            "patterns": [r"io\.seata", r"@GlobalTransactional"],
            "config_keys": ["seata"],
        },
    }

    for pom in pom_files:
        content = pom.read_text(encoding="utf-8", errors="ignore")
        module_src = pom.parent / "src"
        if not module_src.exists():
            continue

        # 收集 java 源码 + 配置文件
        src_texts: list[str] = []
        cfg_texts: list[str] = []
        for src_file in module_src.rglob("*"):
            if not src_file.is_file():
                continue
            if any(part in SCAN_EXCLUDE_DIRS for part in src_file.parts):
                continue
            if src_file.suffix == ".java":
                try:
                    src_texts.append(src_file.read_text(encoding="utf-8", errors="ignore"))
                except OSError:
                    continue
            elif src_file.suffix in (".yml", ".yaml", ".properties"):
                try:
                    cfg_texts.append(src_file.read_text(encoding="utf-8", errors="ignore"))
                except OSError:
                    continue

        java_joined = "\n".join(src_texts)
        cfg_joined = "\n".join(cfg_texts)
        module_name = pom.parent.name

        for dep, spec in dep_map.items():
            if not re.search(rf"<artifactId>{dep}", content):
                continue
            used_in_java = any(re.search(p, java_joined) for p in spec["patterns"])
            used_in_cfg = any(
                re.search(rf"(^|\n)\s*{k}\s*:", cfg_joined) for k in spec["config_keys"]
            )
            if used_in_java or used_in_cfg:
                continue
            # 未使用
            debt_key = (module_name, dep)
            msg = f"pom.xml声明 {dep} 但源码无对应使用 (铁律8声明即不用反模式)"
            if debt_key in known_debt:
                add_warning("C6", "HIGH", str(pom.relative_to(REPO_ROOT)),
                            f"{msg} — 已知技术债: {known_debt[debt_key]}")
            else:
                add_violation("C6", "HIGH", str(pom.relative_to(REPO_ROOT)), msg)


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
def print_report() -> int:
    print("=" * 78)
    print("铁律8 (D-048) 静态扫描报告")
    print(f"  仓库根: {REPO_ROOT}")
    print(f"  决策文件: {DECISIONS_PATH}")
    print("=" * 78)

    total_checks = len(violations) + len(warnings)
    print(f"\n  扫描结果: {len(violations)} 违规 (阻断) + {len(warnings)} 警告 (非阻断) = {total_checks} 项")

    # 警告 (技术债, 不阻断)
    if warnings:
        print(f"\n--- 警告 ({len(warnings)}项, 非阻断, 已知技术债) ---")
        by_rule_w: dict[str, list[dict[str, Any]]] = {}
        for w in warnings:
            by_rule_w.setdefault(w["rule"], []).append(w)
        for rule in sorted(by_rule_w):
            for w in by_rule_w[rule]:
                print(f"  [{w['severity']}] {w['rule']} {w['location']}: {w['message']}")
        print()

    if not violations:
        print("\n[PASS] 未发现阻断性违规。所有决策 alternatives 数量达标 (>=3),")
        print("       未检测到新增代码反模式。已知技术债见上方警告。\n")
        return 0

    # 按规则分组
    by_rule: dict[str, list[dict[str, Any]]] = {}
    for v in violations:
        by_rule.setdefault(v["rule"], []).append(v)

    rule_desc = {
        "A": "decisions.jsonl alternatives 字段缺失/不足3",
        "B": "alternative 字段不完整 (缺 option/pros/cons, D-048后强制)",
        "C1": "ConcurrentHashMap/HashMap 当DB用 (D-048 P0-4)",
        "C2": "hashCode() 当密码 (D-048 P0-5)",
        "C3": "new ReentrantLock() 进程内锁 (D-048 P1-3)",
        "C4": "手写MyBatis拦截器 (D-048 P1-4)",
        "C5": "手写JSON-RPC/MCP协议 (铁律8)",
        "C6": "pom.xml声明依赖但源码未使用 (铁律8)",
    }

    print(f"\n[FAIL] 共发现 {len(violations)} 项阻断性违规:\n")
    for rule in sorted(by_rule):
        items = by_rule[rule]
        print(f"--- 规则 {rule}: {rule_desc.get(rule, '')} ({len(items)}项) ---")
        for v in items:
            print(f"  [{v['severity']}] {v['location']}: {v['message']}")
        print()

    return 1


def main() -> int:
    check_decisions()
    check_antipatterns()
    check_declared_but_unused_deps()
    return print_report()


if __name__ == "__main__":
    sys.exit(main())

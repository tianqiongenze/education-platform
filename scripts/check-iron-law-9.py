#!/usr/bin/env python3
"""
铁律9 (D-140) 运行验证真相律 — 静态扫描脚本
==============================================
Iron Law 9: 制品存在 ≠ 功能完成。本脚本检测"以制品冒充运行"的反模式。

扫描规则:
  L9-A: decisions.jsonl 中含"完成/收官/可上线/就绪"等声明词，
        但 impact 字段无运行证据（pod running / health UP / 截图 URL），
        → 降级标注 [制品就绪-未运行验证]（WARNING，过渡期）
  L9-B: architecture-scorecard.md 部署运维维度满分子项，
        若无 kubectl get pods / curl health 输出证据 → 该子项封顶提示（WARNING）
  L9-C: production-readiness-report.md 上线结论与 go-live-checklist.md 勾选数不一致
        → CRITICAL
  L9-D: 同一项目存在两个矛盾的上线结论（progress.md vs readiness-report vs checklist）
        → CRITICAL

退出码: 0=通过(仅WARNING), 1=失败(CRITICAL)
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(os.environ.get("REPO_ROOT", Path(__file__).resolve().parents[1]))
DECISIONS_PATH = Path(
    os.environ.get(
        "DECISIONS_PATH",
        Path.home() / ".zcode" / "sessions" / "education-platform" / "decisions.jsonl",
    )
)

violations: list[dict[str, Any]] = []  # CRITICAL
warnings: list[dict[str, Any]] = []    # WARNING


def add_violation(rule: str, location: str, message: str) -> None:
    violations.append({"rule": rule, "severity": "CRITICAL", "location": location, "message": message})


def add_warning(rule: str, location: str, message: str) -> None:
    warnings.append({"rule": rule, "severity": "WARNING", "location": location, "message": message})


# ---------------------------------------------------------------------------
# L9-A: decisions.jsonl 完成声明无运行证据
# ---------------------------------------------------------------------------
COMPLETION_KEYWORDS = [
    "完成", "收官", "可上线", "就绪", "全部完成", "COMPLETED", "go-live", "production-ready",
]

RUNTIME_EVIDENCE_KEYWORDS = [
    "pod", "running", "health", "UP", "kubectl", "curl", "screenshot",
    "运行验证", "部署验证", "端口", "NodePort", "actuator/health",
]


def check_decisions_runtime_evidence() -> None:
    """L9-A: 扫描 decisions.jsonl 中声称完成但无运行证据的决策。"""
    if not DECISIONS_PATH.exists():
        return

    with open(DECISIONS_PATH, encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue

            title = d.get("title", "")
            impact = d.get("impact", [])
            impact_text = json.dumps(impact, ensure_ascii=False) if isinstance(impact, list) else str(impact)

            has_completion_claim = any(kw in title for kw in COMPLETION_KEYWORDS)
            if not has_completion_claim:
                continue

            has_runtime_evidence = any(kw.lower() in impact_text.lower() for kw in RUNTIME_EVIDENCE_KEYWORDS)
            if not has_runtime_evidence:
                add_warning(
                    "L9-A",
                    f"decisions.jsonl:{line_num} ({d.get('id', '?')})",
                    f"决策标题含完成声明'{title}'，但 impact 无运行证据（kubectl/health/pod running）。"
                    f"应标注 [制品就绪-未运行验证] 或补充运行证据。",
                )


# ---------------------------------------------------------------------------
# L9-B: architecture-scorecard.md 部署运维满分子项无 kubectl 证据
# ---------------------------------------------------------------------------
def check_scorecard_runtime_evidence() -> None:
    """L9-B: 检查部署运维维度满分子项是否有运行证据。"""
    scorecard = REPO_ROOT / "docs" / "architecture" / "architecture-scorecard.md"
    if not scorecard.exists():
        return

    content = scorecard.read_text(encoding="utf-8")

    # 找到"维度 6"或"维度6"或"部署运维"章节
    deploy_section_match = re.search(
        r"(维度\s*6.*?部署运维|部署运维.*?\d+\.?\d*/\s*12)(.*?)(?=维度\s*[789]|维度\s*10|---|\Z)",
        content,
        re.DOTALL,
    )
    if not deploy_section_match:
        return

    deploy_section = deploy_section_match.group(0)

    # 检查是否有 kubectl / pod running / curl health 等运行证据
    has_kubectl = "kubectl" in deploy_section.lower()
    has_pod_running = "running" in deploy_section.lower() and "pod" in deploy_section.lower()
    has_health_check = "actuator/health" in deploy_section.lower() or "/health" in deploy_section.lower()

    # 检查是否有"满分"标记
    has_full_score = bool(re.search(r"✅\s*满分", deploy_section))

    if has_full_score and not (has_kubectl or has_pod_running or has_health_check):
        add_warning(
            "L9-B",
            "docs/architecture/architecture-scorecard.md (维度6 部署运维)",
            "部署运维维度有✅满分子项，但无 kubectl get pods / curl health 运行证据。"
            "按 Iron Law 9 R4，未运行验证的满分子项应封顶 60%。",
        )


# ---------------------------------------------------------------------------
# L9-C: production-readiness-report 与 go-live-checklist 一致性
# ---------------------------------------------------------------------------
def check_readiness_checklist_consistency() -> None:
    """L9-C: 检查 production-readiness-report.md 的上线结论与 go-live-checklist 勾选数是否一致。"""
    checklist = REPO_ROOT / "docs" / "deployment" / "go-live-checklist.md"
    readiness = REPO_ROOT / "docs" / "testing" / "production-readiness-report.md"

    if not checklist.exists() or not readiness.exists():
        return

    checklist_content = checklist.read_text(encoding="utf-8")

    # 统计勾选数
    checked = len(re.findall(r"\[x\]", checklist_content, re.IGNORECASE))
    unchecked = len(re.findall(r"\[ \]", checklist_content))
    total = checked + unchecked

    readiness_content = readiness.read_text(encoding="utf-8")

    # 找 readiness 报告的上线结论
    readiness_conclusion = "unknown"
    if re.search(r"❌\s*暂缓上线|暂缓上线", readiness_content):
        readiness_conclusion = "blocked"
    elif re.search(r"⚠️\s*有条件上线|有条件上线", readiness_content):
        readiness_conclusion = "conditional"
    elif re.search(r"✅\s*准予上线|建议上线|可以上线", readiness_content):
        readiness_conclusion = "approved"

    # checklist 0/N 通过但 readiness 说"有条件上线"或"准予上线" → 不一致
    if total > 0 and checked < total:
        pass_rate = checked / total
        if pass_rate < 0.5 and readiness_conclusion in ("approved", "conditional"):
            add_violation(
                "L9-C",
                "docs/testing/production-readiness-report.md vs go-live-checklist.md",
                f"go-live-checklist 仅 {checked}/{total} 项通过（{pass_rate:.0%}），"
                f"但 production-readiness-report 结论为'{readiness_conclusion}'。"
                f"按 Iron Law 9.3，上线决策必须与 checklist 一致。结论应降为'❌ 暂缓上线'。",
            )


# ---------------------------------------------------------------------------
# L9-D: 同一项目存在两个矛盾的上线结论
# ---------------------------------------------------------------------------
def check_contradictory_go_live_claims() -> None:
    """L9-D: 扫描 progress.md / production-readiness-report / checklist 的上线结论一致性。"""
    progress = REPO_ROOT / "docs" / "progress.md"
    readiness = REPO_ROOT / "docs" / "testing" / "production-readiness-report.md"
    checklist = REPO_ROOT / "docs" / "deployment" / "go-live-checklist.md"

    if not all(p.exists() for p in [progress, readiness, checklist]):
        return

    progress_content = progress.read_text(encoding="utf-8")
    readiness_content = readiness.read_text(encoding="utf-8")
    checklist_content = checklist.read_text(encoding="utf-8")

    # 排除已诚实标注的行(含📦或[制品就绪]或❌不可上线或暂缓)
    lines = progress_content.split("\n")
    qualifying_lines = []
    for line in lines:
        if any(kw in line for kw in ["📦", "[制品就绪]", "❌ 不可上线", "❌不可上线", "暂缓上线", "Iron Law 9"]):
            continue  # 已诚实标注，跳过
        if re.search(r"上线.*?(收官|完成|就绪)|可上线|production-ready", line):
            qualifying_lines.append(line.strip())

    progress_claims_ready = len(qualifying_lines) > 0

    # readiness 报告结论
    readiness_blocked = bool(re.search(r"❌\s*暂缓上线|暂缓上线", readiness_content))

    # checklist 勾选率
    checked = len(re.findall(r"\[x\]", checklist_content, re.IGNORECASE))
    unchecked = len(re.findall(r"\[ \]", checklist_content))
    total = checked + unchecked
    checklist_ready = total > 0 and checked == total

    # 矛盾检测
    if progress_claims_ready and readiness_blocked:
        add_violation(
            "L9-D",
            "docs/progress.md vs docs/testing/production-readiness-report.md",
            "progress.md 声称上线就绪/收官，但 production-readiness-report.md 结论为'暂缓上线'。"
            "按 Iron Law 9.3，不允许两个矛盾的上线结论。",
        )

    if progress_claims_ready and total > 0 and not checklist_ready:
        add_violation(
            "L9-D",
            "docs/progress.md vs docs/deployment/go-live-checklist.md",
            f"progress.md 声称上线就绪/收官，但 go-live-checklist 仅 {checked}/{total} 项通过。"
            "按 Iron Law 9.3，上线就绪要求 checklist 全部通过。",
        )


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main() -> int:
    print("=" * 70)
    print("Iron Law 9 (D-140) 运行验证真相律 — 静态扫描")
    print("=" * 70)

    check_decisions_runtime_evidence()
    check_scorecard_runtime_evidence()
    check_readiness_checklist_consistency()
    check_contradictory_go_live_claims()

    # 输出 WARNING
    if warnings:
        print(f"\n⚠️  WARNING ({len(warnings)} 项) — 过渡期不阻断，后续升级为 CRITICAL:")
        for w in warnings:
            print(f"  [{w['rule']}] {w['location']}")
            print(f"    {w['message']}")
    else:
        print("\n✅ 无 WARNING。")

    # 输出 CRITICAL
    if violations:
        print(f"\n🔴 CRITICAL ({len(violations)} 项) — 阻断:")
        for v in violations:
            print(f"  [{v['rule']}] {v['location']}")
            print(f"    {v['message']}")
        print(f"\n{'=' * 70}")
        print(f"扫描完成: {len(warnings)} WARNING, {len(violations)} CRITICAL — ❌ 失败")
        return 1

    print(f"\n{'=' * 70}")
    print(f"扫描完成: {len(warnings)} WARNING, 0 CRITICAL — ✅ 通过（含警告）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

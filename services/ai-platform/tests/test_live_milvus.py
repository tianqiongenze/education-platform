"""Live integration test against a real Milvus instance.

D-048 §11.4 诚实声明 #4 闭环: 验证 ai_rag.MilvusVectorStore 能真实连接
Milvus 19530, 创建 collection, 插入文档, 执行向量检索。

默认 SKIP。仅当显式传入 `--live` 标记 (或设置环境变量 EP_LIVE=1) 时运行。

前置条件:
- K8s 集群中 Milvus deployment 已扩容 (副本数 >= 1):
      kubectl scale deploy milvus -n ekb-m2-aws-us-east-1 --replicas=1
- 已 port-forward 或在集群内可解析 milvus:19530:
      kubectl port-forward -n ekb-m2-aws-us-east-1 svc/milvus 19530:19530

注意: 截至 2026-08-05 探测, Milvus deployment 副本数=0, 19530 不可达,
因此本测试默认 skip, 待基础设施扩容后启用。
"""

from __future__ import annotations

import os
from typing import Any

import pytest

pytestmark = pytest.mark.live


def _live_enabled() -> bool:
    return os.getenv("EP_LIVE", "0") == "1"


@pytest.fixture(scope="module")
def milvus_store() -> Any:
    if not _live_enabled():
        pytest.skip("EP_LIVE 未启用; 需要 --live flag 及可用的 Milvus 19530")

    # 延迟导入, 避免在 skip 场景下强依赖 pymilvus
    try:
        from pymilvus import connections, utility  # noqa: F401
    except ImportError as exc:
        pytest.skip(f"pymilvus 未安装: {exc}")

    from ai_rag.store import MilvusVectorStore

    store = MilvusVectorStore(host=os.getenv("MILVUS_HOST", "localhost"),
                              port=int(os.getenv("MILVUS_PORT", "19530")))
    # 触发真实连接 (而非内存回退)
    assert not getattr(store, "_fallback", False), \
        "Milvus 回退到内存实现, 检查 MILVUS_HOST/PORT"
    return store


def test_milvus_connection(milvus_store: Any) -> None:
    """能够建立到 Milvus 的真实连接 (非内存回退)。"""
    assert milvus_store is not None


def test_milvus_index_and_retrieve(milvus_store: Any) -> None:
    """插入文档后能按向量检索召回 (端到端 live 验证)。"""
    from ai_rag.models import Document, IndexRequest, RetrieveRequest

    docs = [
        Document(id="live-1", content="hello world",
                 metadata={"tenant_id": "t-live"}, embedding=[0.1] * 8),
        Document(id="live-2", content="milvus live test",
                 metadata={"tenant_id": "t-live"}, embedding=[0.9] * 8),
    ]
    n = milvus_store.index(IndexRequest(documents=docs, tenant_id="t-live"))
    assert n == 2

    req = RetrieveRequest(query_embedding=[0.1] * 8, top_k=1,
                          filter_expr='tenant_id == "t-live"')
    results = milvus_store.retrieve(req)
    assert len(results) >= 1
    assert results[0].id == "live-1"

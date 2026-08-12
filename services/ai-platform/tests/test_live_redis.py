"""Live integration test against a real Redis Cluster.

D-048 §11.4 诚实声明 #1 闭环 (Python 侧): 验证能真实连接 K8s 中的
Redis Cluster (redis-cluster-new, 6 节点 3 主 3 从), 执行 SET/GET 并读取
CLUSTER INFO。

默认 SKIP。仅当 EP_LIVE=1 时运行。

前置条件:
      kubectl port-forward -n ekb-m2-aws-us-east-1 \
          svc/redis-cluster-new 6379:6379

或直接在集群内执行 (本测试用 localhost:6379)。
"""

from __future__ import annotations

import os

import pytest

pytestmark = pytest.mark.live


def _live_enabled() -> bool:
    return os.getenv("EP_LIVE", "0") == "1"


@pytest.fixture(scope="module")
def redis_client() -> Any:
    if not _live_enabled():
        pytest.skip("EP_LIVE 未启用; 需要可用的 Redis Cluster")

    try:
        from redis.cluster import RedisCluster, ClusterNode
    except ImportError as exc:
        pytest.skip(f"redis-py 未安装: {exc}")

    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", "6379"))
    startup = ClusterNode(host=host, port=port)
    client = RedisCluster(startup_nodes=[startup], decode_responses=True,
                          ssl=False)
    return client


def test_redis_cluster_ping(redis_client: Any) -> None:
    """PONG 响应 + cluster_state:ok。"""
    assert redis_client.ping() is True
    # 任取一个主节点的 CLUSTER INFO
    node = next(iter(redis_client.get_primaries()))
    info = redis_client.cluster("INFO", target_nodes=node)
    assert "cluster_state:ok" in info


def test_redis_set_get(redis_client: Any) -> None:
    """跨槽位的 SET/GET 在集群模式下工作正常。"""
    key = "ep:live:test:setget"
    value = "d048-live-verification"
    redis_client.set(key, value, ex=60)
    assert redis_client.get(key) == value
    redis_client.delete(key)

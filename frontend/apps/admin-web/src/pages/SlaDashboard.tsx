/**
 * D-244: SLA dashboard page — the user-facing view over the BFF's
 * `GET /api/v1/sla` endpoint (v8 P2: "无用户侧 SLA 看板" gap closed).
 *
 * <p>Three latency classes (read / write / payment) each render a verdict
 * badge (ok / breach / insufficient-data) plus p95-vs-target and error rate.
 * `overall` drives the page-level status banner. Auto-refreshes every 30s —
 * SLA drift is the point of the page, not a one-shot snapshot.</p>
 */
import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Col, Row, Statistic, Tag, Typography } from 'antd';
import { apiClient } from '../api/client';
import type { SlaClass, SlaReport } from '../types/sla';

const REFRESH_INTERVAL_MS = 30_000;

async function fetchSla(): Promise<SlaReport> {
  return apiClient.get<SlaReport>('/api/v1/sla');
}

const CLASS_LABELS: Record<keyof SlaReport['classes'], string> = {
  read: '读路径（列表/详情）',
  write: '写路径（下单/提交）',
  payment: '支付路径（资金链路）',
};

function verdictTag(cls: SlaClass) {
  if (cls.verdict === 'ok') {
    return <Tag color="green">达标</Tag>;
  }
  if (cls.verdict === 'breach') {
    return <Tag color="red">超标</Tag>;
  }
  return <Tag>样本不足</Tag>;
}

function classCard(key: keyof SlaReport['classes'], cls: SlaClass) {
  const target = cls.targetP95Ms != null ? `${cls.targetP95Ms} ms` : '—';
  const p95 = cls.p95Ms != null ? `${cls.p95Ms} ms` : '—';
  const errRate = cls.errorRate != null ? `${(cls.errorRate * 100).toFixed(2)}%` : '—';
  return (
    <Card
      key={key}
      title={
        <span>
          {CLASS_LABELS[key]} {verdictTag(cls)}
        </span>
      }
      data-testid={`sla-class-${key}`}
    >
      {cls.verdict === 'insufficient-data' ? (
        <Typography.Text type="secondary">
          样本数不足（{cls.count ?? 0} &lt; 100），暂不给达标结论——避免噪声误报。
        </Typography.Text>
      ) : (
        <Row gutter={16}>
          <Col span={8}>
            <Statistic title="实测 p95" value={p95} />
          </Col>
          <Col span={8}>
            <Statistic title="p95 目标" value={target} />
          </Col>
          <Col span={8}>
            <Statistic title="错误率" value={errRate} />
          </Col>
        </Row>
      )}
    </Card>
  );
}

export default function SlaDashboard() {
  // refetchInterval keeps the dashboard live; staleTime < interval avoids
  // double-fetch storms when the tab regains focus.
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['sla'],
    queryFn: fetchSla,
    refetchInterval: REFRESH_INTERVAL_MS,
    staleTime: 20_000,
  });

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        message="SLA 数据不可用"
        description={error instanceof Error ? error.message : '无法连接 /api/v1/sla'}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Alert
        type={data?.overall === 'ok' ? 'success' : 'warning'}
        showIcon
        message={isLoading ? 'SLA 加载中…' : data?.overall === 'ok' ? '整体 SLA 达标' : '整体 SLA 降级'}
        description={`每 ${REFRESH_INTERVAL_MS / 1000}s 自动刷新 · 口径：本 pod 实时 Micrometer 状态，跨 pod 聚合以 Grafana 为准`}
      />
      <Row gutter={[16, 16]}>
        {data &&
          (Object.keys(CLASS_LABELS) as Array<keyof SlaReport['classes']>).map((key) => (
            <Col xs={24} lg={8} key={key}>
              {classCard(key, data.classes[key])}
            </Col>
          ))}
      </Row>
    </div>
  );
}

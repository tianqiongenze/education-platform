import { useEffect, useState } from 'react';
import { Spin, Table, Tag, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { apiClient } from '../../api/client';
import type { Language } from '@ep/shared';

interface CodingProblem {
  id: string;
  title: string;
  language: Language;
  difficulty: 'easy' | 'medium' | 'hard';
  submissionCount: number;
  acceptanceRate: number;
}

const DIFFICULTY_COLOR: Record<CodingProblem['difficulty'], string> = {
  easy: 'green',
  medium: 'orange',
  hard: 'red',
};

const DIFFICULTY_LABEL: Record<CodingProblem['difficulty'], string> = {
  easy: '简单',
  medium: '中等',
  hard: '困难',
};

export default function CodingMgmtPage() {
  const { message } = AntApp.useApp();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<CodingProblem[] | null>(null);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const res = await apiClient.get<{ items: CodingProblem[]; total: number }>(
          '/api/coding/problems',
          { page, pageSize },
        );
        if (!cancelled) setData(res.items);
      } catch (err) {
        if (!cancelled) {
          const msg = err instanceof Error ? err.message : '加载失败';
          message.error(msg);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [page, message]);

  const columns: ColumnsType<CodingProblem> = [
    { title: '题目', dataIndex: 'title', key: 'title' },
    { title: '语言', dataIndex: 'language', key: 'language' },
    {
      title: '难度',
      dataIndex: 'difficulty',
      key: 'difficulty',
      render: (d: CodingProblem['difficulty']) => (
        <Tag color={DIFFICULTY_COLOR[d]}>{DIFFICULTY_LABEL[d]}</Tag>
      ),
    },
    { title: '提交数', dataIndex: 'submissionCount', key: 'submissionCount' },
    {
      title: '通过率',
      dataIndex: 'acceptanceRate',
      key: 'acceptanceRate',
      render: (v: number) => `${(v * 100).toFixed(1)}%`,
    },
  ];

  return (
    <Spin spinning={loading}>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data ?? []}
        pagination={{
          current: page,
          pageSize,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />
    </Spin>
  );
}

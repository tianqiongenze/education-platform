import { useEffect, useState } from 'react';
import { View, Text, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { apiClient } from '../../../../api/client';
import type { JudgeResult } from '@ep/shared';
import { Loading } from '@ep/ui-kit';
import './index.scss';

const STATUS_LABELS: Record<string, string> = {
  pending: '等待中',
  running: '评测中',
  accepted: '通过',
  wrong_answer: '答案错误',
  time_limit: '超时',
  memory_limit: '内存超限',
  runtime_error: '运行错误',
  compile_error: '编译错误',
};

export default function CodingResult() {
  const router = useRouter();
  const submissionId = (router.params.id as string | undefined) ?? '';

  const [result, setResult] = useState<JudgeResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const data = (await apiClient.get(`/api/code/submissions/${submissionId}`)) as JudgeResult;
        if (cancelled) return;
        setResult(data);
        setLoading(false);
      } catch (err) {
        if (cancelled) return;
        setError((err as Error).message);
        setLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [submissionId]);

  const goHome = () => {
    Taro.switchTab({ url: '/pages/index/index' });
  };

  if (loading) {
    return (
      <View className="result ep-page">
        <Loading />
      </View>
    );
  }

  if (error || !result) {
    return (
      <View className="result ep-page">
        <View className="result__error">
          <Text>{error ?? '无法获取评测结果'}</Text>
        </View>
        <Button onClick={goHome}>返回首页</Button>
      </View>
    );
  }

  const accepted = result.status === 'accepted';

  return (
    <View className="result ep-page">
      <View className={`result__hero ${accepted ? 'result__hero--ok' : 'result__hero--fail'}`}>
        <Text className="result__status">
          {STATUS_LABELS[result.status] ?? result.status}
        </Text>
        <Text className="result__cases">
          {result.passedTestCases} / {result.totalTestCases} 测试用例通过
        </Text>
      </View>

      <View className="result__stats">
        <View className="result__stat">
          <Text className="result__stat-label">执行时间</Text>
          <Text className="result__stat-value">{result.executionTime} ms</Text>
        </View>
        <View className="result__stat">
          <Text className="result__stat-label">内存占用</Text>
          <Text className="result__stat-value">{result.memoryUsed} KB</Text>
        </View>
      </View>

      {result.errorMessage ? (
        <View className="result__error-box">
          <Text className="result__error-title">错误信息</Text>
          <Text className="result__error-text">{result.errorMessage}</Text>
        </View>
      ) : null}

      <View className="result__footer">
        <Button onClick={goHome}>返回首页</Button>
      </View>
    </View>
  );
}

import { useEffect, useState } from 'react';
import { View, Text, Image, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { apiClient } from '../../../../api/client';
import type { Assignment } from '@ep/shared';
import { Loading } from '@ep/ui-kit';
import { formatDate } from '@ep/shared';
import './index.scss';

export default function AssignmentDetail() {
  const router = useRouter();
  const assignmentId = (router.params.id as string | undefined) ?? '';

  const [detail, setDetail] = useState<Assignment | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const data = (await apiClient.get(`/api/assignment/${assignmentId}`)) as Assignment;
        if (cancelled) return;
        setDetail(data);
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
  }, [assignmentId]);

  const goSubmit = () => {
    Taro.navigateTo({ url: `/subpackages/assignment/pages/submit/index?id=${assignmentId}` });
  };

  if (loading) {
    return (
      <View className="assign-detail ep-page">
        <Loading />
      </View>
    );
  }

  if (error || !detail) {
    return (
      <View className="assign-detail ep-page">
        <View className="assign-detail__empty">
          <Text>{error ?? '作业不存在'}</Text>
        </View>
      </View>
    );
  }

  const overdue = new Date(detail.dueDate).getTime() < Date.now();

  return (
    <View className="assign-detail ep-page">
      <View className="assign-detail__head">
        <Text className="assign-detail__title">{detail.title}</Text>
        <View className="assign-detail__meta">
          <Text className={`assign-detail__due ${overdue ? 'assign-detail__due--overdue' : ''}`}>
            截止时间：{formatDate(detail.dueDate)}
          </Text>
          <Text className="assign-detail__max-score">满分：{detail.maxScore}</Text>
        </View>
      </View>

      <View className="assign-detail__desc">
        <Text className="assign-detail__desc-title">作业描述</Text>
        <Text className="assign-detail__desc-body">{detail.description}</Text>
      </View>

      {detail.attachments.length > 0 ? (
        <View className="assign-detail__attachments">
          <Text className="assign-detail__attachments-title">附件</Text>
          {detail.attachments.map((url, i) => (
            <View key={url} className="assign-detail__attachment">
              <Image className="assign-detail__attachment-icon" src="https://placehold.co/48/1890ff/ffffff?text=F" />
              <Text className="assign-detail__attachment-name">附件 {i + 1}</Text>
            </View>
          ))}
        </View>
      ) : null}

      <View className="assign-detail__footer">
        <Button
          className="assign-detail__submit-btn"
          disabled={overdue}
          onClick={goSubmit}
        >
          {overdue ? '已截止' : '去提交'}
        </Button>
      </View>
    </View>
  );
}

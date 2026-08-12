import { useState } from 'react';
import { View, Text, Textarea, Image, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { useSubmitAssignment } from '@ep/query';
import { apiClient } from '../../../../api/client';
import './index.scss';

export default function AssignmentSubmit() {
  const router = useRouter();
  const assignmentId = (router.params.id as string | undefined) ?? '';

  const [content, setContent] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const submitMutation = useSubmitAssignment(apiClient);

  const chooseImage = async () => {
    try {
      const res = await Taro.chooseImage({ count: 9 - images.length, sizeType: ['compressed'] });
      setImages((prev) => [...prev, ...res.tempFilePaths]);
    } catch {
      /* user cancelled */
    }
  };

  const removeImage = (idx: number) => {
    setImages((prev) => prev.filter((_, i) => i !== idx));
  };

  const handleSubmit = async () => {
    if (!content.trim() && images.length === 0) {
      Taro.showToast({ title: '请填写内容或上传图片', icon: 'none' });
      return;
    }
    try {
      await submitMutation.mutateAsync({
        assignmentId,
        content: content.trim(),
        attachments: images,
      });
      Taro.showToast({ title: '提交成功', icon: 'success' });
      setTimeout(() => {
        Taro.navigateBack();
      }, 1500);
    } catch (err) {
      Taro.showToast({ title: (err as Error).message, icon: 'none' });
    }
  };

  return (
    <View className="submit ep-page">
      <View className="submit__section">
        <Text className="submit__label">作业内容</Text>
        <Textarea
          className="submit__textarea"
          placeholder="请输入作业内容…"
          value={content}
          onInput={(e) => setContent(e.detail.value)}
          maxlength={-1}
          autoHeight
        />
      </View>

      <View className="submit__section">
        <Text className="submit__label">图片附件</Text>
        <View className="submit__images">
          {images.map((img, idx) => (
            <View key={img} className="submit__image-wrap">
              <Image className="submit__image" src={img} mode="aspectFill" />
              <View className="submit__image-remove" onClick={() => removeImage(idx)}>
                <Text>×</Text>
              </View>
            </View>
          ))}
          {images.length < 9 ? (
            <View className="submit__image-add" onClick={chooseImage}>
              <Text className="submit__image-add-icon">+</Text>
              <Text className="submit__image-add-text">添加图片</Text>
            </View>
          ) : null}
        </View>
      </View>

      <View className="submit__footer">
        <Button
          className="submit__btn"
          loading={submitMutation.isPending}
          disabled={submitMutation.isPending}
          onClick={handleSubmit}
        >
          {submitMutation.isPending ? '提交中…' : '提交作业'}
        </Button>
      </View>
    </View>
  );
}

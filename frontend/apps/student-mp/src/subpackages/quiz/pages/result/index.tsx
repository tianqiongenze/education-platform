import { View, Text, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import './index.scss';

export default function QuizResult() {
  const router = useRouter();
  const score = Number(router.params.score ?? '0');
  const total = Number(router.params.total ?? '0');
  const correct = Number(router.params.correct ?? '0');
  const passed = score >= 60;

  const goHome = () => {
    Taro.switchTab({ url: '/pages/index/index' });
  };

  return (
    <View className="quiz-result ep-page">
      <View className={`quiz-result__hero ${passed ? 'quiz-result__hero--pass' : 'quiz-result__hero--fail'}`}>
        <Text className="quiz-result__score">{score}</Text>
        <Text className="quiz-result__label">{passed ? '恭喜通过' : '未通过'}</Text>
      </View>

      <View className="quiz-result__summary">
        <View className="quiz-result__summary-item">
          <Text className="quiz-result__summary-num">{correct}</Text>
          <Text className="quiz-result__summary-text">答对题数</Text>
        </View>
        <View className="quiz-result__summary-item">
          <Text className="quiz-result__summary-num">{total - correct}</Text>
          <Text className="quiz-result__summary-text">答错题数</Text>
        </View>
        <View className="quiz-result__summary-item">
          <Text className="quiz-result__summary-num">{total}</Text>
          <Text className="quiz-result__summary-text">总题数</Text>
        </View>
      </View>

      <View className="quiz-result__review">
        <Text className="quiz-result__review-title">答题回顾</Text>
        {Array.from({ length: total }).map((_, i) => (
          <View key={i} className="quiz-result__review-item">
            <Text className={`quiz-result__review-mark ${i < correct ? 'quiz-result__review-mark--ok' : 'quiz-result__review-mark--fail'}`}>
              {i + 1}
            </Text>
            <Text className="quiz-result__review-status">
              {i < correct ? '正确' : '错误'}
            </Text>
          </View>
        ))}
      </View>

      <View className="quiz-result__footer">
        <Button onClick={goHome}>返回首页</Button>
      </View>
    </View>
  );
}

import { useEffect, useState, useRef } from 'react';
import { View, Text, Checkbox, CheckboxGroup, Radio, RadioGroup } from '@tarojs/components';
import Taro from '@tarojs/taro';
import './index.scss';

interface Question {
  id: string;
  type: 'single' | 'multiple';
  title: string;
  options: { label: string; text: string }[];
}

const QUESTIONS: Question[] = [
  {
    id: '1',
    type: 'single',
    title: '以下哪门语言是静态类型语言？',
    options: [
      { label: 'A', text: 'JavaScript' },
      { label: 'B', text: 'Python' },
      { label: 'C', text: 'Java' },
      { label: 'D', text: 'Ruby' },
    ],
  },
  {
    id: '2',
    type: 'multiple',
    title: '以下哪些是前端框架？',
    options: [
      { label: 'A', text: 'React' },
      { label: 'B', text: 'Spring' },
      { label: 'C', text: 'Vue' },
      { label: 'D', text: 'Django' },
    ],
  },
];

const DURATION_SECONDS = 30 * 60;

export default function Exam() {
  const [current, setCurrent] = useState(0);
  const [answers, setAnswers] = useState<Record<string, string[]>>({});
  const [remaining, setRemaining] = useState(DURATION_SECONDS);
  const submittedRef = useRef(false);

  useEffect(() => {
    const timer = setInterval(() => {
      setRemaining((r) => {
        if (r <= 1) {
          clearInterval(timer);
          if (!submittedRef.current) {
            submittedRef.current = true;
            handleSubmit();
          }
          return 0;
        }
        return r - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const question = QUESTIONS[current];
  if (!question) return null;
  const isLast = current === QUESTIONS.length - 1;

  const setAnswer = (val: string[]) => {
    setAnswers((prev) => ({ ...prev, [question.id]: val }));
  };

  const goNext = () => {
    if (current < QUESTIONS.length - 1) {
      setCurrent((c) => c + 1);
    }
  };

  const goPrev = () => {
    if (current > 0) {
      setCurrent((c) => c - 1);
    }
  };

  const handleSubmit = () => {
    const correctCount = Object.keys(answers).length;
    const total = QUESTIONS.length;
    const score = Math.round((correctCount / total) * 100);
    Taro.redirectTo({ url: `/subpackages/quiz/pages/result/index?score=${score}&total=${total}&correct=${correctCount}` });
  };

  const mm = String(Math.floor(remaining / 60)).padStart(2, '0');
  const ss = String(remaining % 60).padStart(2, '0');

  return (
    <View className="exam ep-page">
      <View className="exam__timer">
        <Text>剩余时间 {mm}:{ss}</Text>
      </View>

      <View className="exam__counter">
        <Text>第 {current + 1} / {QUESTIONS.length} 题</Text>
      </View>

      <View className="exam__question">
        <Text className="exam__question-title">{question.title}</Text>
        {question.type === 'single' ? (
          <RadioGroup onChange={(e) => setAnswer([e.detail.value])}>
            {question.options.map((o) => (
              <View key={o.label} className="exam__option">
                <Radio value={o.label} checked={answers[question.id]?.[0] === o.label}>
                  <Text className="exam__option-text">{o.label}. {o.text}</Text>
                </Radio>
              </View>
            ))}
          </RadioGroup>
        ) : (
          <CheckboxGroup onChange={(e) => setAnswer(e.detail.value as string[])}>
            {question.options.map((o) => (
              <View key={o.label} className="exam__option">
                <Checkbox value={o.label} checked={answers[question.id]?.includes(o.label) ?? false}>
                  <Text className="exam__option-text">{o.label}. {o.text}</Text>
                </Checkbox>
              </View>
            ))}
          </CheckboxGroup>
        )}
      </View>

      <View className="exam__nav">
        <View className={`exam__btn ${current === 0 ? 'exam__btn--disabled' : ''}`} onClick={goPrev}>
          <Text>上一题</Text>
        </View>
        {isLast ? (
          <View className="exam__btn exam__btn--primary" onClick={handleSubmit}>
            <Text>提交</Text>
          </View>
        ) : (
          <View className="exam__btn exam__btn--primary" onClick={goNext}>
            <Text>下一题</Text>
          </View>
        )}
      </View>
    </View>
  );
}

import { useState } from 'react';
import { View, Text, Textarea, ScrollView } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import type { Language } from '@ep/shared';
import { apiClient } from '../../../../api/client';
import './index.scss';

const LANGUAGES: Language[] = ['python', 'java', 'javascript', 'cpp', 'go', 'rust'];

const DEFAULT_CODE: Record<Language, string> = {
  python: '# 请在此处编写代码\n',
  java: '// 请在此处编写代码\n',
  javascript: '// 请在此处编写代码\n',
  cpp: '// 请在此处编写代码\n',
  go: '// 请在此处编写代码\n',
  rust: '// 请在此处编写代码\n',
};

interface Problem {
  id: string;
  title: string;
  description: string;
}

export default function CodeEditor() {
  const router = useRouter();
  const problemId = (router.params.problemId as string | undefined) ?? 'demo';
  const courseId = (router.params.courseId as string | undefined) ?? '';

  const [language, setLanguage] = useState<Language>('python');
  const [code, setCode] = useState<string>(DEFAULT_CODE.python);
  const [submitting, setSubmitting] = useState(false);

  const problem: Problem = {
    id: problemId,
    title: `编程题 #${problemId}`,
    description: '请阅读题目要求，在下方编辑器中编写代码并提交。题目描述示例：实现一个函数，返回两个整数之和。',
  };

  const handleLanguageChange = (lang: Language) => {
    setLanguage(lang);
    setCode(DEFAULT_CODE[lang]);
  };

  const handleSubmit = async () => {
    if (submitting) return;
    if (!code.trim()) {
      Taro.showToast({ title: '请编写代码', icon: 'none' });
      return;
    }
    setSubmitting(true);
    try {
      const res = (await apiClient.post(`/api/course/${courseId}/code/submit`, {
        problemId,
        language,
        code,
      })) as { submissionId?: string };
      const submissionId = res?.submissionId ?? '';
      Taro.redirectTo({ url: `/subpackages/coding/pages/result/index?id=${submissionId}` });
    } catch (err) {
      Taro.showToast({ title: (err as Error).message, icon: 'none' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <View className="editor ep-page">
      <ScrollView scrollY className="editor__desc">
        <View className="editor__desc-head">
          <Text className="editor__desc-title">{problem.title}</Text>
        </View>
        <Text className="editor__desc-body">{problem.description}</Text>
      </ScrollView>

      <View className="editor__langs">
        <ScrollView scrollX>
          {LANGUAGES.map((l) => (
            <View
              key={l}
              className={`editor__lang ${language === l ? 'editor__lang--active' : ''}`}
              onClick={() => handleLanguageChange(l)}
            >
              <Text>{l}</Text>
            </View>
          ))}
        </ScrollView>
      </View>

      <View className="editor__code">
        <Textarea
          className="editor__code-input"
          value={code}
          onInput={(e) => setCode(e.detail.value)}
          placeholder="请在此处编写代码"
          autoHeight
          maxlength={-1}
        />
      </View>

      <View className="editor__footer">
        <View
          className={`editor__submit ${submitting ? 'editor__submit--disabled' : ''}`}
          onClick={handleSubmit}
        >
          <Text>{submitting ? '提交中…' : '提交评测'}</Text>
        </View>
      </View>
    </View>
  );
}

import { View, Text, Input, ScrollView } from '@tarojs/components';
import { useState, useRef } from 'react';
import { ragChat } from '../../api';
import type { RagMessage } from '../../types';
import './index.scss';

export default function ChatPage() {
  const [messages, setMessages] = useState<RagMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const sessionIdRef = useRef<string | undefined>(undefined);

  const handleSend = async () => {
    const text = input.trim();
    if (!text || loading) return;
    const userMsg: RagMessage = { role: 'user', content: text, citations: [] };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setLoading(true);
    try {
      const res = await ragChat(text, sessionIdRef.current);
      sessionIdRef.current = res.sessionId;
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: res.answer, citations: res.citations },
      ]);
    } catch {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '抱歉，问答失败，请重试。', citations: [] },
      ]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <View className="chat-page">
      <ScrollView scrollY className="message-list">
        {messages.length === 0 && (
          <Text className="empty-text">向 AI 提问，开始知识问答</Text>
        )}
        {messages.map((m, i) => (
          <View
            key={i}
            className={`message ${m.role === 'user' ? 'message-user' : 'message-ai'}`}
          >
            <Text className="message-content">{m.content}</Text>
            {m.citations.length > 0 && (
              <View className="citations">
                {m.citations.map((c) => (
                  <Text key={c.id} className="citation-tag">
                    {c.title}
                  </Text>
                ))}
              </View>
            )}
          </View>
        ))}
        {loading && <Text className="loading-text">AI 思考中...</Text>}
      </ScrollView>
      <View className="input-bar">
        <Input
          className="chat-input"
          placeholder="输入问题..."
          value={input}
          onInput={(e) => setInput(e.detail.value)}
          onConfirm={handleSend}
        />
        <Text className="send-btn" onClick={handleSend}>
          发送
        </Text>
      </View>
    </View>
  );
}

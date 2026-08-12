import { useState, useRef } from 'react';
import { View, Text, Input, ScrollView } from '@tarojs/components';
import { useRouter } from '@tarojs/taro';
import { VideoPlayer } from '@ep/ui-kit';
import './index.scss';

interface ChatMessage {
  id: string;
  user: string;
  content: string;
  isSelf: boolean;
}

export default function LiveRoom() {
  const router = useRouter();
  const roomId = (router.params.id as string | undefined) ?? '';
  const streamUrl = (router.params.stream as string | undefined) ?? '';

  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: '1', user: '老师', content: '欢迎大家来到直播间', isSelf: false },
    { id: '2', user: '同学A', content: '老师好', isSelf: false },
  ]);
  const [input, setInput] = useState('');
  const [participantCount] = useState(128);
  const scrollRef = useRef<string>('');

  const sendMessage = () => {
    if (!input.trim()) return;
    const msg: ChatMessage = {
      id: String(Date.now()),
      user: '我',
      content: input.trim(),
      isSelf: true,
    };
    setMessages((prev) => [...prev, msg]);
    setInput('');
    scrollRef.current = `msg-${msg.id}`;
  };

  return (
    <View className="room ep-page">
      <View className="room__player">
        {streamUrl ? (
          <VideoPlayer src={streamUrl} isLive />
        ) : (
          <View className="room__player-placeholder">
            <Text>直播间 #{roomId}</Text>
            <Text className="room__player-tip">直播流加载中…</Text>
          </View>
        )}
      </View>

      <View className="room__meta">
        <Text className="room__participants">{participantCount} 人在线</Text>
      </View>

      <ScrollView className="room__chat" scrollY scrollIntoView={scrollRef.current}>
        {messages.length === 0 ? (
          <Text className="room__chat-empty">暂无消息</Text>
        ) : (
          messages.map((m) => (
            <View key={m.id} id={`msg-${m.id}`} className={`room__msg ${m.isSelf ? 'room__msg--self' : ''}`}>
              <Text className="room__msg-user">{m.user}</Text>
              <Text className="room__msg-content">{m.content}</Text>
            </View>
          ))
        )}
      </ScrollView>

      <View className="room__input-bar">
        <Input
          className="room__input"
          placeholder="说点什么…"
          value={input}
          onInput={(e) => setInput(e.detail.value)}
          confirmType="send"
          onConfirm={sendMessage}
        />
        <View className={`room__send ${input.trim() ? '' : 'room__send--disabled'}`} onClick={sendMessage}>
          <Text>发送</Text>
        </View>
      </View>
    </View>
  );
}

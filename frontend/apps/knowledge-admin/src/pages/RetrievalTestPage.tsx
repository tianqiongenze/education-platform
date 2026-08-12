import { useState } from 'react';
import { Card, Input, Button, List, Tag, Tabs, Space, Empty, Spin, Typography } from 'antd';
import { SearchOutlined, SendOutlined } from '@ant-design/icons';
import { search, ragChat } from '../api/knowledge';
import type { SearchHit, RagChatMessage } from '../types/knowledge';

const { Text } = Typography;

export default function RetrievalTestPage() {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [total, setTotal] = useState(0);
  const [ragMessages, setRagMessages] = useState<RagChatMessage[]>([]);
  const [ragInput, setRagInput] = useState('');
  const [ragLoading, setRagLoading] = useState(false);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      const res = await search({ query });
      setHits(res.hits);
      setTotal(res.total);
    } catch {
      setHits([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  };

  const handleRagChat = async () => {
    if (!ragInput.trim()) return;
    const userMsg: RagChatMessage = { role: 'user', content: ragInput, citations: [] };
    setRagMessages((prev) => [...prev, userMsg]);
    setRagLoading(true);
    try {
      const res = await ragChat({ query: ragInput });
      setRagMessages((prev) => [
        ...prev,
        { role: 'assistant', content: res.answer, citations: res.citations },
      ]);
    } catch {
      setRagMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '抱歉，问答失败，请重试。', citations: [] },
      ]);
    } finally {
      setRagLoading(false);
      setRagInput('');
    }
  };

  return (
    <Card title="智能检索">
      <Tabs
        items={[
          {
            key: 'search',
            label: '检索测试',
            children: (
              <>
                <Space.Compact style={{ width: '100%', marginBottom: 16 }}>
                  <Input
                    placeholder="输入检索关键词"
                    prefix={<SearchOutlined />}
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onPressEnter={handleSearch}
                  />
                  <Button type="primary" loading={loading} onClick={handleSearch}>
                    检索
                  </Button>
                </Space.Compact>
                <Spin spinning={loading}>
                  {hits.length === 0 && !loading ? (
                    <Empty description="暂无结果" />
                  ) : (
                    <>
                      <Text type="secondary">共 {total} 条结果</Text>
                      <List
                        style={{ marginTop: 12 }}
                        dataSource={hits}
                        renderItem={(hit) => (
                          <List.Item>
                            <List.Item.Meta
                              title={
                                <Space>
                                  <Text strong>{hit.title}</Text>
                                  <Tag color="purple">score: {hit.score.toFixed(2)}</Tag>
                                </Space>
                              }
                              description={hit.snippet}
                            />
                          </List.Item>
                        )}
                      />
                    </>
                  )}
                </Spin>
              </>
            ),
          },
          {
            key: 'rag',
            label: 'RAG 问答测试',
            children: (
              <div style={{ maxWidth: 720 }}>
                <div
                  style={{
                    minHeight: 320,
                    padding: 12,
                    border: '1px solid #f0f0f0',
                    borderRadius: 8,
                    marginBottom: 12,
                  }}
                >
                  {ragMessages.length === 0 && (
                    <Empty description="开始问答" style={{ marginTop: 80 }} />
                  )}
                  {ragMessages.map((m, i) => (
                    <div
                      key={i}
                      style={{
                        textAlign: m.role === 'user' ? 'right' : 'left',
                        margin: '8px 0',
                      }}
                    >
                      <Tag color={m.role === 'user' ? 'blue' : 'green'}>
                        {m.role === 'user' ? '我' : 'AI'}
                      </Tag>
                      <div>{m.content}</div>
                      {m.citations.length > 0 && (
                        <div style={{ marginTop: 4 }}>
                          {m.citations.map((c) => (
                            <Tag key={c.id}>{c.title}</Tag>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                  {ragLoading && <Spin />}
                </div>
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    placeholder="提问..."
                    value={ragInput}
                    onChange={(e) => setRagInput(e.target.value)}
                    onPressEnter={handleRagChat}
                  />
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    loading={ragLoading}
                    onClick={handleRagChat}
                  >
                    发送
                  </Button>
                </Space.Compact>
              </div>
            ),
          },
        ]}
      />
    </Card>
  );
}

/**
 * D-186: IdePanel — container component for the collaborative IDE.
 *
 * Wraps the IdeEditor with a toolbar (session lifecycle controls, language
 * selector, run button) and manages the IDE session via useIdeSession.
 */

import { useCallback, useState } from 'react';
import { Button, Select, Space, Typography, message } from 'antd';
import {
  PlayCircleOutlined,
  PauseCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import IdeEditor from './IdeEditor';
import { useIdeSession } from '../api/useIdeSession';
import { STORAGE_KEYS } from '@ep/shared';

const { Title } = Typography;

export interface IdePanelProps {
  /** Pre-existing session id to load. If omitted, shows a "create session" UI. */
  sessionId?: string;
  /** WebSocket base URL. */
  wsBaseUrl?: string;
  /** Course id (for creating a session in teaching mode). */
  courseId?: number;
  /** Lesson id (for creating a session in teaching mode). */
  lessonId?: number;
  /** Called when code is run. */
  onRun?: (code: string) => void;
}

const LANGUAGE_OPTIONS = [
  { label: 'JavaScript', value: 'javascript' },
  { label: 'Python', value: 'python' },
  { label: 'Java', value: 'java' },
  { label: 'Go', value: 'go' },
  { label: 'C++', value: 'cpp' },
];

/**
 * IdePanel is the top-level IDE container.
 *
 * Lifecycle:
 * 1. If sessionId is provided, loads the session via useIdeSession.
 * 2. If no sessionId, the user can create one (TEACHING mode for teachers).
 * 3. The panel shows session status, connection state, and editor controls.
 * 4. Activate/Suspend/Close buttons drive the session state machine.
 */
export default function IdePanel({
  sessionId: initialSessionId,
  wsBaseUrl = 'ws://localhost:8080',
  courseId,
  lessonId,
  onRun,
}: IdePanelProps) {
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(initialSessionId);
  const [language, setLanguage] = useState('javascript');
  const [code, setCode] = useState('');
  const { session, loading, error, create, activate, suspend, close } =
    useIdeSession(activeSessionId);

  const token = typeof window !== 'undefined'
    ? window.localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN) ?? undefined
    : undefined;

  const handleCreate = useCallback(async () => {
    // In a real app tenantId/userId would come from auth context.
    // For now we use placeholder values that the backend will accept in dev mode.
    const s = await create({
      tenantId: 1,
      userId: 1,
      mode: 'TEACHING',
      courseId,
      lessonId,
      idleTimeoutMin: 30,
    });
    if (s) {
      setActiveSessionId(s.sessionId);
      void message.success('IDE 会话已创建');
    }
  }, [create, courseId, lessonId]);

  const handleActivate = useCallback(async () => {
    const s = await activate();
    if (s) void message.success('会话已激活');
  }, [activate]);

  const handleSuspend = useCallback(async () => {
    const s = await suspend();
    if (s) void message.info('会话已暂停');
  }, [suspend]);

  const handleClose = useCallback(async () => {
    const s = await close();
    if (s) {
      void message.info('会话已关闭');
      setActiveSessionId(undefined);
    }
  }, [close]);

  const handleRun = useCallback(() => {
    onRun?.(code);
  }, [onRun, code]);

  const sessionStatus = session?.status ?? '无会话';

  return (
    <div data-testid="ide-panel" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Toolbar */}
      <div
        data-testid="ide-panel-toolbar"
        style={{
          padding: '8px 16px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Space size="middle" wrap>
          <Title level={5} style={{ margin: 0 }}>
            协作 IDE
          </Title>
          <span data-testid="ide-session-status">
            状态：<strong>{sessionStatus}</strong>
          </span>
          {session && (
            <span data-testid="ide-session-id" style={{ fontSize: 12, color: '#999' }}>
              {session.sessionId.slice(0, 12)}…
            </span>
          )}
        </Space>

        <Space size="small" wrap>
          <Select
            data-testid="ide-language-select"
            value={language}
            onChange={setLanguage}
            options={LANGUAGE_OPTIONS}
            style={{ width: 120 }}
            size="small"
          />
          {!activeSessionId && (
            <Button
              type="primary"
              size="small"
              onClick={handleCreate}
              loading={loading}
              data-testid="ide-create-btn"
            >
              创建会话
            </Button>
          )}
          {activeSessionId && session?.status !== 'ACTIVE' && (
            <Button
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={handleActivate}
              loading={loading}
              data-testid="ide-activate-btn"
            >
              激活
            </Button>
          )}
          {session?.status === 'ACTIVE' && (
            <Button
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={handleSuspend}
              loading={loading}
              data-testid="ide-suspend-btn"
            >
              暂停
            </Button>
          )}
          {activeSessionId && session?.status !== 'CLOSED' && (
            <Button
              size="small"
              danger
              icon={<CloseCircleOutlined />}
              onClick={handleClose}
              loading={loading}
              data-testid="ide-close-btn"
            >
              关闭
            </Button>
          )}
          <Button
            size="small"
            icon={<PlayCircleOutlined />}
            onClick={handleRun}
            disabled={!activeSessionId}
            data-testid="ide-run-btn"
          >
            运行
          </Button>
        </Space>
      </div>

      {/* Error banner */}
      {error && (
        <div
          data-testid="ide-error-banner"
          style={{ padding: '4px 16px', background: '#fff2f0', color: '#cf1322', fontSize: 13 }}
        >
          {error}
        </div>
      )}

      {/* Editor */}
      <div style={{ flex: 1, minHeight: 0 }}>
        {activeSessionId ? (
          <IdeEditor
            sessionId={activeSessionId}
            token={token}
            wsBaseUrl={wsBaseUrl}
            language={language}
            onChange={setCode}
          />
        ) : (
          <div
            data-testid="ide-placeholder"
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              height: '100%',
              color: '#999',
              fontSize: 16,
            }}
          >
            点击"创建会话"开始协作编程
          </div>
        )}
      </div>
    </div>
  );
}

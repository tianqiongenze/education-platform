/**
 * D-186: React hook for managing an IDE session lifecycle.
 *
 * Wraps the REST API calls to /api/v1/ide/sessions and exposes a clean
 * state machine for the UI layer.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { apiClient } from '../api/client';

export type IdeSessionMode = 'TEACHING' | 'JUDGE';
export type IdeSessionStatus =
  | 'CREATING'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'IDLE'
  | 'CLOSED';

export interface IdeSession {
  sessionId: string;
  tenantId: number;
  userId: number;
  mode: IdeSessionMode;
  yjsDocId: string;
  status: IdeSessionStatus;
  courseId?: number | null;
  lessonId?: number | null;
  idleTimeoutMin: number;
  createdAt: string;
  updatedAt: string;
  lastActiveAt: string;
  closedAt?: string | null;
}

export interface CreateIdeSessionParams {
  tenantId: number;
  userId: number;
  mode: IdeSessionMode;
  courseId?: number;
  lessonId?: number;
  idleTimeoutMin?: number;
}

export interface UseIdeSessionResult {
  session: IdeSession | null;
  loading: boolean;
  error: string | null;
  create: (params: CreateIdeSessionParams) => Promise<IdeSession | null>;
  activate: () => Promise<IdeSession | null>;
  suspend: () => Promise<IdeSession | null>;
  close: () => Promise<IdeSession | null>;
  touch: () => Promise<void>;
  refresh: () => Promise<void>;
}

/**
 * useIdeSession manages a single IDE session's lifecycle via the grading-service
 * REST API. Pass a sessionId to load an existing session, or leave it undefined
 * and call create().
 */
export function useIdeSession(sessionId?: string): UseIdeSessionResult {
  const [session, setSession] = useState<IdeSession | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const safeSet = useCallback(<T,>(fn: (v: T) => void, value: T) => {
    if (mountedRef.current) fn(value);
  }, []);

  const refresh = useCallback(async () => {
    if (!sessionId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiClient.get<IdeSession>(`/api/v1/ide/sessions/${sessionId}`);
      safeSet(setSession, res);
    } catch (e) {
      safeSet(setError, e instanceof Error ? e.message : 'Failed to load session');
    } finally {
      safeSet(setLoading, false);
    }
  }, [sessionId, safeSet]);

  useEffect(() => {
    if (sessionId) refresh();
  }, [sessionId, refresh]);

  const create = useCallback(
    async (params: CreateIdeSessionParams): Promise<IdeSession | null> => {
      setLoading(true);
      setError(null);
      try {
        const res = await apiClient.post<IdeSession>('/api/v1/ide/sessions', params);
        safeSet(setSession, res);
        return res;
      } catch (e) {
        safeSet(setError, e instanceof Error ? e.message : 'Failed to create session');
        return null;
      } finally {
        safeSet(setLoading, false);
      }
    },
    [safeSet],
  );

  const transition = useCallback(
    async (action: string): Promise<IdeSession | null> => {
      if (!session?.sessionId) return null;
      setLoading(true);
      setError(null);
      try {
        const res = await apiClient.post<IdeSession>(
          `/api/v1/ide/sessions/${session.sessionId}/${action}`,
          {},
        );
        safeSet(setSession, res);
        return res;
      } catch (e) {
        safeSet(setError, e instanceof Error ? e.message : `Failed to ${action} session`);
        return null;
      } finally {
        safeSet(setLoading, false);
      }
    },
    [session, safeSet],
  );

  const activate = useCallback(() => transition('activate'), [transition]);
  const suspend = useCallback(() => transition('suspend'), [transition]);
  const close = useCallback(() => transition('close'), [transition]);

  const touch = useCallback(async () => {
    if (!session?.sessionId) return;
    try {
      await apiClient.post(`/api/v1/ide/sessions/${session.sessionId}/touch`, {});
    } catch {
      // best-effort; touch failures are non-fatal
    }
  }, [session]);

  return {
    session,
    loading,
    error,
    create,
    activate,
    suspend,
    close,
    touch,
    refresh,
  };
}

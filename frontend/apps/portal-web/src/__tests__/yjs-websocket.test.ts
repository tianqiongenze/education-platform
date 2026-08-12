/**
 * D-186: Tests for YjsWebSocket client and buildYjsWsUrl helper.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { YjsWebSocket, YJS_MSG, buildYjsWsUrl } from '../api/yjs-websocket';

describe('buildYjsWsUrl', () => {
  it('builds a URL with session id and token', () => {
    const url = buildYjsWsUrl('ws://localhost:8080', 'session-123', 'jwt-token');
    expect(url).toBe('ws://localhost:8080/ws/ide/session-123?token=jwt-token');
  });

  it('builds a URL without token when not provided', () => {
    const url = buildYjsWsUrl('ws://localhost:8080', 'session-123');
    expect(url).toBe('ws://localhost:8080/ws/ide/session-123');
  });

  it('strips trailing slash from base URL', () => {
    const url = buildYjsWsUrl('ws://localhost:8080/', 'session-123');
    expect(url).toBe('ws://localhost:8080/ws/ide/session-123');
  });

  it('encodes session id and token', () => {
    const url = buildYjsWsUrl('ws://host', 'session with spaces', 'tok/en');
    expect(url).toBe('ws://host/ws/ide/session%20with%20spaces?token=tok%2Fen');
  });
});

describe('YjsWebSocket', () => {
  let mockWs: any;
  let wsInstances: any[];

  beforeEach(() => {
    wsInstances = [];
    mockWs = {
      readyState: 0,
      binaryType: 'blob',
      onopen: null,
      onclose: null,
      onerror: null,
      onmessage: null,
      send: vi.fn(),
      close: vi.fn(),
    };
    vi.stubGlobal('WebSocket', vi.fn((url: string) => {
      mockWs.url = url;
      wsInstances.push(mockWs);
      return mockWs;
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('connects and fires onOpen', () => {
    const onOpen = vi.fn();
    const ws = new YjsWebSocket({ url: 'ws://test', onOpen });
    ws.connect();
    expect(mockWs.binaryType).toBe('arraybuffer');
    mockWs.readyState = 1;
    mockWs.onopen?.();
    expect(onOpen).toHaveBeenCalled();
    expect(ws.isOpen).toBe(true);
  });

  it('fires onClose and onError', () => {
    const onClose = vi.fn();
    const onError = vi.fn();
    const ws = new YjsWebSocket({ url: 'ws://test', onClose, onError });
    ws.connect();
    mockWs.onerror?.(new Event('error'));
    expect(onError).toHaveBeenCalled();
    mockWs.onclose?.(new CloseEvent('close'));
    expect(onClose).toHaveBeenCalled();
  });

  it('calls onMessage for ArrayBuffer data', () => {
    const onMessage = vi.fn();
    const ws = new YjsWebSocket({ url: 'ws://test', onMessage });
    ws.connect();
    const buf = new ArrayBuffer(2);
    mockWs.onmessage?.({ data: buf });
    expect(onMessage).toHaveBeenCalledWith(buf);
  });

  it('ignores non-ArrayBuffer messages', () => {
    const onMessage = vi.fn();
    const ws = new YjsWebSocket({ url: 'ws://test', onMessage });
    ws.connect();
    mockWs.onmessage?.({ data: 'text' });
    expect(onMessage).not.toHaveBeenCalled();
  });

  it('send returns false when not open', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    mockWs.readyState = 0; // CONNECTING
    expect(ws.send(new ArrayBuffer(1))).toBe(false);
    expect(mockWs.send).not.toHaveBeenCalled();
  });

  it('send returns true and sends when open', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    mockWs.readyState = 1; // OPEN
    expect(ws.send(new ArrayBuffer(4))).toBe(true);
    expect(mockWs.send).toHaveBeenCalledWith(expect.any(ArrayBuffer));
  });

  it('requestSnapshot sends SYNC_STEP1', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    mockWs.readyState = 1;
    ws.requestSnapshot();
    expect(mockWs.send).toHaveBeenCalledTimes(1);
    const sent = mockWs.send.mock.calls[0][0];
    const view = new Uint8Array(sent);
    expect(view[0]).toBe(YJS_MSG.SYNC_STEP1);
  });

  it('sendUpdate prepends UPDATE type', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    mockWs.readyState = 1;
    const payload = new Uint8Array([0x01, 0x02]);
    ws.sendUpdate(payload);
    expect(mockWs.send).toHaveBeenCalledTimes(1);
    const sent = mockWs.send.mock.calls[0][0];
    const view = new Uint8Array(sent);
    expect(view[0]).toBe(YJS_MSG.UPDATE);
    expect(view[1]).toBe(0x01);
    expect(view[2]).toBe(0x02);
  });

  it('sendAwarenessUpdate prepends AWARENESS_UPDATE type', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    mockWs.readyState = 1;
    ws.sendAwarenessUpdate(new Uint8Array([0x09]));
    const sent = mockWs.send.mock.calls[0][0];
    const view = new Uint8Array(sent);
    expect(view[0]).toBe(YJS_MSG.AWARENESS_UPDATE);
    expect(view[1]).toBe(0x09);
  });

  it('close calls WebSocket.close and clears ref', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    ws.close();
    expect(mockWs.close).toHaveBeenCalled();
    // readyState returns WS_CLOSED (3) when ws is null
    expect(ws.readyState).toBe(3);
  });

  it('connect is idempotent (does not create second socket)', () => {
    const ws = new YjsWebSocket({ url: 'ws://test' });
    ws.connect();
    ws.connect();
    expect(wsInstances).toHaveLength(1);
  });
});

describe('YJS_MSG constants', () => {
  it('matches y-websocket protocol values', () => {
    expect(YJS_MSG.SYNC_STEP1).toBe(0);
    expect(YJS_MSG.SYNC_STEP2).toBe(1);
    expect(YJS_MSG.AWARENESS_UPDATE).toBe(2);
    expect(YJS_MSG.AWARENESS_QUERY).toBe(3);
    expect(YJS_MSG.UPDATE).toBe(4);
  });
});

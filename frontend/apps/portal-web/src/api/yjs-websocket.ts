/**
 * D-186: Yjs WebSocket client for the collaborative IDE.
 *
 * Connects to the ws-gateway /ws/ide/{sessionId} endpoint and exchanges
 * binary y-websocket sync frames. This module is deliberately framework-
 * agnostic (no React dependency) so it can be reused outside hooks.
 *
 * Protocol (mirrors the Go yjs.Handler and Java YjsSyncHandler):
 *   0 = SYNC_STEP1   — request snapshot
 *   1 = SYNC_STEP2   — snapshot reply / remote update
 *   2 = AWARENESS_UPDATE
 *   3 = AWARENESS_QUERY
 *   4 = UPDATE
 */

export const YJS_MSG = {
  SYNC_STEP1: 0,
  SYNC_STEP2: 1,
  AWARENESS_UPDATE: 2,
  AWARENESS_QUERY: 3,
  UPDATE: 4,
} as const;

export type YjsMessageType = (typeof YJS_MSG)[keyof typeof YJS_MSG];

/** WebSocket readyState constants (mirrors the W3C spec). */
const WS_OPEN = 1;
const WS_CLOSED = 3;

export interface YjsWebSocketOptions {
  /** WebSocket URL, e.g. ws://localhost:8080/ws/ide/session-123?token=... */
  url: string;
  /** Called when the socket opens. */
  onOpen?: () => void;
  /** Called when the socket closes. */
  onClose?: (event: CloseEvent) => void;
  /** Called on socket error. */
  onError?: (event: Event) => void;
  /** Called for every binary message received. */
  onMessage?: (data: ArrayBuffer) => void;
}

/**
 * YjsWebSocket wraps a native WebSocket for binary y-websocket sync.
 *
 * D-186: We use the browser's built-in WebSocket — no third-party library
 * (Iron Law 8: zero external deps, offline-compatible). The binary protocol
 * is handled by the caller (typically a Monaco + Yjs binding layer).
 */
export class YjsWebSocket {
  private ws: WebSocket | null = null;
  private readonly opts: YjsWebSocketOptions;

  constructor(opts: YjsWebSocketOptions) {
    this.opts = opts;
  }

  /** Opens the WebSocket connection. Safe to call once. */
  connect(): void {
    if (this.ws) return;
    this.ws = new WebSocket(this.opts.url);
    this.ws.binaryType = 'arraybuffer';

    this.ws.onopen = () => this.opts.onOpen?.();
    this.ws.onclose = (e) => this.opts.onClose?.(e);
    this.ws.onerror = (e) => this.opts.onError?.(e);
    this.ws.onmessage = (e) => {
      if (e.data instanceof ArrayBuffer) {
        this.opts.onMessage?.(e.data);
      }
    };
  }

  /** Sends raw binary data. Returns false if the socket is not open. */
  send(data: ArrayBuffer | Uint8Array): boolean {
    if (!this.ws || this.ws.readyState !== WS_OPEN) return false;
    const buf = data instanceof ArrayBuffer ? data : data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
    this.ws.send(buf);
    return true;
  }

  /** Sends a SYNC_STEP1 request to obtain the current document snapshot. */
  requestSnapshot(): boolean {
    return this.send(new Uint8Array([YJS_MSG.SYNC_STEP1]));
  }

  /** Sends an UPDATE message (document update payload). */
  sendUpdate(payload: Uint8Array): boolean {
    const msg = new Uint8Array(1 + payload.length);
    msg[0] = YJS_MSG.UPDATE;
    msg.set(payload, 1);
    return this.send(msg);
  }

  /** Sends an AWARENESS_UPDATE message. */
  sendAwarenessUpdate(payload: Uint8Array): boolean {
    const msg = new Uint8Array(1 + payload.length);
    msg[0] = YJS_MSG.AWARENESS_UPDATE;
    msg.set(payload, 1);
    return this.send(msg);
  }

  /** Closes the WebSocket. */
  close(): void {
    this.ws?.close();
    this.ws = null;
  }

  /** Returns the current ready state. */
  get readyState(): number {
    return this.ws?.readyState ?? WS_CLOSED;
  }

  /** Returns true when the socket is open and ready to send. */
  get isOpen(): boolean {
    return this.ws?.readyState === WS_OPEN;
  }
}

/**
 * Builds the WebSocket URL for a given session id and auth token.
 *
 * @param wsBaseUrl  e.g. ws://localhost:8080
 * @param sessionId  the IDE session id
 * @param token      JWT access token (optional if anonymous mode)
 */
export function buildYjsWsUrl(
  wsBaseUrl: string,
  sessionId: string,
  token?: string,
): string {
  const base = wsBaseUrl.replace(/\/$/, '');
  const url = `${base}/ws/ide/${encodeURIComponent(sessionId)}`;
  return token ? `${url}?token=${encodeURIComponent(token)}` : url;
}

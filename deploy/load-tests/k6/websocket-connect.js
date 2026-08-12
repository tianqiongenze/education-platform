/**
 * websocket-connect.js — WebSocket connection stability test for gateway-go.
 *
 * 5000 concurrent WS connections, each sending a ping every 5 seconds,
 * held for 5 minutes. Verifies connections stay alive.
 *
 * Run: k6 run websocket-connect.js
 *
 * Requires k6 ws module (built-in).
 */
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { WS_URL, recordCheck } from './config/common.js';

const ws_connect_failures = new Counter('ws_connect_failures');
const ws_msg_per_sec = new Rate('ws_msg_per_sec');
const ws_connect_latency = new Trend('ws_connect_latency', true);
const ws_ping_rtt = new Trend('ws_ping_rtt', true);

export const options = {
  vus: 5000,
  duration: '5m',
  thresholds: {
    'ws_connect_failures': ['count<100'], // < 2% of 5000
    'ws_msg_per_sec': ['rate>0.1'],
    'ws_ping_rtt': ['p(95)<500'],
    'ws_sessions': ['rate<0.02'],
  },
  tags: {
    scenario: 'websocket-connect',
    target: '5k-ws',
  },
};

export default function () {
  const url = `${WS_URL}/ws?userId=ws_${__VU}_${Date.now()}`;

  const connectStart = Date.now();
  const res = ws.connect(url, {}, (socket) => {
    socket.on('open', () => {
      ws_connect_latency.add(Date.now() - connectStart);

      // Send initial hello
      socket.send(JSON.stringify({ type: 'hello', userId: __VU }));

      // Send ping every 5 seconds
      socket.setInterval(() => {
        const pingStart = Date.now();
        socket.send(JSON.stringify({ type: 'ping', ts: pingStart }));

        socket.on('message', (data) => {
          try {
            const msg = JSON.parse(data);
            if (msg.type === 'pong') {
              ws_ping_rtt.add(Date.now() - pingStart);
              ws_msg_per_sec.add(1);
            }
          } catch (e) {
            // ignore non-JSON
          }
        });
      }, 5000);

      socket.on('message', (data) => {
        ws_msg_per_sec.add(1);
      });

      socket.on('error', (e) => {
        ws_connect_failures.add(1);
      });

      socket.on('close', () => {
        // Connection closed
      });
    });

    socket.on('error', (e) => {
      ws_connect_failures.add(1);
    });
  });

  const connected = res && res.status === 101;
  check(res, {
    'ws connected (101)': (r) => r && r.status === 101,
  });
  recordCheck(connected);

  if (!connected) {
    ws_connect_failures.add(1);
  }

  // Hold connection for the iteration duration.
  // k6 will close the socket at end of iteration (VU lifecycle).
  sleep(280); // slightly less than duration per VU to allow clean close
}

/**
 * ai-gateway-load.js — Load test for the AI Gateway service.
 *
 * 500 VUs for 5 minutes.
 * Mix: 70% chat completions, 20% embeddings, 10% health check.
 *
 * Run: k6 run ai-gateway-load.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { AIGW_URL, commonHeaders, thinkTime, metrics, recordCheck } from './config/common.js';

const chat_latency = new Trend('ai_chat_latency', true);
const embeddings_latency = new Trend('ai_embeddings_latency', true);
const health_latency = new Trend('ai_health_latency', true);
const chat_total = new Counter('ai_chat_total');
const embeddings_total = new Counter('ai_embeddings_total');

export const options = {
  vus: 500,
  duration: '5m',
  thresholds: {
    'http_req_duration': ['p(95)<2000'],
    'http_req_failed': ['rate<0.01'],
    'ai_chat_latency': ['p(95)<2000'],
    'ai_embeddings_latency': ['p(95)<2000'],
    error_rate: ['rate<0.01'],
  },
  tags: {
    scenario: 'ai-gateway-load',
    target: '500-vu',
  },
};

const sampleMessages = [
  { role: 'system', content: 'You are a helpful tutor.' },
  { role: 'user', content: 'Explain photosynthesis.' },
  { role: 'user', content: 'What is the Pythagorean theorem?' },
  { role: 'user', content: 'Summarize the French Revolution in 3 sentences.' },
  { role: 'user', content: 'Give me 5 practice problems on fractions.' },
];

const sampleTexts = [
  'The mitochondria is the powerhouse of the cell.',
  'Newton\'s first law of motion describes inertia.',
  'The quadratic formula solves ax^2 + bx + c = 0.',
  'Shakespeare wrote Hamlet around 1600.',
  'Photosynthesis converts sunlight into chemical energy.',
];

function pickRandom(arr, n) {
  const out = [];
  for (let i = 0; i < n; i++) {
    out.push(arr[Math.floor(Math.random() * arr.length)]);
  }
  return out;
}

export default function () {
  const roll = Math.random();

  if (roll < 0.7) {
    // 70% chat completions
    const numMsgs = Math.floor(Math.random() * 3) + 1; // 1-3 messages
    const msgs = pickRandom(sampleMessages, numMsgs);
    const payload = {
      model: 'mock-gpt',
      messages: msgs,
      max_tokens: 128,
      temperature: 0.7,
    };
    const start = Date.now();
    const res = http.post(
      `${AIGW_URL}/v1/chat/completions`,
      JSON.stringify(payload),
      { headers: commonHeaders }
    );
    chat_latency.add(Date.now() - start);
    chat_total.add(1);

    const ok = res.status === 200;
    check(res, {
      'chat completions 200': (r) => r.status === 200,
      'chat has choices array': (r) => {
        try {
          const body = r.json();
          return Array.isArray(body.choices) && body.choices.length > 0;
        } catch (e) {
          return false;
        }
      },
    });
    recordCheck(ok);
  } else if (roll < 0.9) {
    // 20% embeddings
    const text = sampleTexts[Math.floor(Math.random() * sampleTexts.length)];
    const payload = {
      model: 'mock-embedding',
      input: text,
    };
    const start = Date.now();
    const res = http.post(
      `${AIGW_URL}/v1/embeddings`,
      JSON.stringify(payload),
      { headers: commonHeaders }
    );
    embeddings_latency.add(Date.now() - start);
    embeddings_total.add(1);

    const ok = res.status === 200;
    check(res, {
      'embeddings 200': (r) => r.status === 200,
      'embeddings has 1536-dim vector': (r) => {
        try {
          const body = r.json();
          const vec =
            body.data && body.data[0] && body.data[0].embedding;
          return Array.isArray(vec) && vec.length === 1536;
        } catch (e) {
          return false;
        }
      },
    });
    recordCheck(ok);
  } else {
    // 10% health check
    const start = Date.now();
    const res = http.get(`${AIGW_URL}/health`, { headers: commonHeaders });
    health_latency.add(Date.now() - start);
    const ok = res.status === 200;
    check(res, {
      'health 200': (r) => r.status === 200,
    });
    recordCheck(ok);
  }

  thinkTime(0.5, 1.5);
}

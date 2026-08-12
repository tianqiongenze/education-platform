/**
 * itbackend-flow.js — IT Backend homework flow load test.
 *
 * 2000 VUs for 10 minutes.
 * Flow per VU iteration:
 *   teacher creates assignment -> publish -> student submits homework ->
 *   start grading -> check grading status
 *
 * Run: k6 run itbackend-flow.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import {
  ITBACKEND_URL,
  commonHeaders,
  login,
  thinkTime,
  generateIdempotencyKey,
  metrics,
  recordCheck,
} from './config/common.js';

const homework_submitted_per_sec = new Rate('homework_submitted_per_sec');
const grading_pipeline_latency = new Trend('grading_pipeline_latency', true);
const assignment_create_latency = new Trend('assignment_create_latency', true);
const grading_started_total = new Counter('grading_started_total');

export const options = {
  vus: 2000,
  duration: '10m',
  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<3000'],
    'http_req_failed': ['rate<0.02'],
    'grading_pipeline_latency': ['p(95)<3000'],
    error_rate: ['rate<0.02'],
  },
  tags: {
    scenario: 'itbackend-flow',
    target: '2k-vu',
  },
};

function genAssignmentTitle() {
  const subjects = ['Math', 'Physics', 'Chemistry', 'Biology', 'History', 'English'];
  const s = subjects[Math.floor(Math.random() * subjects.length)];
  return `${s} Homework VU${__VU}-ITER${__ITER}`;
}

export default function () {
  const teacherToken = login();
  const studentToken = login(); // reuse same test account in mock mode

  let assignmentId = null;

  // ---- Teacher: create assignment ----
  group('create assignment', () => {
    const idemKey = generateIdempotencyKey();
    const payload = {
      title: genAssignmentTitle(),
      description: 'Auto-generated load test assignment.',
      courseId: Math.floor(Math.random() * 1000) + 1,
      teacherId: 1000 + __VU,
      dueDate: '2026-12-31T23:59:59Z',
      idempotencyKey: idemKey,
    };
    const start = Date.now();
    const res = http.post(
      `${ITBACKEND_URL}/api/v1/assignments`,
      JSON.stringify(payload),
      {
        headers: Object.assign({}, commonHeaders, {
          'X-Idempotency-Key': idemKey,
          Authorization: teacherToken ? `Bearer ${teacherToken}` : '',
        }),
      }
    );
    assignment_create_latency.add(Date.now() - start);
    const ok = res.status === 200 || res.status === 201;
    check(res, {
      'create assignment 200|201': (r) => r.status === 200 || r.status === 201,
    });
    recordCheck(ok);

    try {
      const body = res.json();
      assignmentId = (body && (body.id || (body.data && body.data.id))) || null;
    } catch (e) {
      assignmentId = null;
    }
  });

  thinkTime(0.5, 1.5);

  // ---- Teacher: publish assignment (re-use PUT-style via idem) ----
  if (assignmentId) {
    group('publish assignment', () => {
      const res = http.post(
        `${ITBACKEND_URL}/api/v1/assignments/${assignmentId}/publish`,
        JSON.stringify({ status: 'published' }),
        {
          headers: Object.assign({}, commonHeaders, {
            Authorization: teacherToken ? `Bearer ${teacherToken}` : '',
          }),
        }
      );
      check(res, {
        'publish 200': (r) => r.status === 200,
      });
      recordCheck(res.status === 200);
    });
  }

  thinkTime(1, 3);

  // ---- Student: submit homework ----
  let submissionId = null;
  if (assignmentId) {
    group('submit homework', () => {
      const idemKey = generateIdempotencyKey();
      const payload = {
        assignmentId,
        studentId: 5000 + __VU,
        content: `Homework submission for assignment ${assignmentId} by student ${__VU}.`,
        idempotencyKey: idemKey,
      };
      const res = http.post(
        `${ITBACKEND_URL}/api/v1/submissions`,
        JSON.stringify(payload),
        {
          headers: Object.assign({}, commonHeaders, {
            'X-Idempotency-Key': idemKey,
            Authorization: studentToken ? `Bearer ${studentToken}` : '',
          }),
        }
      );
      const ok = res.status === 200 || res.status === 201;
      check(res, {
        'submit homework 200|201': (r) => r.status === 200 || r.status === 201,
      });
      recordCheck(ok);
      if (ok) {
        homework_submitted_per_sec.add(1);
      } else {
        homework_submitted_per_sec.add(0);
      }
      try {
        const body = res.json();
        submissionId = (body && (body.id || (body.data && body.data.id))) || null;
      } catch (e) {
        submissionId = null;
      }
    });
  }

  thinkTime(1, 2);

  // ---- Start grading ----
  let gradingId = null;
  if (submissionId) {
    group('start grading', () => {
      const start = Date.now();
      const idemKey = generateIdempotencyKey();
      const res = http.post(
        `${ITBACKEND_URL}/api/v1/grading/start`,
        JSON.stringify({
          submissionId,
          assignmentId,
          idempotencyKey: idemKey,
        }),
        {
          headers: Object.assign({}, commonHeaders, {
            'X-Idempotency-Key': idemKey,
            Authorization: teacherToken ? `Bearer ${teacherToken}` : '',
          }),
        }
      );
      grading_pipeline_latency.add(Date.now() - start);
      const ok = res.status === 200 || res.status === 201;
      check(res, {
        'start grading 200|201': (r) => r.status === 200 || r.status === 201,
      });
      recordCheck(ok);
      if (ok) grading_started_total.add(1);
      try {
        const body = res.json();
        gradingId = (body && (body.id || (body.data && body.data.id))) || null;
      } catch (e) {
        gradingId = null;
      }
    });
  }

  thinkTime(1, 2);

  // ---- Check grading status ----
  if (gradingId) {
    group('check grading status', () => {
      const res = http.get(
        `${ITBACKEND_URL}/api/v1/grading/${gradingId}/status`,
        {
          headers: Object.assign({}, commonHeaders, {
            Authorization: teacherToken ? `Bearer ${teacherToken}` : '',
          }),
        }
      );
      check(res, {
        'grading status 200': (r) => r.status === 200,
      });
      recordCheck(res.status === 200);
    });
  }

  thinkTime(1, 2);
}

# D-241: Locust business-flow simulation (first real-cluster Locust run).
# Division of labor (2026 best practice): k6 = protocol-level throughput from
# an in-cluster k8s Job; Locust = stateful user journeys (captcha -> login ->
# guarded reads) through an ssh tunnel from the operator workstation.
#
# Run: ssh -N -L 18083:10.109.25.191:8083 -L 18090:10.97.98.252:80 root@10.167.2.175 &
#      locust -f locustfile.py --headless -u 5 -r 2 -t 1m
# (bases default to the tunnel ports; override with EP_CORE_BASE / EP_BFF_BASE)
#
# Note on auth: the math captcha answer is server-side (Redis) — from the load
# generator's perspective a wrong answer is EXPECTED and returns 400; that is
# the auth guard working, not an error. Guarded reads without a token return
# 401/403 — also expected. Both are marked success() with a guard tag.
import json
import os
import random

from locust import HttpUser, task, between

CORE_BASE = os.environ.get("EP_CORE_BASE", "http://localhost:18083")
BFF_BASE = os.environ.get("EP_BFF_BASE", "http://localhost:18090")


class EduStudentUser(HttpUser):
    """Simulates a student: anonymous reads + a login attempt per session."""

    wait_time = between(0.5, 1.5)
    # locust requires a host attr even when all requests use absolute URLs
    host = "http://localhost:18090"
    
    def on_start(self):
        self.token = None
        self.known_course_id = 1
        self._try_login()

    def _try_login(self):
        with self.client.get(f"{CORE_BASE}/api/auth/captcha", name="/core captcha",
                             catch_response=True) as r:
            if r.status_code != 200:
                r.failure(f"captcha issuance failed: {r.status_code}")
                return
            cap = json.loads(r.text)
        answer = random.randint(0, 20)  # cannot know the server-side answer
        with self.client.post(
            f"{CORE_BASE}/api/auth/login",
            json={"phone": "13800000000", "password": "demo123",
                  "captchaId": cap.get("captchaId", ""), "captchaAnswer": str(answer)},
            name="/core login",
            catch_response=True,
        ) as login:
            if login.status_code == 200:
                body = json.loads(login.text)
                self.token = body.get("token") or body.get("accessToken")
                login.success()
            elif login.status_code in (400, 401, 423):
                login.success()  # guard enforced — expected
            else:
                login.failure(f"login unexpected: {login.status_code}")

    @task(4)
    def read_course(self):
        with self.client.get(
            f"{BFF_BASE}/api/v1/courses/{self.known_course_id}",
            name="/bff course detail",
            headers={"X-Cell-Name": "cell-hz"},
            catch_response=True,
        ) as r:
            if r.status_code == 200:
                r.success()
            elif r.status_code in (401, 403):
                r.success()  # anonymous guard — expected without token
            else:
                r.failure(f"course read: {r.status_code}")

    @task(2)
    def read_captcha(self):
        with self.client.get(f"{CORE_BASE}/api/auth/captcha", name="/core captcha",
                             catch_response=True) as r:
            if r.status_code == 200:
                r.success()
            elif r.status_code == 429:
                r.success()  # rate limiter working (D-155 Resilience4j) — expected under load
            else:
                r.failure(f"captcha: {r.status_code}")

    @task(1)
    def health_walk(self):
        with self.client.get(f"{BFF_BASE}/actuator/health", name="/bff health",
                             catch_response=True) as r:
            if r.status_code == 200:
                r.success()
            else:
                r.failure(f"bff health: {r.status_code}")

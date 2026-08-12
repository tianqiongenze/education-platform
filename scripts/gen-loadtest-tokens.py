#!/usr/bin/env python3
"""Generate load-test JWT tokens signed with RS256 for the education platform BFF.

Produces /tmp/ep-loadtest-tokens.json with 3 groups (students/teachers/admins),
each entry: {"userId": <string>, "token": <jwt>, "role": <role>}.

userId is emitted as a STRING to avoid JS Number precision loss for Java Long
snowflake IDs that may exceed Number.MAX_SAFE_INTEGER.
"""

import json
import os
import time

import jwt

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Default: the committed key in the project root.
# NOTE: the currently running BFF process was started with a DIFFERENT RSA key
# (passed via --jwt.rsa.private-key-pem on the command line). If the BFF is
# running with a key other than jwt-rs256-private.pem, point KEY_PATH at the
# matching private key (e.g. jwt-rs256-private-bff-running.pem) so the issued
# tokens validate against the BFF's public key.
DEFAULT_KEY = os.path.join(BASE_DIR, "jwt-rs256-private.pem")
RUNNING_KEY = os.path.join(BASE_DIR, "jwt-rs256-private-bff-running.pem")
KEY_PATH = RUNNING_KEY if os.path.exists(RUNNING_KEY) else DEFAULT_KEY
OUTPUT_PATH = "/tmp/ep-loadtest-tokens.json"

TOKEN_TTL_SECONDS = 7 * 24 * 3600  # 7 days
TENANT_ID = 1

GROUPS = [
    {"name": "students", "role": "student", "username_prefix": "student",
     "id_start": 9000, "id_end": 9499},   # 500
    {"name": "teachers", "role": "teacher", "username_prefix": "teacher",
     "id_start": 9500, "id_end": 9999},   # 500
    {"name": "admins", "role": "admin", "username_prefix": "admin",
     "id_start": 8000, "id_end": 8249},   # 250
]


def load_private_key(path):
    with open(path, "rb") as f:
        return f.read()


def build_payload(user_id, role, username, now):
    """Match JwtService.issue() claim structure exactly.

    BFF JwtService uses:
      subject(sub) = String.valueOf(userId)
      claims: email, name, role
      iat, exp (1h TTL in prod; we override to 7d for load tests)
    """
    return {
        "sub": str(user_id),             # subject = userId (parsed via getSubject)
        "email": f"{username}@loadtest.ep.local",
        "name": username,
        "role": role,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
    }


def main():
    private_key = load_private_key(KEY_PATH)
    now = int(time.time())
    print(f"Using key: {KEY_PATH}")

    result = {}
    total = 0
    for group in GROUPS:
        entries = []
        for uid in range(group["id_start"], group["id_end"] + 1):
            username = f"{group['username_prefix']}_{uid}"
            payload = build_payload(uid, group["role"], username, now)
            token = jwt.encode(payload, private_key, algorithm="RS256")
            if isinstance(token, bytes):  # PyJWT < 2 returned bytes
                token = token.decode("utf-8")
            entries.append({
                "userId": str(uid),
                "token": token,
                "role": group["role"],
            })
            total += 1
        result[group["name"]] = entries
        print(f"{group['name']}: {len(entries)} tokens "
              f"(userId {group['id_start']}-{group['id_end']})")

    with open(OUTPUT_PATH, "w") as f:
        json.dump(result, f, ensure_ascii=False)

    print(f"\nTotal tokens: {total}")
    print(f"Written to: {OUTPUT_PATH}")
    print(f"File size: {os.path.getsize(OUTPUT_PATH)} bytes")

    # Sanity: decode one token (without signature verification) to confirm structure
    sample = result["students"][0]["token"]
    decoded = jwt.decode(sample, options={"verify_signature": False,
                                          "verify_exp": False})
    print(f"\nSample student token decoded payload: {decoded}")


if __name__ == "__main__":
    main()

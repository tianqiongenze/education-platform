# syntax=docker/dockerfile:1.7
# D-115: Multi-stage Dockerfile for sp04-ai (AI middle-platform).
#
# Service:  sp04-ai  (ai_gateway: Litestar ASGI app served by Granian)
# Module:   ai_gateway.app:create_app  (Litestar app factory)
# Port:     8086 (K8s Deployment maps 8086 -> container 8086)
# Health:   GET /health -> 200 JSON  (defined in ai_gateway/routes.py)
#
# The service uses Poetry (pyproject.toml) and depends on the local path
# dependency ep-common -> ../ep-python-common. The Docker build context is
# services/ so both sp04-ai/ and ep-python-common/ are available.
#
# Build:
#   docker build -t harbor.edu.internal/education-platform/sp04-ai:1.0.0 \
#       -f services/sp04-ai/Dockerfile services/

ARG PY_VERSION=3.12

# ---------- builder ----------
FROM python:${PY_VERSION}-slim AS builder

# build-essential: needed to compile some C-extension wheels (psycopg, pgvector).
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Poetry (pinned for reproducibility).
RUN pip install --no-cache-dir "poetry==2.0.1"

WORKDIR /src

# Copy the ai-platform project and its local path dependency.
COPY ai-platform/ ./ai-platform/
COPY libs-python/ep-common-python/ ./libs-python/ep-common-python/

WORKDIR /src/ai-platform

# Export a requirements.txt from Poetry (resolves the local path dep) and
# install all wheels into /install so the runtime stage stays small.
RUN poetry export --without-hashes --format=requirements.txt -o /tmp/requirements.txt && \
    pip install --no-cache-dir --prefix=/install -r /tmp/requirements.txt

# ---------- runtime ----------
FROM python:${PY_VERSION}-slim

# tini: tiny init for clean signal handling; curl: healthcheck.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tini curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 app

# Copy installed packages from builder.
COPY --from=builder /install /usr/local

WORKDIR /app

# Copy the application source (ai_gateway + sibling ai_* packages declared in
# pyproject.toml [tool.poetry.packages]).
COPY --from=builder /src/ai-platform/ ./

# Non-root user.
USER app

# Granian is the ASGI server declared in pyproject.toml. ai_gateway.app:create_app
# is the Litestar app factory (returns a Litestar instance).
ENV GRANIAN_WORKERS=2
ENV PYTHONUNBUFFERED=1

EXPOSE 8086

# /health endpoint defined in ai_gateway/routes.py.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8086/health || exit 1

# Granian ASGI entrypoint. --interface asgi matches the Litestar app factory.
CMD ["granian", "--interface", "asgi", "--host", "0.0.0.0", "--port", "8086", \
     "ai_gateway.app:create_app"]

ENTRYPOINT ["/usr/bin/tini", "--"]

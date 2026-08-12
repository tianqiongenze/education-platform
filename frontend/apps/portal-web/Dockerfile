# syntax=docker/dockerfile:1.7
# D-115: Multi-stage Dockerfile for portal-web (Vite + React 19 + AntD 5 SPA).
#
# Service:  portal-web (frontend/apps/portal-web)
# Build:    pnpm install + pnpm build  (tsc --noEmit && vite build -> dist/)
# Port:     80 (nginx) -> K8s maps to 3004
#
# The portal-web app is part of a pnpm workspace (frontend/) and depends on
# workspace packages (@ep/api-client, @ep/query, @ep/shared, @ep/ui-kit). The
# Docker build context is frontend/ so the whole workspace is available.
#
# Build:
#   docker build -t harbor.edu.internal/education-platform/frontend-portal:1.0.0 \
#       -f frontend/apps/portal-web/Dockerfile frontend/

ARG NODE_VERSION=22

# ---------- builder ----------
FROM node:${NODE_VERSION}-alpine AS builder

# pnpm is required by the workspace (packageManager: pnpm@11.0.0).
RUN corepack enable && corepack prepare pnpm@11.0.0 --activate

WORKDIR /repo

# Copy the whole workspace (manifests + lockfile first for better layer cache).
COPY frontend/ ./

WORKDIR /repo/apps/portal-web

# Install workspace deps (frozen lockfile for reproducibility).
RUN pnpm install --frozen-lockfile

# Build the portal-web app. vite.config.ts resolves @ep/* aliases to sibling
# packages; turbo/pnpm hoists them into node_modules.
RUN pnpm run build

# ---------- runtime ----------
FROM nginx:alpine

# Replace the default nginx config with the SPA-aware config.
COPY --from=builder /repo/apps/portal-web/nginx.conf /etc/nginx/conf.d/default.conf

# Copy the built static assets into the nginx html root.
COPY --from=builder /repo/apps/portal-web/dist /usr/share/nginx/html

# nginx alpine image ships as root; switch to the non-root nginx user.
# (The nginx user is created by the base image; UID 101.)
USER nginx

EXPOSE 80

# Healthcheck: nginx serves a 200 on / for SPA root.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ || exit 1

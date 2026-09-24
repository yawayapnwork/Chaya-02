# syntax=docker/dockerfile:1.7
# Chaya 02 web app (Next.js standalone server).
#
# Build once, run anywhere: nothing environment-specific is baked in. The browser configuration is read at request time
# from CHAYA_PUBLIC_API_BASE_URL / CHAYA_PUBLIC_OIDC_ISSUER / CHAYA_PUBLIC_OIDC_CLIENT_ID and the server-side API URL
# from CHAYA_API_BASE_URL (lib/public-config.ts, DEPLOYMENT.md). None of these is secret; the image holds no secrets.

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build && mkdir -p public

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
# Only the traced standalone server and its static assets; no node_modules install, no build tooling, no sources.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
# The one writable path (Next's image/fetch cache); everything else stays root-owned and read-only to the app.
RUN mkdir -p .next/cache && chown node:node .next/cache
USER node
EXPOSE 3000
# Readiness: browser configuration complete AND the API reachable (app/api/health/route.ts). Liveness: /api/health/live.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "server.js"]

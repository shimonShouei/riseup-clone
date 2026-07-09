# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM node:22-slim AS build
WORKDIR /app

# Download Puppeteer's matching Chromium INTO the app tree (not the default
# ~/.cache) so it survives the copy into the runtime stage. israeli-bank-scrapers
# expects the Chromium revision puppeteer bundles, so we let puppeteer manage it
# rather than substituting a system chromium (avoids version drift).
ENV PUPPETEER_CACHE_DIR=/app/.cache/puppeteer

# Install with the committed lockfile for reproducible, pinned deps (R11).
# npm ci runs puppeteer's postinstall, which fetches Chromium into the cache dir.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
# Compile TS -> dist, then drop devDependencies so they never reach runtime.
# (npm prune leaves the Chromium cache dir untouched — it lives outside node_modules.)
RUN npm run build && npm prune --omit=dev

# ---------- runtime stage ----------
FROM node:22-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app

# Headless Chromium's shared-library runtime deps. This is what makes the image
# large: these libs + the ~170MB Chromium binary add roughly ~400MB over the
# ~200MB node:22-slim base (final image ~600MB). It is the price of self-hosted
# Puppeteer scraping. Clean apt lists to keep it as small as possible.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      fonts-liberation \
      libasound2 \
      libatk-bridge2.0-0 \
      libatk1.0-0 \
      libatspi2.0-0 \
      libcairo2 \
      libcups2 \
      libdbus-1-3 \
      libdrm2 \
      libexpat1 \
      libgbm1 \
      libglib2.0-0 \
      libnspr4 \
      libnss3 \
      libpango-1.0-0 \
      libx11-6 \
      libxcb1 \
      libxcomposite1 \
      libxdamage1 \
      libxext6 \
      libxfixes3 \
      libxkbcommon0 \
      libxrandr2 \
    && rm -rf /var/lib/apt/lists/*

# Puppeteer must find the Chromium we downloaded in the build stage.
ENV PUPPETEER_CACHE_DIR=/app/.cache/puppeteer

# node:*-slim ships a non-root `node` user (uid 1000). We run as it (R10).
# Copy only the pruned prod deps, compiled output, and the browser cache — no
# source, no secrets.
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/.cache ./.cache
COPY --chown=node:node package.json ./

USER node

# R2: default to loopback. Under docker-compose we set BIND_HOST=0.0.0.0 but
# publish the port to the host loopback / mesh iface only (see docker-compose.yml).
ENV BIND_HOST=127.0.0.1 \
    PORT=8443
EXPOSE 8443

# Secrets (AUTH_TOKEN, TLS key/cert) are injected at runtime via env / mounted
# files — NEVER baked into an image layer (R7).
#
# Note on hardening (R5/R6/R12): Chromium launches headless with --no-sandbox
# --disable-dev-shm-usage (see src/scraper/discount.real.ts), so it needs no
# extra Linux caps and writes its throwaway profile under /tmp. With the
# read-only root FS in docker-compose.yml, /tmp is a tmpfs — the browser's
# profile/cache is RAM-only and vanishes on teardown, leaving nothing at rest.
CMD ["node", "dist/server.js"]

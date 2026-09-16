# node:20-alpine (musl) is what every quick Node Dockerfile defaults to, but
# mediasoup's prebuilt mediasoup-worker binary targets glibc — Alpine falls
# back to a slow from-source C++ build (needing python3/make/g++/linux-headers
# on top) instead of just downloading the prebuilt. bookworm-slim (Debian,
# glibc) gets the fast prebuilt-binary path, confirmed locally: `npm install`
# here took under a minute with zero native toolchain packages installed.
# Also matches every OTHER service in this repo already being Debian-family
# (eclipse-temurin:*-jammy) — not a new OS family, just a new runtime.
FROM node:22-bookworm-slim AS build
WORKDIR /build
# mediasoup's postinstall first tries a prebuilt mediasoup-worker binary; if
# the fetched binary fails its own runtime self-check on this build host (seen
# under some sandboxed/emulated Docker builders), it falls back to compiling
# the worker from source, which needs python3 + a C++ toolchain — bookworm-
# slim has neither by default.
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip make g++ ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json* ./
RUN npm install
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-bookworm-slim
WORKDIR /app
# Reuse the build stage's already-installed node_modules (which has a working
# compiled mediasoup-worker binary inside it) instead of running npm install
# a second time here — that second install would hit the exact same
# prebuilt-binary-fails-self-check fallback and need the same build toolchain
# all over again, just to reproduce output we already have.
COPY --from=build /build/node_modules ./node_modules
COPY --from=build /build/dist ./dist
COPY package.json ./
EXPOSE 8095
ENTRYPOINT ["node", "dist/index.js"]

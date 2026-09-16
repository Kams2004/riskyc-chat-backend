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
COPY package.json package-lock.json* ./
RUN npm install
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --omit=dev
COPY --from=build /build/dist ./dist
EXPOSE 8095
ENTRYPOINT ["node", "dist/index.js"]

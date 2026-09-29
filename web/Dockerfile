# Deployed as its own Railway service with Root Directory = web, so this
# build context is web/ itself (no web/ prefix on COPY paths).

FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Vite inlines VITE_* vars at build time, so this has to be a build arg, not a
# runtime env var -- set it as a Railway "build variable" pointing at the
# backend service's public URL, e.g. https://api.ballknowers.co
ARG VITE_API_BASE
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

FROM node:20-alpine AS runtime
WORKDIR /app
RUN npm install -g serve@14.2.6
COPY --from=build /app/dist ./dist
# Security headers live here, deliberately outside dist/ and public/ so the file
# is not itself published. See DEPLOY.md "Security headers".
COPY serve.json ./serve.json
# Railway assigns PORT at runtime; -s enables SPA fallback to index.html for
# react-router-dom's client-side routes. --config is resolved relative to the
# served directory (dist), hence ../serve.json.
CMD ["sh", "-c", "serve -s dist --config ../serve.json -l ${PORT:-3000}"]

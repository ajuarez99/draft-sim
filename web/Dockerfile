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
# Spec 052: Umami Cloud website id, a build variable like the one above. Optional --
# absent means analytics is off (no tracker script is added to the page).
ARG VITE_UMAMI_WEBSITE_ID
ENV VITE_UMAMI_WEBSITE_ID=$VITE_UMAMI_WEBSITE_ID
# Spec 073: which site this service builds, and where each site lives. All three are build
# variables like the ones above (Vite inlines them). VITE_SITE is "timemachine", "leagues", or unset
# for the combined single site; any other value FAILS the build on purpose. The two origins default to
# https://www.ballknowers.co and https://leagues.ballknowers.co. DEPLOY.md "Two sites".
ARG VITE_SITE
ENV VITE_SITE=$VITE_SITE
ARG VITE_TM_ORIGIN
ENV VITE_TM_ORIGIN=$VITE_TM_ORIGIN
ARG VITE_LEAGUES_ORIGIN
ENV VITE_LEAGUES_ORIGIN=$VITE_LEAGUES_ORIGIN
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

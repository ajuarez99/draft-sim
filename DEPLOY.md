# Deploying draft-sim

Written 2026-08-29, before any deploy has happened. Nothing here has been executed;
it is the intended order, with the things most likely to go wrong called out.

Local development is unaffected by everything in this document. Every deployment
value has a working local default, so `./gradlew bootRun` still needs no environment.

## Not done yet — read this first

**The frontend cannot talk to a remote backend.** Two pieces are missing:

1. `web/src/api.ts` calls `/api/...` as a relative path, which only works when the UI
   and API share an origin. It needs a `VITE_API_BASE` env var.
2. Nothing sends the `Authorization: Bearer` header, so once `API_TOKEN` is set the
   frontend gets a 401 on every call.

Both are small — one module, maybe twenty lines. Until they're done you can deploy
the **backend** and drive it with curl or Postman, but the deployed UI won't work.
Decide whether you want that before starting.

## Shape of it

    Vercel            web/            static build of the Vite app
    Fly / Railway     Dockerfile      Spring Boot, JVM 21
    Neon / Supabase   Postgres 17     managed, with SSL

Three services, three sets of credentials. The backend is the only one that holds
secrets.

## 1. Database

Neon or Supabase both work. Create a database named `draftsim` and take the
connection string.

Convert it to a JDBC URL — managed Postgres almost always requires SSL, and Flyway
will fail on the first migration without it:

    DB_URL=jdbc:postgresql://<host>/draftsim?sslmode=require

Do **not** run `docker compose up` against a remote database. The compose file is for
local Postgres only.

## 2. Backend

The repo root `Dockerfile` is self-contained — it builds from source and bakes in
`config/weights.yml`. Build context must be the repo root, not `backend/`.

Rehearse it locally before touching a platform:

    docker compose --profile full up --build
    curl localhost:8080/api/health

That runs the real production image against local Postgres. If it works there it will
work on Fly.

Then, for Fly:

    fly launch --no-deploy        # answer no to the Postgres prompt, you have one
    fly secrets set DB_URL="..." DB_USER="..." DB_PASSWORD="..." API_TOKEN="$(openssl rand -base64 48)"
    fly deploy

Railway and Render are the same idea: point them at the repo, they find the Dockerfile,
you set the same variables in their UI.

### Environment variables

| Variable | Required | Notes |
|---|---|---|
| `DB_URL` | yes | JDBC form, with `?sslmode=require` |
| `DB_USER` | yes | |
| `DB_PASSWORD` | yes | |
| `API_TOKEN` | yes | `openssl rand -base64 48`. Blank means **auth off** — see below |
| `CORS_ORIGINS` | yes | Exact frontend origin, scheme included, no trailing slash |
| `PORT` | usually not | Most platforms inject it; the app reads it |
| `LOG_LEVEL` | no | `INFO` in production; defaults to `DEBUG` |
| `WEIGHTS_FILE` | no | The image sets it to `/app/config/weights.yml` |
| `DB_POOL_SIZE` | no | Defaults to 10 |

`API_TOKEN` being blank disables authentication entirely and every endpoint —
`/api/ingest/*` included — becomes open to the internet. The app logs a warning at
startup when this happens. Check the logs on the first deploy; do not assume.

## 3. Verify, one stage at a time

Do these in order. Each one isolates a different failure.

    # 1. process is up and config bound
    curl https://<backend>/api/health
    #    -> weightsLoaded must be true. False means WEIGHTS_FILE didn't resolve
    #       and simulations will NPE later instead of failing now.

    # 2. auth is actually on
    curl -i https://<backend>/api/board
    #    -> expect 401. A 200 here means API_TOKEN is blank.

    # 3. auth accepts the real token
    curl -H "Authorization: Bearer $API_TOKEN" https://<backend>/api/board
    #    -> expect 409 "no blended board" before ingest has run. That is success:
    #       it means you got past the filter and reached the controller.

    # 4. ingest (slow — see timeouts below)
    curl -X POST -H "Authorization: Bearer $API_TOKEN" \
      https://<backend>/api/ingest/all/1391509063170293760

    # 5. read the board
    curl -H "Authorization: Bearer $API_TOKEN" "https://<backend>/api/board?limit=40"

## 4. Frontend

Only after the two changes at the top of this file exist.

Vercel's **root directory must be set to `web`** — the repo root is not a Vite project
and auto-detection will either fail or build the wrong thing. Then set `VITE_API_BASE`
and the token variable in Vercel's environment settings, and add the resulting origin
to the backend's `CORS_ORIGINS`.

Note that any token shipped to a browser is readable by anyone who opens devtools.
That is acceptable for a private tool you alone use and is not acceptable if you ever
share the URL. If it needs to be shareable, the token has to move server-side — at
which point you want real auth rather than a bigger shared secret.

## Things that will probably bite

**Memory on free tiers.** `POST /api/ingest/players` pulls Sleeper's ~5MB player dump
and parses it into a map of maps. Peak heap is well above the file size. A 256MB
instance may OOM; 512MB should be comfortable. `JAVA_OPTS` already sets
`-XX:MaxRAMPercentage=75`.

**Request timeouts on ingest.** `/api/ingest/all/...` does three sequential Sleeper
crawls plus a board rebuild. Platform HTTP timeouts are often 30–60s. If it times out,
call the three sub-endpoints separately — `/api/ingest/players`, then
`/api/ingest/league/{id}`, then `/api/ingest/board` — which is exactly why they exist
as separate routes.

**SSE through a proxy.** `/api/sims/stream` is a long-lived streaming response. Some
platforms buffer it, which turns live progress into one delayed dump at the end. The
plain `POST /api/sims` returns the same payload without streaming if that happens.

**CORS origin exactness.** `https://foo.vercel.app` and `https://foo.vercel.app/` are
different values here, and Vercel preview deployments each get their own origin. Expect
to add more than one.

**Flyway on first boot.** The migration has been verified by hand against Postgres 16,
but Flyway's own bookkeeping has never run. Watch the first deploy's logs.

## Rolling back

The database is the only stateful part and migrations so far are additive, so rolling
the backend image back is safe. `fly releases` / `fly deploy --image <previous>`.

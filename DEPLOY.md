# Deploying Ball Knowers

Written 2026-08-29, before any deploy has happened. Nothing here has been executed;
it is the intended order, with the things most likely to go wrong called out.

Local development is unaffected by everything in this document. Every deployment
value has a working local default, so `./gradlew bootRun` still needs no environment.

## Not done yet — read this first

**Built 2026-09-07**: `web/src/api.ts` now routes every call through an `apiFetch`
helper that prepends `VITE_API_BASE` (blank = same-origin, unchanged from before)
and sends `Authorization: Bearer ${VITE_API_TOKEN}` when that's set. See
`web/.env.example`.

**One gap remains, and it's structural, not an oversight:** `useLiveDraft.ts`
subscribes to `/api/drafts/{id}/live-stream` with the browser's native
`EventSource`, which has no way to attach a request header at all. `apiUrl()`
still routes its URL through `VITE_API_BASE`, but there is no way to also send
the bearer token on that one connection. **Live mode will 401 against any
split-origin deploy that has `API_TOKEN` set** — same-origin deploys and
auth-off deploys are both unaffected. Fixing it needs a backend-side answer
(most likely: accept the token as a query-string parameter on that one route,
same tradeoff as shipping it to the frontend at all — see the token note
below) before it can be called done. Left alone for now because it's a
one-route problem, not a reason to block deploying everything else.

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

Vercel's **root directory must be set to `web`** — the repo root is not a Vite project
and auto-detection will either fail or build the wrong thing. Then set `VITE_API_BASE`
and `VITE_API_TOKEN` in Vercel's environment settings (see `web/.env.example`), and add
the resulting origin to the backend's `CORS_ORIGINS`. Live mode needs the gap noted
above resolved first if `API_TOKEN` is set.

Note that any token shipped to a browser is readable by anyone who opens devtools.
That is acceptable for a private tool you alone use and is not acceptable if you ever
share the URL. If it needs to be shareable, the token has to move server-side — at
which point you want real auth rather than a bigger shared secret.

## Multiple people

claude/user-identity-and-onboarding.md: anyone can type a Sleeper username at
`/` and see their own leagues, with their own seat highlighted. Four things
worth knowing before that goes out to more than Allan:

- **This is identification, not authentication.** Anyone can type `popsharky`
  and get Allan's seat highlighting and Allan's league list. There is no
  password and nothing is hidden. That is an accepted tradeoff for a
  friends-and-league-mates tool, and it stops being acceptable the moment
  anything private lands in the database.
- **The API token is shared.** `VITE_API_TOKEN` is in every visitor's
  devtools, so every visitor can call `/api/ingest/*` and `/api/drafts`
  unscoped (`GET /api/drafts` with no `X-Sleeper-User` header still returns
  everyone's leagues, by design — see §2 above). Signing in scopes what the
  *app* shows a given visitor; it does not restrict what the *API* will
  answer to a raw request. The real fix is a server-side proxy or real auth;
  the interim mitigation, if it matters, is same-origin hosting so at least
  nothing new is exposed beyond the pre-existing token-in-devtools tradeoff.
- **Live mode's EventSource still cannot send the token** (the gap noted at
  the top of this document). It bites a split-origin deploy with `API_TOKEN`
  set. If draft night matters more than the deploy shape, either keep it
  same-origin or accept the token as a query parameter on that one route.
- **`APP_OWNER_SLEEPER_USER_ID` is now a fallback**, not the identity — see
  `.env.example`. `X-Sleeper-User` (sent by every signed-in visitor's browser)
  wins when present; the configured owner only matters for a request with no
  header at all, which is every request until someone signs in.

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

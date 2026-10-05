# Deploying Ball Knowers

**This describes what is actually running.** Ball Knowers has been live since
2026-09-11 on Railway at `ballknowers.co`; this file was rewritten 2026-09-16
against the deployed thing, replacing a plan written before any deploy happened
that still described a fly.io/Vercel/Neon split that was never built.

Local development is unaffected by everything here. Every deployment value has a
working local default, so `./gradlew bootRun` still needs no environment.

## What is running

Railway project **joyful-unity**, environment **production**:

| Service | What | Root dir | Build | Domain |
|---|---|---|---|---|
| `draft-sim` | Spring Boot backend, JVM 21 | `/` | root `Dockerfile` | `api.ballknowers.co` |
| `perpetual-vitality` | Vite/React frontend | `web` | `web/Dockerfile` | `www.ballknowers.co` |
| `Postgres` | Railway Postgres plugin | — | — | internal |

The backend's root directory is the repo root, not `backend/` — the Dockerfile
needs both `backend/` and `config/` in its build context. `railway.toml` at the
repo root carries the backend's build and healthcheck config
(`healthcheckPath = /api/health`).

Postgres is wired to the backend with Railway reference variables
(`${{Postgres.PGHOST}}` and friends) rather than a pasted connection string, so
rotating the database does not mean editing the backend's variables.

**The bare apex forwards, it does not resolve.** `ballknowers.co` ->
`https://www.ballknowers.co` via Squarespace *domain forwarding*, because
Squarespace's DNS editor refuses a CNAME at `@`. It is not a second Railway
custom domain.

The Railway plan is **Hobby**, upgraded from the trial specifically to lift a
one-custom-domain limit that blocked adding `api.ballknowers.co` alongside
`www.ballknowers.co`. Compute usage is capped low (~$10); this is a near-zero
traffic hobby project.

## Deploying

**Push to `main`. Both services auto-deploy from GitHub.** Verified 2026-09-16:
three pushes were picked up without intervention, and `api.ballknowers.co` was
serving the new response shape within minutes.

There is nothing to run locally. The Railway CLI is not linked to this checkout
(`railway status` answers "No linked project"), so anything the dashboard would
do needs the dashboard, or `railway link` first.

### Two traps that have each cost real time

**"Redeploy" re-runs the SAME commit.** It is not "deploy latest". Clicking it
on a stale deployment reproduces the stale deployment, forever. Worse, the
replay then shows as "2 minutes ago via GitHub" carrying the OLD commit message,
so the deployment list looks current when it is not. **Check the commit message
against `git log`, never the timestamp.** To pull in new commits use
Settings -> Source -> **Check for updates**.

**Auto-deploy can be switched off per service, silently.** On 2026-09-15 the
`draft-sim` service had it off and drifted 28 commits behind the frontend. The
symptom was not an error — it was the frontend calling an API contract the
backend had never heard of, which white-screened the site. Re-enabled under
Settings -> Source -> "Auto deploys when pushed to GitHub".

### After any change to an API response shape

**Confirm BOTH services redeployed.** They deploy independently, and a
frontend-only deploy ships a client that talks to an older contract. One curl
answers it in seconds:

    curl https://api.ballknowers.co/api/health
    curl https://api.ballknowers.co/api/leagues/1346366555759341568/analysis | head -c 400

When a page breaks in production, check the deployed API's payload shape
*before* reading frontend code.

### A backend deploy runs migrations

Flyway runs pending migrations against the production Postgres on boot —
forward-only, and additive so far, but know it before clicking anything. V14
(`mock_draft_session.reversal_round`) and V15 (`player_projection`) both applied
on boot this way. Watch the deploy logs the first time a migration is in flight.

## Code is not data

**A deploy ships the application. It does not ship a database.** Production
Postgres is its own database and nothing in a build populates it.

This bit on 2026-09-16: the League analysis page went live and refused every
single block — no ranking score, no projections, no charts, no matchups —
because production had zero scored weeks and zero projections for the 2026
season while local had both. The page looked broken and was working exactly as
designed.

Filling it is two POSTs, **and the order matters**:

    curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" "https://api.ballknowers.co/api/ingest/league-history/<sleeperLeagueId>"
    curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" "https://api.ballknowers.co/api/ingest/projections?sport=nfl&season=2026&fromWeek=<lastScored+1>&toWeek=<playoffWeekStart-1>"

`/api/ingest/**` needs the admin token (see "The security posture" below): without
the `X-Admin-Token` header, or with `ADMIN_TOKEN` unset on the backend, every one of
these answers 403. `$ADMIN_TOKEN` here is the same value the `draft-sim` service has.

The history ingest is what sets `lastScored`, which decides the right `fromWeek`
for projections. Ask for that window rather than guessing it: after the history
ingest, `GET /api/leagues/<id>/analysis` (which needs `X-Sleeper-User: <a member's id>`, or the
admin token) reports it as `window.fromWeek`.

Measured against production: 2 seasons / 24 rosters / 216 weeks / 156 fixtures,
then 13 weeks / 6,044 projection rows in about two seconds. Both are idempotent.

In PowerShell, `curl` is an alias for `Invoke-WebRequest` and has no `-X`. Use
`curl.exe -X POST -H "X-Admin-Token: $env:ADMIN_TOKEN" "..."` or
`Invoke-RestMethod -Method Post -Headers @{'X-Admin-Token'=$env:ADMIN_TOKEN} -Uri "..."`.

### Repairing the league-seasons that lost their pairings

A second instance of "code is not data", and a nastier one, because nothing
looks broken.

`ingestWeeklyPoints` used to skip a week whenever its scores were already
cached, which took the `league_matchup` upsert down with it. `league_matchup`
arrived on 2026-09-14, so **every season ingested before that date has complete
scores and no pairings**, and re-running the ingest could not repair it -- the
skip was keyed on the table that was already full. Fixed in `945371e`.

Anything reading `league_matchup` then answers from whatever single week
survived. League history's record book reported this league's biggest blowout as
**67.6** when the real figure is **118.86**, with no empty state to give it
away, because the list was not empty.

**This cannot be fixed with SQL.** `league_matchup.matchup_id` is the only
pairing data in the schema and it was never written -- there is no column to
derive it from and no backup. The values exist only in Sleeper's matchups
endpoint, so repair means asking Sleeper again:

    DATABASE_URL="<prod url>" API=https://api.ballknowers.co ./scripts/repair-missing-pairings.sh
    ADMIN_TOKEN="<the backend's ADMIN_TOKEN>" DATABASE_URL="<prod url>" API=https://api.ballknowers.co ./scripts/repair-missing-pairings.sh --apply

Without `--apply` it reports and changes nothing. It finds the affected seasons,
collapses them to chain **heads** (the ingest walks `previous_league_id`
backwards, so the newest league in a chain repairs every season behind it in one
pass), re-ingests each, and re-checks.

**Deploy the fix first.** Against an old build the gate still skips the cached
weeks, and the script is a no-op that looks like it worked.

Measured locally on 2026-09-16: three affected league-seasons across two chains,
repaired in two calls (540 and 230 roster-weeks), after which the detection
query returned zero rows and every scored week had its pairing -- football 2025
went from 8 paired rows in one week to 196 across all 17.

To check without repairing anything:

    psql -d "$DATABASE_URL" -f scripts/find-missing-pairings.sql

In Git Bash, a `postgres://` URL passed positionally gets rewritten by MSYS path
conversion and psql silently drops every flag after it. Both scripts set
`MSYS2_ARG_CONV_EXCL` and pass the URL through `-d`.

## Environment variables

Set on the `draft-sim` (backend) service unless noted.

| Variable | Notes |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | Railway reference variables off the Postgres plugin |
| `API_TOKEN` | **Currently blank in production — the shared bearer gate is OFF.** See below |
| `ADMIN_TOKEN` | **The operator secret.** Sent as `X-Admin-Token`; gates `/api/ingest/**` and the commissioner-only actions, and overrides the membership checks for curl. **Blank means admin is DISABLED and every one of those refuses** (fail closed, the opposite of `API_TOKEN`). Server-side only: never a `VITE_` variable. **Not yet set in production** — set it before the next ingest, or none can be run |
| `REFRESH_SECRET` | The daily GitHub Action's secret, `X-Refresh-Secret`; blank means `/api/refresh/daily` does not exist. Deliberately separate from `ADMIN_TOKEN`: the cron should not hold a secret that can rewrite picks |
| `CORS_ORIGINS` | Exact frontend origin, scheme included, no trailing slash |
| `PORT` | Railway injects it; the app reads it |
| `LOG_LEVEL` | `INFO` in production; defaults to `DEBUG` |
| `WEIGHTS_FILE` | The image sets it to `/app/config/weights.yml` |
| `DB_POOL_SIZE` | Defaults to 10 |
| `APP_OWNER_SLEEPER_USER_ID` | **A seat-preselect default only, never an authorization.** It picks which draft seat to highlight; a header-less request is no longer let through because of it. (It still satisfies the commissioner *identity* check, which since 2026-10-05 is an honour system, not a gate) |
| `VITE_API_BASE` | **Frontend service.** Baked in at Docker *build* time |

**`VITE_API_BASE` is inlined by Vite at build time**, so changing it requires a
rebuild, not a restart with a new env value.

**A stray `JAVA_OPTS` holding garbage** once caused `Could not find or load main
class <garbage>`. If the backend will not boot, read the Variables tab for
anything unexpected before assuming the Dockerfile is broken.

## The security posture, stated plainly

**Verified** means run against a real Postgres and the real filters/CORS mapping in the test
suite; **assumed** means reasoned from the code and not executed. Where a line is assumed it
says so.

**Three separate mechanisms, easy to confuse:**

| Mechanism | Header | Blank/unset means | Protects |
|---|---|---|---|
| Sign-in identity | `X-Sleeper-User` | **the request sees nothing league-scoped** (it used to see everything) | league, draft, mock, refresh, manager routes |
| Admin token (`ADMIN_TOKEN`) | `X-Admin-Token` | admin **disabled**, its routes refuse | `/api/ingest/**`, curl override (commissioner actions until 2026-10-05) |
| Shared bearer (`API_TOKEN`) | `Authorization: Bearer` | gate **off**, every route open | a blanket gate; currently unused in production |

**What changed on 2026-09-29 (claude/audit-2026-09-28/01 and /04, option D).** Before it,
leaving `X-Sleeper-User` off got *more* than signing in as a stranger: production answered
200 to a header-less `GET /api/leagues/<id>/analysis` and 404 to a stranger's id, and a
header-less caller could read and pick into anyone's mock, or write any seat's manual pick.
Now a blank identity is refused on every scoped route (verified by `AccessControlMvcIT`). The
only override is a valid `X-Admin-Token`.

**What is protected now:**

- **Every league- and draft-addressed route** answers 404 for a caller who is not in that
  league *or has no identity*: the draft list (empty), each `/api/drafts/{id}/...` route
  including the live SSE stream's `?user=`, `/leagues/{id}/history`, `/power`, `/analysis`,
  `/ballot`, `/superlatives`, `/conduct-list`, `/roster-management`, `/transactions`,
  `/expected-wins`, `/forecast`, `/weekly-report/{week}`, the refresh routes,
  `/api/managers/{id}/history`, and forking a live draft into a mock. (`roster-management`,
  `transactions`, `expected-wins`, `forecast` and `weekly-report` had **no scoping at all**
  until this change.) The rule lives in `store/LeagueMembership`, pinned by
  `LeagueMembershipIT`.
- **Mock drafts:** listing shows nothing without an identity, reading and picking are the
  owner's only, and creating one needs an identity. An *unowned* legacy session (V8's closed
  set, owner null) is still usable by any signed-in identity, deliberately: it has no owner to
  defer to and can no longer be created.
- **`/api/ingest/**` needs the admin token.** A browser never holds it. The web app's own
  add-a-league, setup and "Load past seasons" calls go to `/api/setup/**` instead: the same
  work, authorised by membership (the caller must be signed in and appear in the league, by
  our data or by Sleeper's public league-users list). `POST /api/refresh/players` needs an
  identity.
- **Commissioner-only actions** (conduct-list add/remove, the commissioner ranking,
  "Recompute" (`/power/compute`), History's backfill, and a draft's reversal-round override)
  check the commissioner identity only. **Amended 2026-10-05:** from 2026-09-29 to 2026-10-05
  they also needed the admin token, but there was no way to get that token to another league's
  commissioner short of handing over the operator's secret, so in practice only the operator
  could use them. They are now on the honour system, like ballots (see below).

**Operator escape hatches (curl).** A valid `X-Admin-Token` lets a request with *no*
`X-Sleeper-User` through the membership checks. This is the draft-night manual pick:

    curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
         -d '{"pickNo":17,"sleeperPlayerId":"4046"}' https://api.ballknowers.co/api/drafts/<id>/picks

The token overrides only a *blank* identity; a named stranger stays a stranger. A commissioner
action from curl needs the commissioner's `X-Sleeper-User` (and, since 2026-10-05, no token).

**Local dev:** `ADMIN_TOKEN` defaults to blank, so locally `/api/ingest/**` **refuses** too. Set
one in the shell that runs `bootRun` (`ADMIN_TOKEN=dev ./gradlew bootRun`) and pass
`-H "X-Admin-Token: dev"` when you curl an ingest. The test JVM sets its own
(`build.gradle.kts`), so the suite does not depend on your environment.

**What is still only scoping, not security (assumed, and stated so):**

- `X-Sleeper-User` is an unverified claim and Sleeper ids are public. Sign-in is
  *identification, not authentication* (claude/user-identity-and-onboarding.md): anyone can type
  `popsharky` and get that manager's league list and seat highlighting. Anyone who wants a
  league's data can present a member's id. What the scoping buys is that the app no longer
  hands every visitor every league, and no longer gives anonymous callers *more* than members.
- **Ballots are on the honour system.** `POST /leagues/{id}/ballot` maps to whatever member id
  the request names, so a member's id (public) is enough to submit or overwrite their ballot.
  The ballot UI says ballots are not verified. Real protection needs identity the app issues
  itself (audit plan 04, option B).
- **Commissioner actions are on the honour system too** (since 2026-10-05). The commissioner
  identity is a header anyone can copy from Sleeper's public `GET /league/{id}/users`
  (`is_owner: true`), so anyone who looks it up can save a commissioner ranking, edit the
  conduct list, recompute power rankings, or change a draft's reversal round. Accepted
  deliberately: these are for fun, and the UI says so beside the controls.
- The setup routes are bounded by membership, not by rate: a member can re-run their own
  league's ingest as often as they like. `POST /api/refresh/players` is "once per sport per UTC
  day" only in the steady state: concurrent first calls, and calls while Sleeper's player
  endpoint is failing, each fetch (idempotent, but real traffic; not measured).
- Deliberately unscoped: `/api/board`, `/api/sims` beyond its draft check, and
  `GET`/`PUT`/`DELETE /api/managers`. Manager profiles are a shared model layer, not a
  per-league resource (audit plan 02 covers the tendency writes).
- `/api/health`, `/api/sleeper/user/*` (needed before sign-in) and CORS preflights stay open.

### If `API_TOKEN` is ever switched on

Two things break, both known:

- **`/api/drafts/{id}/live-stream` cannot send a bearer token.** The browser's
  native `EventSource` has no way to set a request header, so live mode 401s
  against a split-origin deploy with the token set — and this deploy is
  split-origin. It already takes its identity as `?user=` for the same reason;
  the token would need the same treatment.
- **Any token shipped to a browser is readable in devtools**, so it is a shared
  secret every visitor holds. The real fix is a server-side proxy or real auth,
  not a longer string.

### Security headers

The frontend's response headers (CSP, `X-Content-Type-Options`, `Referrer-Policy`,
HSTS) live in **`web/serve.json`**, copied into the runtime image by
`web/Dockerfile` and passed to `serve` with `--config ../serve.json` (resolved
relative to the served `dist/` directory). The file is deliberately not in
`public/`, or it would be published. `serve` is pinned (`serve@14.2.6`) so the
flag behaviour is stable. Verified locally, not yet on production.

- **The CSP is `Content-Security-Policy-Report-Only`, not enforcing.** It stays
  report-only until every page (sign-in, leagues, board, mock, live, /managers,
  power rankings, weekly report, history) has been clicked through on production
  with DevTools open and shown zero violations. Only then rename the header to
  `Content-Security-Policy`. Note `frame-ancestors` is ignored in report-only
  mode, so clickjacking protection starts only when it is enforced.
- **Adding a new external origin (API host, CDN, font, image host) means
  updating the CSP in `web/serve.json`.** Today: `https://api.ballknowers.co`
  (connect), `https://sleepercdn.com` (img), Google Fonts (style and font).
- HSTS is `max-age=31536000` with no `includeSubDomains`, until every
  `*.ballknowers.co` host is confirmed HTTPS-only.
- The backend (`api.ballknowers.co`) is a separate service and sends none of
  these; that is a separate decision.

## Things that will probably bite

**Memory.** `POST /api/ingest/players` pulls Sleeper's ~5MB player dump and
parses it into a map of maps; peak heap is well above the file size. 512MB is
comfortable, 256MB may OOM. `JAVA_OPTS` sets `-XX:MaxRAMPercentage=75`.

**Request timeouts on ingest.** `/api/ingest/all/...` does three sequential
Sleeper crawls plus a board rebuild. If a platform timeout cuts it short, call
the three sub-endpoints separately — `/api/ingest/players`, `/api/ingest/league/{id}`,
`/api/ingest/board` — which is exactly why they exist as separate routes. (All need
`X-Admin-Token`.)

**SSE through a proxy.** `/api/sims/stream` is a long-lived streaming response
and some platforms buffer it, turning live progress into one delayed dump.
`POST /api/sims` returns the same payload without streaming.

**CORS origin exactness.** `https://www.ballknowers.co` and
`https://www.ballknowers.co/` are different values here.

## Rolling back

The database is the only stateful part and migrations so far are additive, so
rolling the backend image back is safe. In Railway, redeploy the previous
deployment from the service's deployment list — and here "Redeploy re-runs the
same commit" is the behaviour you actually want.

## Rehearsing locally

`docker compose --profile full up --build` runs the real production image
against local Postgres. If `curl localhost:8080/api/health` returns
`weightsLoaded: true` there, the image is sound. Do **not** point
`docker compose up` at a remote database; the compose file is for local
Postgres only.

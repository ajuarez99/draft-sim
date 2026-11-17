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
| `ADMIN_TOKEN` | **The operator secret.** Generate it with `openssl rand -base64 32` and keep it in a password manager: with the lock on it is also typed (pasted, not memorised) into the league site's lock screen, and the backend logs one WARN at startup if it is shorter than 32 characters. League sessions and stream tickets are signed with keys derived from it, so a captured session token is an offline guessing oracle for a short token, and **rotating it ends every session at once** (the only revocation there is). There is **no rate limit** on the unlock route (`POST /api/admin/session`) or on any other `X-Admin-Token` check: a long random token is the defence. Sent as `X-Admin-Token`; gates `/api/ingest/**` and the commissioner-only actions, and overrides the membership checks for curl. **Blank means admin is DISABLED and every one of those refuses** (fail closed, the opposite of `API_TOKEN`). Server-side only: never a `VITE_` variable. **Not yet set in production** — set it before the next ingest, or none can be run |
| `LEAGUE_LOCK` | `true` locks the league API behind a session (spec 073, second pass). **Default off.** Needs `ADMIN_TOKEN` set (it signs the sessions). Turn on only **after** the league site is split off, see "Two sites". Unset = rollback |
| `LEAGUE_LOCK_IDLE_SECONDS` | Idle window of a league session, default `300` (5 minutes, hand-set: "5 min or something") |
| `LEAGUE_LOCK_MAX_SESSION_SECONDS` | Absolute lifetime of a session from the original unlock, however often it is renewed, default `43200` (12 hours, hand-set). A leaked or scripted token cannot be kept alive past this |
| `REFRESH_SECRET` | The daily GitHub Action's secret, `X-Refresh-Secret`; blank means `/api/refresh/daily` does not exist. Deliberately separate from `ADMIN_TOKEN`: the cron should not hold a secret that can rewrite picks |
| `BK_CREDENTIAL_KEY` | Spec 035. 32 random bytes, base64 (`openssl rand -base64 32`); seals the ESPN cookies a user pastes for a private ESPN league (AES-256-GCM). **Absent = private ESPN leagues disabled** (public ESPN leagues still work; there is no plaintext fallback). Server-side only: never a `VITE_` variable, never logged. Rotating it makes every stored cookie set unreadable, so users re-paste them |
| `CORS_ORIGINS` | Exact frontend origin, scheme included, no trailing slash |
| `PORT` | Railway injects it; the app reads it |
| `LOG_LEVEL` | `INFO` in production; defaults to `DEBUG` |
| `WEIGHTS_FILE` | The image sets it to `/app/config/weights.yml` |
| `DB_POOL_SIZE` | Defaults to 10 |
| `APP_OWNER_SLEEPER_USER_ID` | **A seat-preselect default only, never an authorization.** It picks which draft seat to highlight; a header-less request is no longer let through because of it. (It still satisfies the commissioner *identity* check, which since 2026-10-05 is an honour system, not a gate) |
| `VITE_API_BASE` | **Frontend service.** Baked in at Docker *build* time |
| `VITE_UMAMI_WEBSITE_ID` | **Frontend service.** Spec 052. A build variable, like `VITE_API_BASE`. Absent = analytics off: no tracker script is added and nothing is sent. See "Analytics" below |

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
- **The Redraft site's web build stops working** (spec 074). Its build fetches the season pages' data from
  `/api/history/public/**` with no bearer, so a gate that is on answers 401 and the build fails. Before
  switching `API_TOKEN` on, exempt `/api/history/public/**` in `ApiTokenFilter`. Never solve it with a
  `VITE_` token: that would put the secret in the build output. (Review-fixes B3: read from the code, not
  tried, because the gate has never been on in production.)

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
  (connect), `https://sleepercdn.com` (img), Google Fonts (style and font), and
  (spec 052) `https://cloud.umami.is` (script, the analytics tracker) and
  `https://gateway.umami.is` (connect, where it sends counts).
- HSTS is `max-age=31536000` with no `includeSubDomains`, until every
  `*.ballknowers.co` host is confirmed HTTPS-only.
- The backend (`api.ballknowers.co`) is a separate service and sends none of
  these; that is a separate decision.

## Two sites (spec 073)

One `web/` codebase, built in one of three modes by the build variable `VITE_SITE`:

| `VITE_SITE` | Serves | Address |
|---|---|---|
| unset | The combined single site, Time Machine at `/history` | Today's web service until you flip it |
| `timemachine` | Only the Time Machine, at `/` and `/game/:gameId` | `www.ballknowers.co` |
| `leagues` | Everything except the Time Machine | `leagues.ballknowers.co` |

Anything else (a typo such as `leauges`) fails the build with a message naming the value. A trailing space
from a Windows `set VITE_SITE=x && ...` is trimmed. All three variables below are **build** variables, like
`VITE_API_BASE`: change one and rebuild.

| Variable | Notes |
|---|---|
| `VITE_SITE` | `timemachine`, `leagues`, or unset (combined) |
| `VITE_TM_ORIGIN` | Time Machine site origin, no trailing slash. Default `https://www.ballknowers.co` |
| `VITE_LEAGUES_ORIGIN` | League site origin, no trailing slash. Default `https://leagues.ballknowers.co` |

Merging this change to `main` shows nothing new: the existing web service has no `VITE_SITE`, so it keeps
building the combined site. The split happens when you set variables, in this order.

**The league site is locked to Allan (2026-11-16).** The `leagues` build asks for the backend's admin token
before it shows anything, and that is a **required step before the flip: set `ADMIN_TOKEN` on the backend
service in production.** The variables table in this file still says it is **not yet set in production**.
With it unset (blank), the backend refuses every token, so the league site cannot be opened by anyone,
including you. Set it, redeploy the backend, and check the lock screen accepts it before step 6.

This is a **front-door lock only**, by your choice, and it is not security. The league site's pages stay
behind the prompt, but every league API route answers as openly as it did before, with or without the token,
and the "unlocked" marker is a browser-storage value that can be set by hand. The site also asks search
engines not to index it (`noindex`, `Disallow: /`, no sitemap), which well-behaved crawlers honour and
nothing enforces. The Time Machine site and the combined site have no lock.

**Amended 2026-11-16 (second pass): the league API is locked too.** The paragraph above describes the first pass and is kept as written. Allan reversed it the same day: with `LEAGUE_LOCK=true` on the backend, every league API route needs a league session (minted by `POST /api/admin/session` from the admin token, 5 minutes idle, renewed while you are active), the admin token, or, on the live draft stream only, `?session=`. The Time Machine (`/api/history/**`), sign-in's username lookup, `/api/health`, the daily refresh job (its own `X-Refresh-Secret`) and the admin/ingest routes stay as they are. Operator `curl` uses `X-Admin-Token` exactly as before. See `specs/073-split-sites/plan.md` for what this does and does not protect.

**Amended 2026-11-16 (lock fix pass, CR-01..CR-12): the stream no longer takes `?session=`.** The live draft stream's URL carries a separate 60-second, single-draft `?ticket=` the web fetches from `POST /api/league-session/stream-ticket` right before each connection; a session token in a query string is refused everywhere, and a ticket is refused everywhere except its own draft's stream. Sessions also have an absolute lifetime (`LEAGUE_LOCK_MAX_SESSION_SECONDS`). Two consequences for deploying:

- **The backend must be deployed with this change before the league site's lock screen can work.** An older backend answers `POST /api/admin/session` with 404, which the lock screen reports as "Couldn't reach the server"; and a backend from before this pass would not know tickets, so the live room of a new web build cannot open its stream against it. Deploy the backend first, then the league web service.
- **Once `LEAGUE_LOCK=true`, every operator script or curl that reads a league route needs `X-Admin-Token`** (for example `scripts/check-weeks-vs-sleeper.py` and the capture scripts read `/api/leagues/...`). Without it they get `401 league_session_required`. The ingest and admin routes were already behind that header and are unchanged. A browser tab still open on the old combined site gets errors, not a lock screen, once the lock is on.

> **Do not set `LEAGUE_LOCK=true` before the flip.** The live combined site shares this backend: turning the lock on first locks every league page (leagues, drafts, mocks, managers) on the still-combined site **for everyone**, because their browsers hold no session. Only the `leagues` build knows how to ask for one. Set it after step 6.

**Rollout**

1. **Check first (unconfirmed):** whether your Railway plan allows another custom domain on a second web
   service. This was not looked up. If it does not, stop here; nothing else depends on it.
   *Amended 2026-11-16: Allan reports the Hobby plan ($5/month) allows up to 2 custom domains **per service**,
   so a domain on the second web service does not count against the first. Reported by Allan, not checked
   against Railway by the session that wrote this.*
2. **Backend service:** append `,https://leagues.ballknowers.co` to `CORS_ORIGINS` (comma-separated, exact
   origins, no trailing slash). Redeploys the backend; harmless on its own.
3. **New Railway web service** from the same repo, Root Directory `web`, build variables `VITE_SITE=leagues`,
   `VITE_API_BASE` (same value as the existing web service) and optionally `VITE_UMAMI_WEBSITE_ID`.
4. **Custom domain** `leagues.ballknowers.co` on that service, with the CNAME Railway shows added in Squarespace.
5. **Set `ADMIN_TOKEN` on the backend** if it is still blank (required, see above), then **check the league site** at its real address: enter the token at the lock screen, sign in, open a league, a draft board and a mock. If sign-in
   answers "Couldn't reach Sleeper just now", the backend does not allow that origin: step 2 was missed or
   mistyped (the browser blocks the call, and the page can only report that it failed).
6. **Flip the main site:** set `VITE_SITE=timemachine` on the existing web service and redeploy. From then on
   `www.ballknowers.co` is the Time Machine, old league links forward to `leagues.ballknowers.co`, and old
   `/history` links land on the Time Machine home or game. Do steps 5 and 6 in one sitting: between them the
   league site forwards `/history/<id>` to the main address, which still serves it under `/history` (the league
   site forwards to `/history`, not `/game`, so a forwarded link works in either state), and nobody has the
   league address until you hand it out.
7. **Lock the API:** set `LEAGUE_LOCK=true` on the backend (and `ADMIN_TOKEN`, already required) and redeploy it, **only after step 6**. Check: the league site accepts the token, you can browse, and 5 minutes without touching it returns the lock screen; `www.ballknowers.co` (Time Machine) still plays. The external daily refresh job keeps working (its own secret). `LEAGUE_LOCK_IDLE_SECONDS` changes the window.
8. **Re-set your analytics opt-out** on `leagues.ballknowers.co` (open it in each browser you use and set
   `localStorage['umami.disabled']`). The opt-out is stored per address and is not carried.

**What carries across and what does not.** Sign-in is a Sleeper username kept per address, so each site asks
once. Time Machine games, achievements and streaks live in the backend against the Sleeper user and need no
move. Saved stat-calculator formulas live in the browser per address: they ride along only when someone is
**forwarded** from the main address to the league site (an old league link), and only when the league site has **never saved formulas** of its own (a person who
deleted theirs there is not given the old ones back). A league-mate who types the league address directly
starts with none there; their old ones stay on the main address and are carried the first time a forward
happens. The carry is capped at 24,000 characters of URL fragment (roughly 320 per formula, so a full store of
50 fits); a store bigger than that sends the newest formulas that fit. Display preferences reset on the league
site (they default); the analytics opt-out is not carried.

**Analytics.** Each site reports under its own hostname (`www.ballknowers.co`, `leagues.ballknowers.co`).
Whether they share one Umami website id or use two is your call: `VITE_UMAMI_WEBSITE_ID` on each service.
Known, accepted change (code review CR-08): a visitor forwarded between the two sites is recorded with no
outside referrer, because the forward leg sends no view and the destination sees the other site as its
referrer, which counts as inside. Their original source (a group chat, a search) is not kept.

There is no "your leagues moved" notice on the Time Machine site (spec FR-019, withdrawn 2026-11-16): the
league site is private, so league-mates are not sent there. Old league links on the Time Machine site still
forward to the league address, which shows the lock screen to anyone but you. The league site sends no page
view while locked.

**Rolling back.** To unlock the API, unset `LEAGUE_LOCK` and redeploy the backend (nothing else needs to change). To undo the split, remove `VITE_SITE` from the main web service and redeploy: the main address is the combined
site again. The league service can stay up (its `/history` forwards land on the combined site's `/history`) or be
deleted.

**Not checkable before the switch:** real DNS, Railway's build of each mode, production CORS, Umami receiving
the league host, and how link-preview bots render the Time Machine tags and image (`og-image-tm.png`).

## Ball Knowers Redraft: static season pages and search (spec 074)

The public game that was called "Time Machine" is now **Ball Knowers Redraft**. The `VITE_SITE` value is
still `timemachine` (the variable name did not change). On that site the web build now writes real HTML
files: `/nba/<season>`, `/nfl/<season>`, `/nba`, `/nfl`, `/daily`, a home page, `sitemap.xml`, `404.html`
and a noindex app shell. It gets their data from the backend while it builds.

**State when this was written (2026-11-17): built and verified locally, nothing committed, merged or
deployed.** Everything below is a plan for the deploy, plus what was measured on a laptop. See
`specs/074-redraft-seo/verification.md`.

### Deploy order

1. **Backend first.** It carries migration **V50** (`chosen_season` on `historical_game`; guest ids are stored
   in the same owner column as `guest:<uuid>`) and the new routes under `/api/history/public/**`. The backend
   must also allow the `X-Guest-Id` header in CORS (already in the code).
2. **Check the backend**: `curl https://api.ballknowers.co/api/history/public/seasons?sport=NBA` answers
   200 with `"schema":1` and a season list.
3. **Then the web service.** Its build now needs the backend **up and already carrying
   `/api/history/public`**. If the backend is down or still the old build, the web build fails loudly
   and names the URL and the reason (a 404 means an old backend; a wrong `schema` says so).
   **A failed web build is safe to re-run, and the previous web deploy keeps serving** while it fails.
   Merging to `main` starts both services at once, so the first time expect the web build to fail once
   if it starts before the backend is ready: re-run it when step 2 passes.

### The build-time fetch

- It reads `VITE_API_BASE` (already set on the web service), asks for one season at a time, and gives each
  request a **3-minute retry budget**. It checks the response's `schema` number, validates every field it
  uses (a season label or slug that is not a year such as `2016-17` is refused), and fails the whole build
  with the URL and reason if anything is off.
- A sport with no seasons fails the build **unless** `REDRAFT_ALLOW_EMPTY_SPORT=1` is set. Production
  should have both sports, so a failure there means a missing import, and you want to see it.
- **`REDRAFT_SEASON_DATA` (a fixture file) and `REDRAFT_ALLOW_EMPTY_SPORT=1` are for local and test builds
  only. Do not set either on Railway.** The first would publish fixture data as real pages; the second
  would let a half-imported site go live without football.
- **Cost measured on the dev machine** (`specs/074-redraft-seo/t017-measurements.md`, `verification.md`):
  47 NBA seasons fetched one after another, cold, took **about 39 s** (median 853 ms, slowest 1.1 s).
  The JVM there ran with only the C1 compiler and the default heap, **not production's `-Xmx1024m`**, and
  against a local database. Football was **not measured** (no NFL seasons locally). Production's 56 NBA seasons
  and 26 NFL seasons are therefore an extrapolation, not a number. How much heap the season summaries take
  alongside real traffic under 1 GB is **unmeasured**; locally 47 cached summaries held under about 2 MB after a GC.

### Redeploy the web service after the data changes

The pages are files, built once. **After any historical import, or any config change that alters which
seasons are offered** (`first-offered` / `last-offered`), redeploy the web service, or the static pages,
the sitemap and the sport indexes go stale. The backend drops its own cached summaries when an import
finishes; the files do not follow by themselves.

### How the web container serves it now

- The Dockerfile runs `serve dist --config ../serve.site.json` with **no `-s`**. The build generates
  `serve.site.json` beside `dist/` (git-ignored, never published) for whichever `VITE_SITE` it built, and it
  carries `web/serve.json`'s security headers. The leagues and combined sites get one `**` rewrite to
  `index.html`, which behaves like `-s` did (measured identical on 15 of 15 paths, t027-serve.md).
- On the Redraft site (measured on a fixture build with the real command, `t027-serve.md`, and again on
  the real local data, `verification.md`): a real page answers **200**; `/nba/`, `/nba/2016-17/` and the
  `.html` forms answer **301** to the slashless address; an unknown path, an unoffered season, a bare year
  and `/assets` answer a real **404** with the "Page not found" page; the old app addresses (`/game/<id>`,
  `/history/**`, the league prefixes) answer 200 with an **app shell that carries a noindex meta**.
- **Why a separate shell file:** `serve`'s `headers` rules match the *rewritten* file, not the URL asked for,
  so an `X-Robots-Tag` on `/game/**` never appears. The noindex is a meta tag in `app-shell.html` and
  `404.html` instead (lessons #56). `/404` and `/app-shell` answer 200 at their own addresses; both are noindex
  and neither is linked.
- The 404 carries the security headers; the 301 responses carry none (serve sends headers only with a file).
  The CSP stays report-only.
- **Not measured:** Railway's edge in front of `serve`, and any response from production.

### After deploy: check this

1. `curl -sI https://www.ballknowers.co/nba/2016-17` is 200, and the body has its own `<title>`, a
   canonical equal to that address, and the season's numbers without running scripts.
2. `curl -sI https://www.ballknowers.co/nba/not-a-season` (and `/xyz`) is **404**, and the body has the noindex meta.
3. `https://www.ballknowers.co/sitemap.xml`: the number of `<loc>` lines equals the offered seasons of both
   sports **plus 4** (`/`, `/daily`, `/nba`, `/nfl`). Locally, with NBA only, 47 seasons plus 3 gave 50. It
   must hold no `/game/` address.
4. `https://www.ballknowers.co/game/1` answers 200 and its HTML has `<meta name="robots" content="noindex">`.
5. `curl https://api.ballknowers.co/api/history/public/seasons?sport=NFL` lists football seasons, and `/nfl`
   on the site links them. (This is the one thing nobody has run: there was no football data locally.)
6. **The league site is unchanged and still locked**: `leagues.ballknowers.co` shows the token prompt, and a
   league API route without a session still answers 401 (`LEAGUE_LOCK` on). The new public routes must
   answer without a session: they live under `/api/history/public/**`, which the lock already lets through.
7. One real play-through as a guest: today's game and one chosen season, then a second chosen season is
   refused with the sign-in panel.

### Owed manual steps (Allan)

- **Make `ballknowers.co` redirect to `www` with a permanent 301** at the registrar. It answered **302** when
  measured on 2026-11-17.
- **Verify the site in Google Search Console and in Bing Webmaster Tools, and submit `sitemap.xml`.** Nothing
  in the repo can do this.
- **After two to four weeks**, check how many pages Search Console says are indexed, and the visits to the
  season-page route as a whole. The analytics record route templates (such as `/nba/:season`), not individual
  seasons, so per-season visits cannot be read from them.
- No ranking and no traffic is expected or promised. Whether search engines pick the pages up, and how
  they rank them, is not known.

## Analytics (spec 052)

Cookie-free usage counts through Umami Cloud's free plan. Front end only; nothing in the
backend or the database. The numbers are read on Umami's own dashboard, behind your login.

- **Turn it on:** create a website in Umami Cloud for `www.ballknowers.co`, copy its website
  id, and set `VITE_UMAMI_WEBSITE_ID` as a **build** variable on the frontend service (then
  rebuild; Vite inlines it). Without it nothing loads.
- **It only runs on `www.ballknowers.co` / `ballknowers.co`.** `localhost`, previews and any
  other host never load the tracker, even with the variable set. For payload checks in dev,
  set `VITE_ANALYTICS_DEBUG=1` and the would-be payloads are logged with `console.debug`
  instead of being sent.
- **Exclude your own browser:** `localStorage.setItem('umami.disabled','1')` in DevTools on
  the site. Both this app and Umami's script honour it.
- **The numbers are a floor.** Visitors with a blocker or tracking protection are not counted.
  And they start on the deploy day: there is no backfill (FR-010).
- **The league id changes at season rollover.** Sleeper issues a new league id each season,
  so a league's `league-view` events split at the rollover; the sport does not.
- **"Page views minus league views" is only a rough "no league" count (review #3).** A
  `league-view` is sent only when the rail resolves a league for the path, so some league-route
  page views send none: a league the signed-in user is not a member of (`/api/drafts` is scoped
  to the user), the first half of an auction-draft redirect, and a draft id missing from its
  lineage. Treat the difference as an upper bound; the cleaner "no league" read is by route
  template (anything outside `/leagues/`, `/drafts/` and `/mock/:session`, plus `/(landing)/*`).
- **`league-added` with `via: id` also counts a re-paste (review #7).** The ingest is
  idempotent, so pasting the id of a league that is already added succeeds and is counted as a
  league added. The one-click path and ESPN do not have this.
- **An auction-draft redirect counts two page views** (the board route, then the redirect
  target).
- **What is sent** is exactly `specs/052-site-analytics/data-model.md`: a route template
  (e.g. `/leagues/:league/power`, never the real URL), the sport and league id on a league
  view, an outside referrer's origin on the first view, and three key actions (`sign-in`,
  `league-added`, `mock-started`). Never a username, display name, draft, player, manager or
  mock-session id. Signed-out visits are filed under `/(landing)/...`.
- The sign-in screen says all of this in one sentence (`analyticsLine` in
  `web/src/landingCopy.ts`).

## Things that will probably bite

**Memory.** *(Amended 2026-10-20, spec 041. The old paragraph guessed that "512MB is comfortable,
256MB may OOM" for the player ingest; measurement replaced the guess.)*
- **Production** (Railway, 2 GB / 2 vCPU, minute-resolution metrics): it rests at ~300–420 MB and
  peaked at 827 MB on draft night 2026-10-10. Spikes near 1.1–1.3 GB at deploy time are the old and
  new containers overlapping, not load. Read metrics at minute resolution (lessons #49).
- **The JVM flags live in the Dockerfile `ENTRYPOINT`, not in `JAVA_OPTS`:** `-XX:+UseG1GC -Xmx1024m
  -XX:G1PeriodicGCInterval=60000`. `JAVA_OPTS` is empty by default and is for *extra* flags. A
  Railway `JAVA_OPTS` variable adds to these, and for a repeated flag the last one wins, so it can
  override one but can't drop them all. The backend logs `JVM settings: maxHeapMb=…` at startup;
  check that line after any change.
- **Why these values** (measured locally in a 2 GB / 2 CPU container, `specs/041-memory-footprint/`):
  the old `MaxRAMPercentage=75` (1.5 GB heap) held ~760 MB for 10+ minutes after a load spike, with
  ~56 MB live. With the periodic GC it falls to ~414 MB by +10 min. `-Xmx1024m` survived the worst
  case (2 concurrent 5,000-iteration sims plus both player ingests, peak ~956 MB container) with no OOM.
- **The player ingest** allocates ~90–155 MB (mostly garbage) and retains a few MB. 256 MB was not tested.
- **If the Railway memory limit is ever lowered below ~1.5 GB, lower `-Xmx` with it.** Heap 1024 MB
  plus ~250–380 MB of non-heap needs headroom under the limit, or the container is OOM-killed rather
  than the JVM throwing.

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

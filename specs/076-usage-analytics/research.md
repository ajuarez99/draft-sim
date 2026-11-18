# Research: Usage analytics we own (spec 076)

Read from the code on `origin/main` d6a2ac4, 2026-11-17. "Read" means read, not run, unless it says measured.

## R1. Started / finished / game of the day come from the game table, not from new events

**Decision**: the Redraft funnel's started, finished, abandoned, still-drafting and game-of-the-day counts are
a query over `historical_game`. No client event feeds them into our store.

**What is there** (read):
- `historical_game` (V43) has `created_at`, `completed_at`, `status` (`DRAFTING` / `COMPLETE` / `ABANDONED`),
  `sport`; V49 added `daily_date` (non-null = game of the day); V50 added `chosen_season` and made
  `sleeper_user_id` hold either a Sleeper id or `guest:<uuid>`.
- No code deletes a game row (`grep "delete from historical_game"` over `backend/src/main/java`: no hit).
  Abandoning sets a status.

**Why**: the spec assumed these had to be counted as events from the browser, starting at deploy
(spec Assumptions, FR-003, FR-016). The rows already hold them, exactly: not a floor, not blockable, not
fakeable through a public address, counted once per game by construction, and going back to spec 053's
launch. And because started and finished are the same rows, a real completion rate per start-day cohort
exists, which FR-016 said was impossible. The spec is amended (see its "Amended at plan time" notes).

**One gap**: guest vs signed-in at the time of play. Signing in moves a guest's games to the Sleeper id
(`/api/history/claim`), after which the row no longer says it began as a guest. Fix: a new column
`started_as_guest boolean not null`, set at insert, backfilled from `sleeper_user_id like 'guest:%'`.
Guest play shipped with spec 074 on 2026-11-17, so the backfill is wrong only for games a guest started and
then claimed between 074's deploy and this one. The page says so.

**Alternatives**: client `game-started` / `draft-finished` events into our store (blockable, fakeable,
no history, cannot link a finish to its start); deriving guest from the current owner key with no column
(drifts every time someone signs in).

## R2. What the browser still has to send

Page views, league views, the three older key actions, and one new action, `result-shared` (a share is a
browser act the server never sees). These go to a new public address and are stored as daily counters.

The hosted service (Umami), if ever switched on, additionally gets `game-started`, `draft-finished` and
`result-shared` as named events through the existing `trackAction` path (FR-006). Those two game events are
not sent to our own address: R1 already has them, and a second count of the same thing under the same
label is the bug class this repo keeps relearning.

## R3. Daily counters, not a row per view

**Decision**: one table, `usage_daily`, keyed by (day, site, kind, route, name, props, league); a send is an
upsert that adds 1. Rows grow with the vocabulary, not with traffic (FR-019, SC-007).

**Alternatives**: a row per view with a timestamp (grows with traffic, invites per-visitor questions the
spec rules out); Redis/in-memory counters (lost on restart).

## R4. The vocabulary lives in the web app; the server holds a checked copy

Routes are defined once, in `web/src/analytics.ts` (`routesFor(site)`), already pinned to `App.tsx` by
`analytics.test.ts`. The server needs the same list to refuse junk (FR-011). **Decision**: a JSON resource,
`backend/src/main/resources/usage-vocabulary.json`, and a vitest test that fails when it differs from what
`analytics.ts` exports. One source, one checked copy, a test that names the drift.

Known cost: the two services deploy separately. A route added in the web app and deployed before the
backend is refused until the backend ships. Refused sends are counted (kind `rejected`, one row per day and
site) and shown on the owner page, so drift is visible instead of silent.

## R5. Abuse limits (hand-set, labelled as such)

- **Vocabulary check** first: unknown site, route, event name, property key or value → refused.
- **League id** must exist in the `league` table; the stored sport is the table's, not the sender's.
- **Burst limit**: 60 accepted sends per minute per source address, held in memory only, in a map capped at
  10,000 addresses, entries dropped after a minute. **60 and 10,000 are guesses, not measured.** The address
  is read from the request (first `X-Forwarded-For` hop behind Railway's proxy, else the socket address),
  used as a map key for at most a minute, never stored, never logged. The spec's "never written down" is
  tightened to that.
- **Crawlers**: a `User-Agent` matching a short list of bot words is answered 204 and not counted. The list
  is hand-set and will miss some.
- Every outcome answers **204 with no body** — accepted, refused, limited or crawler — so the address tells a
  prober nothing and the browser has nothing to handle.

What this does not stop: a script sending valid vocabulary slowly from many addresses. The owner page says
view and share counts are unauthenticated.

## R6. The send itself

`fetch(apiUrl('/api/usage'), { method: 'POST', keepalive: true, ... })`, **not** through `apiFetch`:
`apiFetch` attaches `X-Sleeper-User`, the guest id and the league session, and a usage send must carry no
identity (FR-007, SC-004). It carries `Content-Type: application/json` and the build's bearer token if one
is set (`ApiTokenFilter` guards `/api/*`). `sendBeacon` was rejected: it cannot set the bearer header.
Fire-and-forget; every failure is swallowed (FR-009).

On when: production hosts (`OWN_HOSTS`) unless the owner opt-out (`localStorage['umami.disabled']`) is
set, independent of the Umami website id (FR-006, FR-010). Locally only with a new dev-only switch
`VITE_USAGE_COLLECT=1`, which is what live verification uses.

## R7. Public route plumbing (read)

- `LeagueLockInterceptor.PUBLIC_PATHS` must gain `/api/usage`: a locked league site still counts its
  landing view. `LeagueLockMappingsTest` pins the public list and must be updated with it.
- CORS already allows `POST` and `Content-Type` on `/api/**` (`WebConfig.addCorsMappings`).
- The front end's CSP already lists `https://api.ballknowers.co` in `connect-src` (`web/serve.json`). No
  change.
- `/api/admin/**` is already behind `AdminGateInterceptor`; `/api/admin/usage` needs no new gate.

## R8. A "day"

`America/New_York`, the zone the game of the day already rolls over in (`HistoricalDailyService.ZONE`).
Both the counters' `day` and the game rows' start day use it. Named on the page (FR-015).

## R9. Migration number

Highest on main is V50. This spec takes **V51**. Spec 075 (email accounts) is not on any branch yet; if it
lands first with V51, this renumbers before merge (stop-and-ask item).

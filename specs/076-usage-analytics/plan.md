# Implementation Plan: Usage analytics we own

**Branch**: `076-usage-analytics` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/076-usage-analytics/spec.md`

## Summary

Count page views, league views, key actions and result shares in our own Postgres as daily counters, fed
by a public no-identity `POST /api/usage` from the existing `web/src/analytics.ts`; read the Redraft
started/finished funnel straight from the existing `historical_game` rows (research R1), with one new
column so guest-vs-signed-in survives a later sign-in; show all of it on an unlisted owner page,
`/admin/usage`, behind the existing admin token. Umami stays optional and gains three Redraft events.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, JdbcTemplate, virtual threads); TypeScript + React + Vite

**Primary Dependencies**: none new

**Storage**: Postgres; Flyway `V51__usage_analytics.sql` (new table `usage_daily`, new column
`historical_game.started_as_guest`)

**Testing**: JUnit (unit + Postgres-backed ITs, MockMvc), vitest + Testing Library; live verification on
bootRun + vite against the local 5433 cluster

**Target Platform**: Railway, two web services (www, leagues) and one API

**Project Type**: web application (backend + web)

**Performance Goals**: a send is one upsert; the report is five grouped queries over a table that grows
with vocabulary × days, plus one over `historical_game`. No target measured; none needed at this size.

**Constraints**: no identity on the wire or at rest; a send can never affect the page; 204 for everything

**Scale/Scope**: unknown traffic (that is the point). Rows per day ≤ routes × sites + leagues × routes +
event combinations: a few hundred at most.

## Constitution Check

| Rule | How this plan meets it |
|---|---|
| Migrations append-only | New `V51`, nothing earlier touched. Number collision with spec 075 is a stop-and-ask (R9). |
| `api.ts` mirrors Java records | `UsageReport` and its row records get their TS types in the same task as the records. |
| No retuning hand-set constants to match a guess | The burst limit (60/min), address cap (10,000) and crawler list are new, hand-set, and labelled as guesses in code and on no user-facing surface as measured. |
| `Map.of` with nulls | `leagueName` is nullable; the report is records, the 400 body a mutable map. |
| Ask before committing | No commit without Allan. |
| Planning docs are not specs | Spec's own assumption (events from the browser, no history) was checked against the schema and found wrong; amended (R1). |

No violations to justify.

## Design

### Backend (`backend/src/main/java/com/ballknowers/draftsim/`)

- `usage/UsageVocabulary` — loads `usage-vocabulary.json` (sites → routes; event names → allowed props and
  values). Pure validation: `Optional<UsageKey> validate(body)`; canonicalises props (sorted `k=v;k=v`).
- `usage/UsageRepository` — `increment(day, key)` (the upsert), `incrementRejected(day, site)`, the grouped
  reads, `leagueSport(leagueId)`, and the funnel query over `historical_game`
  (`(created_at at time zone 'America/New_York')::date`).
- `usage/UsageLimiter` — in-memory per-address fixed window (60/min), map capped at 10,000, swept on access.
  Takes a `Clock`.
- `usage/UsageService` — the check order in contracts/usage-api.md; `report(from, to)`.
- `api/UsageController` — `POST /api/usage`, always 204; reads raw body with a 1 KB cap; catches everything.
- `api/AdminUsageController` — `GET /api/admin/usage`.
- `api/LeagueLockInterceptor.PUBLIC_PATHS` += `/api/usage` (+ `LeagueLockMappingsTest`).
- `historical/HistoricalGameRepository` insert sets `started_as_guest` from the owner key
  (`startsWith("guest:")` — reuse whatever helper spec 074 already has for that test, do not write a second).

### Web (`web/src/`)

- `analytics.ts` — split `enabled()` into `umamiEnabled()` and `ownEnabled()`; every existing track function
  builds its record once and hands it to both sinks. New `sendOwn(body)` (bare `fetch`, keepalive, no
  identity headers, swallow everything). `KeyAction` gains `game-started`, `draft-finished`,
  `result-shared`; a small table says which sinks each action goes to (the two game events: Umami only).
  The dedupe latches (`lastPageKey`, `lastLeagueKey`) stay shared so both sinks see the same events.
  `LEAGUE_ROUTES` gains `/admin/usage`.
- `usageVocabulary.test.ts` — asserts `backend/src/main/resources/usage-vocabulary.json` equals what
  `analytics.ts` exports (routes per site, events and props).
- Call sites: `HistoricalResults.tsx` ShareButton (after `navigator.share` resolves / clipboard succeeds);
  the game-start call sites (`startHistoricalGame`, `startHistoricalDaily`, `startChosenSeasonGame`
  callers) and `HistoricalRoom.tsx`'s pick/auto handlers for the Umami-only events.
- `api.ts` — `UsageReport` types + `getAdminUsage(token, from, to)`.
- `adminToken.ts` — the `sessionStorage` token helpers lifted out of `AdminDigest.tsx` so both admin pages
  share one definition.
- `pages/AdminUsage.tsx` + route `/admin/usage` in `App.tsx` beside `/admin/digest` (league and combined
  trees; the Redraft tree already forwards `/admin/*`). Layout, top to bottom:
  1. A plain-words caveat block (FR-015): counting since, zone, floor, unauthenticated, views are not
     people; and for the funnel: exact, from saved games since `gamesSince`, includes your own games, the
     guest split's backfill gap.
  2. Range picker (7 / 30 / 90 days, custom).
  3. Views per day, one small-multiple bar series per site, zero-filled inside the counted period, days
     before `countingSince` drawn as "not counted".
  4. Views per route, a table per site with the route's label and template.
  5. Redraft funnel: per sport totals for the range (started → finished with the cohort rate, abandoned,
     still drafting), guest and game-of-the-day splits, then the per-day table. Shares beside it, labelled
     as a browser-reported floor and not part of the cohort.
  6. Key actions table. 7. League views table. 8. Rejected sends count, with what it means.
  Empty store → words, not zero tables (FR-017); the funnel section still renders, since games exist.
- Privacy line (FR-018): update spec 052's visitor-facing line and the Redraft site's equivalent to say the
  site counts page views itself, with no cookie and nothing about the visitor.

### Docs

`DEPLOY.md` Analytics section (deploy order: backend first, so the address exists before the web sends;
`VITE_USAGE_COLLECT` is dev-only), `HANDOFF.md`, and a `claude/lessons.md` entry for R1 (a spec assumed
events were needed where rows already answered it).

## Not being built, and why

- Distinct visitors, sessions, referrers in our store (clarified; referrers stay Umami's).
- Excluding the owner's own games from the funnel: the server has no opt-out signal and the rows are real
  games. Labelled instead.
- A generic event API. The vocabulary is closed on purpose.
- Charts library. The per-day series is CSS bars.

## Acceptance for the verification pass

SC-001…SC-009 from the spec as amended, driven on a real bootRun + vite with `VITE_USAGE_COLLECT=1`:
scripted views on both site builds, a guest game started/finished/abandoned/shared, 1,000 junk sends, a
burst, the page without a token, the collection address unreachable, and a network capture checked for
identity headers.

## Project Structure

```text
specs/076-usage-analytics/   spec.md plan.md research.md data-model.md quickstart.md contracts/usage-api.md
backend/src/main/resources/db/migration/V51__usage_analytics.sql
backend/src/main/resources/usage-vocabulary.json
backend/src/main/java/com/ballknowers/draftsim/usage/   (new package)
backend/src/main/java/com/ballknowers/draftsim/api/UsageController.java, AdminUsageController.java
web/src/analytics.ts, adminToken.ts, api.ts, App.tsx, pages/AdminUsage.tsx, components/HistoricalResults.tsx, pages/HistoricalRoom.tsx
```

## Complexity Tracking

None.

## Amended after review 2026-11-17

[plan-review.md](plan-review.md) read the plan cold against the code: 0 critical, 3 high, 6 medium, 5 low.
Research R1 held under attack (no deletes, one insert site, `created_at` is the start, funnel SQL ran, ET
bucketing right on both DST edges, V51 free everywhere). Every finding is taken. Where this section
disagrees with the text above or with research.md, this section wins; data-model.md and the contract are
corrected in place.

Three were choices about what a number means. They were made here without stopping, and are Allan's to
overturn:

- **F5 → the owner's games are split out.** `draftsim.owner.sleeper-user-id` exists (the plan's "no signal"
  was false). When set, each funnel row also carries `ownerStarted` / `ownerFinished` (his signed-in
  games) and the page's headline totals leave them out, saying how many were left out. When blank the page
  says it is not set. His guest-mode games cannot be told apart; the page says that too. Not checked:
  whether production sets the variable.
- **F6 → the two Umami-only events are dropped.** `game-started` and `draft-finished` are not built. The only
  new event is `result-shared`, to both sinks. Spec FR-006 amended. No change to `startHistoricalDaily`.
- **F4 → wording.** Headline is finished vs **not finished** (abandoned + drafting); detail rows are "gave up
  (pressed abandon)" and "left open". The page says finished includes one-press auto-finished drafts and
  that a game left open stays "left open" for ever.

Design fixes:

- **F1 (high) → `started_as_guest` is nullable, no default, never NOT NULL.** Null = not recorded (a row
  written by code that predates the column: an overlapping old container, a rollback, another branch's ITs
  on the shared 5433). Backfill existing rows as planned. The funnel gains `unknownStarted`. An IT creates
  one guest and one signed-in game through the service and asserts the stored value is non-null and right.
  The insert uses `Identity.isGuest`.
- **F2 (high) → the vocabulary is what `routeTemplate()` can return**, not the tables: per site every entry's
  `url` form, `/(not-found)`, and for `leagues` and `combined` also `/(landing)` and `/(landing)<url>`.
  `analytics.ts` exports that closed set (`routeVocabulary(site)`); the JSON is generated from it; a test
  walks every entry × signedIn × site through `routeTemplate` and asserts membership. The wire `route` is
  the `url` form.
- **F3 + F7 (high/medium) → limiter.** Keyed on `X-Real-IP` (what Railway documents), else the socket
  address. Counts **every** send that reaches it, not only accepted ones. At the 10,000-address cap new
  addresses are refused until the window turns. A **global ceiling of 600 database writes a minute** (hand-set
  guess, labelled) bounds the worst case whatever the address source does. `rejected` is counted in memory
  and flushed at most once a minute under that ceiling, so a refused send costs no connection. The page
  says the per-address limit is best effort. Whether `X-Real-IP` is what production delivers is **owed to
  the post-deploy check**; it cannot be verified locally. Limits are asserted in a unit test with a `Clock`;
  the quickstart's burst step is replaced by that test plus one manual burst read as "at most 60 in a
  window".
- **F8 → the guard.** The vitest test fails (not skips) when the JSON is missing, loads `node:fs`
  dynamically (no `@types/node`), path `../backend/src/main/resources/usage-vocabulary.json`. A JUnit test
  loads the real resource. `UsageVocabulary` fails soft: on a bad resource it logs once and refuses
  everything, the API still starts. DEPLOY.md says the guard is local and adds "run vitest" for any route
  change.
- **F9 → the two-sink split, decided.**
  1. The three early guards become "neither sink is on".
  2. Under `VITE_ANALYTICS_DEBUG` the own sink logs its body to the console like the Umami one and sends
     nothing.
  3. `VITE_USAGE_COLLECT=1` is the switch that really sends from a non-production host, honoured only when
     `import.meta.env.DEV`. Spec FR-010 amended to name it.
  4. `analytics.test.ts` and `AppShell.analytics.real.test.tsx` stub `fetch`; new cases: own sink on with no
     website id; both on; opt-out stops both; localhost sends nothing without the dev switch; own body
     holds only vocabulary fields.
  5. The own body is built by its own function from (site, route | name, props, league). Never derived from
     `basePayload`.
  6. The module header comment is rewritten to describe both sinks.
- **F10** → contract wording: every request that reaches the handler answers 204.
- **F11** → sport is lowercase `nba` / `nfl` everywhere in `UsageReport`, converted in `UsageService`. Funnel
  fields are the contract's list plus `unknownStarted`, `ownerStarted`, `ownerFinished`; data-model.md's
  "same four" sentence is replaced.
- **F12** → the send passes `referrerPolicy: 'no-referrer'`.
- **F13** → usage ITs run on a fixed far-past `Clock` day and delete their rows. `countingSince` reads
  `view`, `league-view` and `event` rows only. Verification reports the funnel as before/after differences.
- **F14** → the test is `LeagueLockMappingsIT` (`EXPECTED_PUBLIC`); the public entry is needed because the
  send carries no session header (a locked league site sends no view at all). Files missing from the
  structure list: `HistoricalGameRepository.java`, `LeagueLockInterceptor.java`, `landingCopy.ts`,
  `redraftCopy.ts` (the two privacy lines), the two analytics test files. (`HistoricalDraft.tsx`,
  `SeasonPage.tsx`, `DailyCards.tsx`, `HistoricalRoom.tsx` drop out with F6.) The caveat block says the
  opt-out is per browser and per site and never applies to the funnel. A stored route with no label left
  shows its template. Local 5433 is at V49, so starting this backend applies V50 and V51 to the shared
  database; with F1 fixed that is safe for other branches.

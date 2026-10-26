# Implementation Plan: Compare two players

**Branch**: `049-player-compare` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/049-player-compare/spec.md`. Worktree
`.claude/worktrees/048-player-compare`, cut from `origin/main` `ec72031` (spec 047 merged).

## Summary

A new basketball page, `/leagues/:sleeperLeagueId/compare?a=<id>&b=<id>`, that renders two players' existing
player-page figures in two columns and marks the better value per row. The page **composes two calls to the
existing player endpoint** (`GET /api/leagues/{id}/players/{pid}`) rather than adding a comparison endpoint, so
FR-002 ("every figure equals the player page") holds by construction: it is the same payload. The marking rule
is a pure client function with a preference-ordering test (`AGENTS.md` bug class #1). The player chooser
searches the existing season stat leaderboard's rows. The only backend change is one additive, nullable field on
the player page record, `thisWeek` (games played and left in the current scoring week), because the client has
no single source for "which week is today" (R3). No migration.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5) backend; TypeScript + React (Vite) frontend

**Primary Dependencies**: existing only: `PlayerStatsService`, `ScheduleGridService`, `NextGames`,
`OwnershipMark`, `apiFetch`

**Storage**: Postgres, read-only for this feature. No migration (highest is V40; nothing added)

**Testing**: JUnit 5 + the existing MVC/IT suites (backend); vitest + Testing Library (web)

**Target Platform**: the deployed site (Railway) and local dev; desktop and 375 px phones

**Project Type**: web application (backend + web)

**Performance Goals**: SC-007, under 2 s for a warm pair on the deployed site. Two player-page calls in parallel
plus one leaderboard call for the chooser (loaded lazily, only when the chooser opens). Single-call latency and
leaderboard payload size are **not measured yet**; T001 measures them before the chooser design is final (R4).
*(Partly wrong: spec 023 measured the leaderboard at 1.46 MB raw / 327 KB gzipped. See A9.)*

**Constraints**: basketball only (the player endpoint answers `NOT_BASKETBALL` for football); `api.ts` must
mirror the new Java field in the same change; no verdict text anywhere (FR-006)

**Scale/Scope**: one page, one chooser component, one marking module, one additive backend field

## Constitution Check

*GATE: before Phase 0 research; re-checked after Phase 1 design.*

| Rule | How this plan meets it |
|---|---|
| Migrations append-only | No migration. |
| `api.ts` mirrors Java records | `PlayerStatsPage.thisWeek` and its record `ThisWeek` are added to `PlayerStatsService` and to `api.ts` in the same task (T005). Optional (`thisWeek?:`) on the TS side for deploy skew, as 046/047 did. |
| No retuning hand-set constants | The small-sample rule is the existing `small-sample-minutes: 100` (`config/weights.yml:231`), used as-is. The window-full rule (R2) is a definition, not a tuned number. |
| `Map.of` null trap | `ThisWeek` is a record; no `Map.of` on a nullable value is added. |
| Ask before committing | Commit, push, PR stay Allan's call. |
| Roadmap scope is not a spec | Roadmap 3.3 says "M, thin views over 3.1". Read the code: it needs none of 3.1 (projections are excluded by FR-006), so it is smaller than M (Research R1). |

Post-design re-check: unchanged, no violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/049-player-compare/
├── spec.md
├── plan.md            # this file
├── research.md        # R1–R6
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── api.md         # the one additive field on the player page response
│   └── ui.md          # route, query params, marking rule, copy rules
├── checklists/requirements.md
└── tasks.md           # /speckit-tasks
```

### Source code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/engine/
└── PlayerStatsService.java        # + ThisWeek record, + thisWeek field, computed beside header()
backend/src/test/java/com/ballknowers/draftsim/engine/
└── PlayerStatsThisWeekTest.java   # new: week boundaries, today's game, no schedule, fallback season

web/src/
├── api.ts                         # + ThisWeek type, + PlayerStatsPage.thisWeek?
├── compare.ts                     # new: pure row model + markRow() (direction, equal, small-sample, window-full)
├── compare.test.ts                # new: preference-ordering tests (higher wins, TOV lower wins, ties, guards)
├── pages/PlayerCompare.tsx        # new: two columns, season line, this-week line, no-verdict copy
├── pages/PlayerCompare.test.tsx   # new: figures equal fixture page, verdict-text scan (SC-006), one side failing
├── components/PlayerChooser.tsx   # new: name search over the season leaderboard rows
├── components/PlayerChooser.test.tsx
├── pages/PlayerPage.tsx           # + "Compare" link in the header (basketball only)
└── App.tsx                        # + route /leagues/:sleeperLeagueId/compare, gate="stats"
```

**Structure Decision**: existing backend + web layout. The comparison page is a sibling of `PlayerPage` and
reuses its components where they are already split out (`OwnershipMark`, `PlayerFace`, `PageHeader`); figures
are formatted with the same helpers `PlayerPage.tsx` uses, so rounding matches (FR-002).

## Design decisions (detail in research.md)

1. **Compose, don't add an endpoint (R1).** Two parallel `getPlayerStats` calls. Season consistency (FR-007)
   follows: the data season is resolved per *league* (`PlayerStatsService.java:305-322`, `Rule.STORED_GAMES`,
   then `dataSeasonBefore`), never per player, so two calls for one league answer the same season. The page
   still asserts it and refuses to mark any row if the two `season` values differ.
2. **Marking (R2).** A row is marked only when both values are present, unequal, and neither window is
   `smallSample`; and, for `LAST_10` / `LAST_5`, only when **both players have the full number of games in that
   window**. Rationale: a player with 3 games has `LAST_10` = his 3-game average, not a 10-game one; comparing it to
   another player's 10-game average isn't like-for-like. Direction is higher-better except `tov` and `tovPct`.
   Minutes per game is marked higher-better (a role signal). Unmarked by design: team, positions, owner, `pf`,
   games this week.
   > **Amends spec FR-005** (planning, 2026-10-25): the spec said "small sample by the player page's existing
   > rule". Read in code, that rule is *minutes* only (`smallSample` = window minutes < 100,
   > `AdvancedStats.java:258`, `weights.yml:231`), so a 3-game player with 105 minutes would pass it. The
   > window-full condition is added; US2's independent test ("fewer games than a window's length") already
   > assumed it. Spec FR-005 updated in place with this note.
3. **This week (R3).** New nullable `thisWeek` on the player page: `{ week, firstDate, lastDate, played,
   remaining }`. `week` is the `ScheduleGridService` week whose `[firstDate, lastDate]` contains
   `NextGames.today(clock)`; `remaining` is the player's *team's* scheduled games in that week dated today or
   later that are not already in his box scores; `played` is his box-score games in that range. Null when there
   is no schedule, no current week (All-Star break, offseason), no team, the league is complete, or the page is
   on a fallback data season (the box scores are another season's, so `played` would be wrong). The page shows it
   unmarked, with the "one game per starter per week" note (FR-008).
4. **Chooser (R4).** Searches `getStatLeaderboard(leagueId, 'SEASON').rows` by name (case- and
   accent-insensitive), showing team and `OwnershipMark`. Loaded only when the chooser opens. It lists every
   player **with a game this season**, which is narrower than FR-011's "stored player record"; a player with no
   games (injured all season) can still be compared by address. Amends FR-011, noted in the spec. If T001 finds the
   payload too large (> ~1 MB) or slow (> 1 s warm), the fallback is a slim search endpoint, decided then, not now.
5. **Address (R5).** `?a=&b=` query params on `/leagues/:id/compare`. Swapping a player replaces one param
   (`replace: true` history). `a == b` shows one column and the chooser. Football leagues render the "basketball
   only" line from the first response's `reason === 'NOT_BASKETBALL'`, with no extra call.
6. **No-verdict copy (R6).** No header or footer summary; no win count. A test renders a full comparison and
   scans its text for `/\b(start|sit|better pick|recommend|winner|wins \d)/i` (SC-006).

## Amended after review (2026-10-26)

`plan-review.md` (F1–F15) read this plan cold against the code. **These amendments win over the design
decisions above where they disagree.** F4, F5 and F6 changed which rows are marked and went to Allan, who took
the recommended option on each.

| # | Finding | What changed |
|---|---|---|
| A1 | F1 HIGH | The SC-006 scan is whole-word, with positive controls (contracts/ui.md). The old `\bstart` matched "starter". |
| A2 | F2 HIGH | The games-this-week note was wrong: under lock-ins (spec 044), more games means more chances at a good game. The note is rewritten, "No game this week" is said plainly, and the spec Context is corrected visibly. |
| A3 | F3 HIGH | Decision 3's grid date-range rule is **dropped**. The week is `LockInService.liveWeek(schedule, NextGames.today(clock), leg)`, made package-visible and reused. It is the first counted game on or after today, falling back to the league's `leg`. `played` = box lines with `week == W`, and `remaining` = `NextGames.forTeam(...)` with `week == W` and `gameId` not already played. That is lock-ins' own counting, with no date join. `firstDate`/`lastDate` are removed from the contract. |
| A4 | F4 HIGH, Allan | FG%/3P%/FT% are **unmarked**, with made-attempted under each (the player page's `RateValue` form). |
| A5 | F5, Allan | TOV per game is **unmarked**. Turnover rate (`advanced.tovPct`) is added as a row, marked lower-better, and withheld on its own `Rate.reason`. |
| A6 | F6, Allan | Games (every window) and the season total are **unmarked**. The total is labelled "box-score total, not the league's credited points". |
| A7 | F7 | The client window-full rule is **dropped**. The player page response gains `windowQualification` (additive, optional in TS): per window, `{ qualified, reason, lastN }`, from the `Computed.qualification` the server already has. `markRow` withholds a LAST_N row when `games < lastN` or `reason == NOT_QUALIFIED_STALE`. The minutes-per-game floor and SEASON `qualified` are ranking rules and are **not** mark guards (stated design choice). The cell names the reason: "3 of 10 games", or "last game N days before the newest". |
| A8 | F8 | A `compare` row is added to `destinations.ts` (`sports: ['nba']`, `idKind: 'league'`, `inRail: false`). Football is decided from that row through `comparePagesFor(sport)`, applied to the first response's `sport` *(amended at analyze, I1: "before any request" wasn't buildable)*; the Compare link on the player page reads the same row. A season switch from the rail **drops `?a=&b=`, accepted**: another season's league is a different comparison. |
| A9 | F9 | R4's size is quoted from spec 023: **1.46 MB raw, 327 KB gzipped** for a full-season SEASON board. The chooser reuses the existing module-level cache (`draftStatsCache.fetchDraftStats` or its equivalent for this endpoint) and the existing accent fold (`targets.ts` `fold`). The switch-to-slim-endpoint threshold becomes **> 1 s warm on a full season (NBA 2025 locally)**. The gzipped size is recorded, not used as a gate. |
| A10 | F10 | The formatters (`fixed`, `num`, `minutes`, the `RateValue` form) move from `PlayerPage.tsx` to a shared `web/src/statFormat.ts`, used by both pages. `markRow` compares the **displayed strings**, and a test checks that 12.04 vs 12.03 is unmarked. |
| A11 | F11 | The chooser skips rows with `name == null` (they can't be found by name). On a fallback season it shows the leaderboard's fallback note. With neither `a` nor `b` set, football is decided by A8, not by a response. |
| A12 | F12 | `thisWeek` is computed from the **URL** league (its season's schedule and box lines) under `header()`'s rule. The fallback-null case is dropped. A fixture test covers "the URL season has a schedule and no box scores yet". |
| A13 | F13 | The this-week line shows `scheduleAsOf` (already on the response). The contract names the ingest lag. |
| A14 | F14 | If the two responses' `season` **or** `dataAsOf` differ, the page refetches the older column once. If they still differ, it withholds every mark and prints both as-of times. |
| A15 | F15 | Each column's fetch includes `useLeagueDataVersion(leagueId)` in its deps. The 375 px check uses the league's longest real names. SC-007 excludes the chooser. The Compare link goes in the player page's `Header` card, beside the owner, and `PageHeader` is unchanged. |

**Project structure, revised:** add `web/src/statFormat.ts` (moved formatters), `web/src/destinations.ts`
(+ `compare` row), and on the backend `LockInService.liveWeek` (visibility only) and
`PlayerStatsService` (+ `ThisWeek`, + `windowQualification`). Still no migration and no new endpoint.

## Complexity Tracking

None.

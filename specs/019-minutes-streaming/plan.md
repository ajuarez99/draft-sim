# Implementation Plan: NBA minutes trends and streaming candidates

**Branch**: `019-minutes-streaming` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

**Input**: roadmap Phase 2 items 2.3 + 2.4. Allan: "keep it going". **Deadline: in production
before 2026-10-20.**

## Summary

A basketball-only Trends page with three parts:

- **Streaming candidates:** free agents ranked by recent form, with games this week and next
  shown beside them.
- **Minutes risers and fallers:** the median of the last 3 games vs. the season mean, with a true
  usage rate and points per minute.
- **A fix to "rostered":** store the current roster players the refresh already fetches (V28).

Measuring before planning changed three things from the design docs:

- **`TEAM_*` rows** sit in `player_game` (R2). Averaging without excluding them gives 41-minute
  "players". They also make true usage computable, so the design doc's "needs team totals Sleeper
  doesn't give" is corrected.
- **Game count is worth ~+5%, not ~2×** (R3). A starter's week is credited one game, usually his
  best. Streaming ranks by form and drops the "≥ 4 games" filter.
- **"Rostered" is blank between draft and week 1** (R4), which is exactly when streaming matters.
  V28 fixes it from data the refresh already fetches and throws away.

## Amended after review (2026-10-06)

[plan-review.md](plan-review.md) read the plan cold and re-measured against the DB and
Sleeper. R1, R2's join, R3's 2025 table and R4 held. It found 13 problems and 17 notes. Allan
asked for them to be addressed. data-model.md and contracts/api.md are rewritten in place,
with dated notes at the top. The other docs carry inline amendments.

| # | Finding | Disposition |
|---|---|---|
| F1 | The switch to 2026 after the first game empties every list for week 1 | **Fixed.** Per-list cutover: a list reads the new season once half the teams have played its minimum (streaming 3, roles 5), else the previous season, labelled. Measured on 2025: 10-26 and 10-29. Test with 1–2 games per player |
| F2 | No recency: April tanking minutes, long-injured and teamless players lead streaming; benched players never fall | **Fixed.** Rows whose last game is more than `recency-days` (14) before the season's latest game are excluded, and so are players with `player.team` null. Both counts are shown. `missedTeamGames` from `player_absence` (`TEAM_PLAYED_NO_ENTRY`) is shown, not ranked, because it can't tell benching from injury. Test with a 60-day-old last game |
| F3 | Completed seasons skip standings, so "Rosters haven't loaded" would show forever | **Fixed: option (b).** `SEASON_COMPLETE` streaming reason. A finished season's rosters aren't a waiver wire, and no refresh is promised. Quickstart V3 corrected |
| F4 | `rostered` boolean when unknown | **Fixed.** `Boolean`, null when `streamingReason` is set. Role lists aren't split then. Invariant 3 |
| F5 | `NOT_DRAFTED` inferred from an empty map (keepers, mid-draft) | **Fixed.** From `league.status` `pre_draft`/`drafting`. Test with a non-empty map + `pre_draft` |
| F6 | +5% compares games played, but the column shows games scheduled | **Fixed.** The note quotes within-player, scheduled-games 2025: **+4.4%** for 4 vs 2, −1.6% for 4 vs 3. R3 amended, and it notes 2024 by games played was +10.9% |
| F7 | Rank-by-form asserted; season mean predicts better (r 0.517 vs 0.476) | **Fixed.** Streaming sorts by season-to-date mean, with form and the minutes delta shown beside. Preference-ordering test |
| F8 | Server-held sentence with numbers; one-game crediting assumed for every league | **Fixed.** `oneGameCredit` is computed from this league's (or its previous season's) stored weeks, ≥ 90% share, sent as code + share. The sentence and the one cited +4% figure, with provenance, live in web copy |
| F9 | All-Star rows count as games | **Fixed.** Games whose opponent isn't one of the season's team codes are dropped. Tested on the real 2025 game id |
| F10 | Spec and data-model disagree on team | **Fixed.** `player.team` only, and teamless players are excluded. Spec edge case rewritten |
| F11 | Owner naming isn't callable | **Fixed.** Extracted into `RosterOwners.ownerNames`, called by Spotlight and Trends, with a same-name test. Weekly Report's copy is a named follow-up |
| F12 | Per-game usage averaging is noisy; missing keys | **Fixed.** Pooled over the window. Missing keys = 0, and turnovers are `to` |
| F13 | ~13 MB JSON per request, unmeasured | **Partly fixed.** Project only the needed columns. V3 times it locally and in production. Cache only if that's slow (decided on the number) |

Notes taken:
- **Counts and filters:** N1 (counts corrected in R2), N2 (sport filter on every `TEAM_` read),
  N3 (`left(id,5)` instead of LIKE).
- **Data and rules:** N4 (`JdbcTemplate` + `createArrayOf`), N5 (`[]` pre-draft, noted), N6 (the
  league's own scoring, stated), N7 (games null for a complete season; "incl. played" label),
  N8 (look weeks up by number), N9 (min fetch time).
- **UI copy:** N10 ("includes players on waivers"), N15 (fallback role lists labelled "end of
  2025").
- **Config and checks:** N11 (`streaming-size` config), N12 (R5 counts relabelled), N13
  (`playerTrendsLoaded`), N16 (Weekly Report check was run on :8085, main's code, on 10-06;
  recorded as measured).
- **N14:** Spotlight vs. Trends from 10-10 to 10-20 is a named follow-up. Switching Spotlight to
  V28 is a small change, but out of scope.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5. TypeScript + React + Vite.

**Primary Dependencies**: existing only. `PlayerGameRepository`, `GameScoringService`,
`ScheduleGridService` (spec 017), `RosterSeasonRepository`, `LeagueHistoryIngestService`,
`LeagueMembership`, `SportRulesRegistry`, `destinations.ts`, `useLeagueDataVersion`.

**Storage**: V28, two nullable columns on `roster_season`. There's one new read and one changed
write.

**Testing**: JUnit (a pure core over synthetic and real rows), an IT for the V28 round-trip
(the `text[]` bind, lessons bug class #3), Vitest, and the live quickstart.

**Performance**: one season of NBA player rows is ~29k and the team rows ~2.5k. An in-memory
compute is expected to be well under a second. That's unmeasured, and V3 times it.

**Constraints**: no new Sleeper call. `TEAM_*` is excluded by one constant. No defaulted sport
rule, and basketball-only goes through `playsMultipleGamesPerScoringPeriod()`. Hand-set numbers
go in `weights.yml`, labelled. Reasons are codes, and sentences live in the web without ingest
wording. Records, not `Map.of`.

**Scale/Scope**:

- 1 migration, a repository change (upsert + read), an ingest change;
- 1 properties record, 1 service, 1 controller;
- `api.ts`, 1 destination, 1 route and 1 page;
- about 6 test classes.

Guess: ~2 sessions.

## Constitution Check

The constitution is still the template, so the gates are AGENTS.md's, as in plans 009–018.

| Gate | Status |
|---|---|
| Honesty over apparent confidence | ✅ The fallback season is labelled. The game-count note states a measured number and its scope. "Usage rate" is the standard formula, now computable, so it's not a proxy. "Free agent" is refused when rosters aren't loaded |
| Verified vs. assumed | ✅ R1–R7 are marked. "Best game among lineup days" is labelled a hypothesis |
| Migrations append-only | ✅ V28, add-column only |
| `api.ts` mirrors records | ✅ Same change |
| `Map.of` with nulls | ✅ Records |
| Two implementations of one rule | ✅ Games per week come from `ScheduleGridService`, never re-counted. Owner naming reuses the `SpotlightOwnership` rule. Basketball-only reuses the existing sport rule |
| Optional params encoding rules | ✅ A missing config gives `NOT_CONFIGURED`, not a default |
| Ordering tests for rankings | ✅ Riser/faller and form ordering tests |
| Corrections shown | ✅ Dated notes on `nba-minutes-trends.md` (team totals exist) and on the grid doc's view 4 (≥ 4-game filter dropped, +5%) |
| Live verification | ✅ quickstart V3–V8, plus a dated post-draft check |
| Concurrent sessions | ✅ Own worktree. Ask before committing |
| Coding on Sonnet | At implement |

## Project Structure

```text
specs/019-minutes-streaming/  spec, plan, research, data-model, quickstart, contracts/api.md, tasks (next)

backend/src/main/resources/db/migration/V28__roster_players.sql
backend/src/main/java/com/ballknowers/draftsim/
├── store/RosterSeasonRepository.java        # Upsert.players, rosteredPlayers()
├── store/PlayerGameRepository.java          # TEAM_ID_PREFIX, season read excluding teams, teamTotals()
├── ingest/LeagueHistoryIngestService.java   # players ∪ reserve ∪ taxi into Upsert
├── config/PlayerTrendsProperties.java       # + DraftSimApplication registration
├── engine/PlayerTrendsService.java          # pure compute + read
└── api/PlayerTrendsController.java
config/weights.yml                           # draftsim.player-trends block (ARBITRARY)
web/src/{api.ts, destinations.ts, App.tsx, pages/PlayerTrends.tsx (+test), playerTrends.ts (+test)}
claude/nba-minutes-trends.md, claude/nba-schedule-grid-and-streaming.md   # amended notes
```

## Build order

0. **Extract `RosterOwners.ownerNames`** (F11), with Spotlight switched to it and its tests unchanged.
1. **V28 and the roster players**: migration, `Upsert.players` with an array bind, the ingest
   change, and `rosteredPlayers`. Plus an IT that writes and reads back. Then refresh NBA 2026
   locally (V1).
2. **Repository reads**: the `TEAM_` constant, the season read without teams, `teamTotals`.
3. **The pure core first, tests first**: windows, role, usage, form, lists, fallback and
   streaming exclusion. Then `read()` and the controller, and the `AccessControlMvcIT` route.
   Run V2, V3, V4 and V6.
4. **Web**: types, destination, route, page and copy helpers. Run V5 and V7.
5. **Doc amendments + HANDOFF.**
6. **The bug-hunting review** (a separate pass), then **live verification**, then ask, merge and
   deploy, then production V4 before 10-20. The dated post-draft check runs 10-11…10-19.

The adversarial plan review should read this plan cold before step 1.

## Complexity Tracking

No violations.

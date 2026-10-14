# Implementation Plan: Rivalry schedule grid and rivalry week recommendation

**Branch**: `034-rivalry-schedule-grid` (worktree `.claude/worktrees/034-rivalry-schedule-grid`, cut from `origin/main` 8346cdf) | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/034-rivalry-schedule-grid/spec.md`

## Summary

One new league page, `/leagues/:id/rivalries`, for both sports, with three sections:

1. An all-time manager × manager head-to-head matrix over the league's chain.
2. The requested season's schedule as manager × week. Each cell shows the opponent and the
   record against them before that week.
3. A recommended set of rivalry week pairings, with a reason for each.

Everything reads meetings already stored through `pairedWithScores`, the same query as the
versus page. A parity test keeps the two from drifting.

The only new data is Sleeper's **winners bracket**. Without it, playoff weeks can't be split
into playoff and consolation games. Measured: half of the 14 games in a 6-team league's
playoff weeks are consolation (research R2). The bracket gets a migration, ingest on the
normal chain walk, and a bracket-only backfill endpoint. A full history re-ingest isn't safe
for finished NBA seasons (research R10).

Pairings come from an exact max-weight matching (bitmask DP, n ≤ 16) on a hand-set score.
Preference-ordering tests cover the score (research R6).

## Correction to the spec (shown, not hidden)

The spec's Background said the playoff start week "is not stored in any migration today".
**That's wrong.** It lives in `league.settings_json`, and `LeagueRepository.playoffFormat`
already reads it for five callers (research R1). The grep looked for a column and missed the
JSON. The gap that really exists is the one R2 measured: brackets aren't stored. The spec now
carries an amended note in its Dependencies section.

## Amended after review (2026-10-14)

[plan-review.md](plan-review.md) found 1 blocker, 12 should-fix and 6 nits. These amendments
override anything below or in research/data-model/contracts that they contradict. Where the
review left a call to Allan, he asked for the whole pipeline to run without stopping, so the
call is made here and labelled **(decided in-session; Allan can overturn)**.

- **F1 (blocker), accepted.** V32 is taken by the uncommitted 033-trade-verdicts session, and
  it's already applied to the shared 5433 DB (verified: `flyway_schema_history` row 32
  "trade assets"). 034 uses **`V33__league_bracket_match.sql`**.
  - Live verification runs against a **scratch copy** (`draftsim034` on 5433), made with
    `pg_dump | psql`. In the copy only, V32's history row is removed so the 034 build
    validates. The shared `draftsim` DB is never migrated by 034.
  - **Merge order:** if 034 merges before 033, 033 must renumber to V34 before it merges, or
    Flyway rejects it on prod. This is recorded in HANDOFF.
- **F2, accepted.** A game is PLAYOFF only if a bracket row **of that week's round** has the
  pair.
  - Round to week: type 0 is `start + r − 1`. Type 1 is the same, except the last round
    covers two weeks. Any other type → UNSPLIT.
  - If any row of that round has a null `t1`/`t2`, the whole week is UNSPLIT
    (`BRACKET_PENDING`).
  - Brackets are ingested **after** `ingestWeeklyPoints`.
  - 2026 seasons store 7 provisional rows. The quickstart now expects that.
- **F3, accepted.** Totals and `byKind` stay per week, which keeps parity. `finals[]`, every
  reason, and `playoffBonus` are per **bracket match**: the winner is `w`, the points are the
  summed legs, and the final is labelled "two-week final". A test uses West Coast 2025's
  exact shape: legs split 1-1, and 6 wins 269.04–254.52.
- **F4, option (b) (decided in-session; Allan can overturn).** GameKind gains **PLACEMENT**
  for bracket rows with `p` 3 or 5. PLAYOFF is elimination rounds plus the final (`p` null or
  1). Only PLAYOFF earns `playoffBonus` and the PLAYOFF_HISTORY reason. Placement games are
  named in their own words ("met in the 5th-place game").
- **F5, accepted.** A null or empty response never deletes rows. A stored bracket where every
  row has `w` is final, and C2 skips it unless `?force=true`. A null body is "no data". FK
  `on delete cascade`.
- **F6, accepted.** In the chain walk, a **skipped** season with zero bracket rows gets one
  `winners_bracket` call. That's no `/matchups` and no standings. A bracket fetch failure
  fails the chain like any other ingest error (stated and tested), so a season never freezes
  as `loaded_complete` without one.
- **F7, option (a) (decided in-session).** For a playoff week with no stored pairing, a
  manager whose bracket row for that week's round has both slots set gets that opponent,
  with `state: OPPONENT` and `source: BRACKET`. Anyone else is TBD, and the cell sentence
  says pairings arrive once the week is scored. It doesn't hint at running an ingest
  (`NoIngestHintsInMessagesTest`). `ingestRemainingFixtures` is not widened.
- **F8, accepted.** Playoff weeks use `NO_GAME` ("out" or "season over"). `BYE` there only
  when the bracket says so: a round-2 slot with no `*_from`. Regular weeks keep `BYE`.
- **F9, accepted.** Nullable `seasonGrid.currentWeek` is read from the URL's own league row
  (`leg`, through the existing reader, never defaulted). The service **never** uses
  `LeagueSeasonResolver`. A test uses a two-season chain whose newest season has no scores.
- **F10, the spec's version holds.** `NO_HISTORY` means the chain has no season before the
  requested one. data-model is amended.
- **F11, accepted.** New status `NOT_YET_MET`: zero meetings, and `scheduledPairs` pairs them
  in a later week of the requested season. It scores 0. R5's table gets current-pair
  counts: Foot 8/66, West Coast 6/91, NBA 55/66 at ≥ 3.
- **F12, accepted.** DP spec:
  - Managers are sorted by ascending `manager.id`. The state is a bitmask of who is used.
  - Take the lowest free index `i`. If n is odd and the sit-out isn't used yet, try "i sits
    out" first. Then try partners `j > i` in ascending order.
  - A candidate replaces the best only when strictly greater by more than 1e-9.
  - The unpaired reason is honest: "leaving X out gives the highest total; X's best
    available pairing would score S". It's not "fewest qualifying rivalries".
  - A test asserts the tie-break on an all-zero league.
- **F13, accepted.** Parity filters `HeadToHeadService.compute(a,b)`'s meetings by
  `sleeperLeagueId ∈ chain` and recounts. A synthetic two-chain fixture covers it. A matrix
  cell's link says "all leagues" when the versus page would add another chain.
- **F14, accepted as a pinned test.** A single 1-0 final (4.5) outranking a 2-2 pair (4.0) is
  intended: a title-game rematch is the stronger story. A test pins it. Streak is a
  reason, not a score term, and the spec's ranking language is reasons-only.
- **F15, accepted.** Query `pairedWithScores` once per league in the chain (at most 3) and
  attach the league to each game. Self-pairs and null managers are dropped, with a test.
- **F16–F19, accepted:**
  - MOST_MEETINGS only when the max is unique.
  - "Closest" becomes "even".
  - A streak breaks on a tie.
  - A partial config block → `CONFIG_MISSING`.
  - The split-note copy has no ingest hints.
  - The median-game note shows when `medianMatch`.
  - `ManagerCareerService:128`'s note is left (it's about placements, which are still
    unparsed).
  - R10's wording is softened.
  - The destinations row comments why its href is one-season. `'rivalries'` is added to
    `DestinationKey`.
  - Each wide table gets its own scroll container with a sticky first column.

## Technical Context

- **Language/Version**: Java 21 (Spring Boot 3.5, JdbcClient); TypeScript (React + Vite, strict).
- **Primary Dependencies**: existing only. Nothing new on either side.
- **Storage**: Postgres. One new table, `league_bracket_match` (V32). Everything else is
  computed per request.
- **Testing**:
  - JUnit unit tests for the matching and scoring, and for GameKind.
  - Postgres ITs for the repository, the parity with `HeadToHeadService`, and ingest.
  - Vitest for the page and destinations.
  - Live verification in the browser pane.
- **Target Platform**: the existing web app. Railway deploys backend and web separately, so
  both services must ship.
- **Project Type**: web application (`backend/` + `web/`).
- **Performance Goals**: a page answer under ~300 ms locally for a 14-manager chain. The
  inputs are two queries plus a 16k-state DP. This is a target, not a measurement; measure it
  in verification.
- **Constraints**:
  - Sports are never mixed.
  - No new Sleeper calls on the read path.
  - Finished NBA seasons' `/matchups` are never re-fetched.
  - Constitution rules (below).
- **Scale/Scope**: 3 real chains, 12–14 managers, 1–3 seasons each.

No NEEDS CLARIFICATION remains. Research R1–R11 settled every unknown.

## Constitution Check

| Rule | How this plan complies |
|---|---|
| Append-only migrations | New `V32__league_bracket_match.sql`. V31 is the highest on every branch (checked). Nothing existing is edited. |
| `api.ts` mirrors Java records | `RivalryResponse` and children are added to `web/src/api.ts` in the same change as the records ([contracts/api.md](contracts/api.md)). |
| No retuning hand-set constants | `draftsim.rivalry.*` is new, labelled ARBITRARY, and its values and reasons are recorded in research R5/R6 *before* any live output is seen. If football comes out mostly thin, that gets reported, not tuned away. |
| `Map.of` null trap | Nullable fields are listed in contracts C1. Records or mutable maps only. |
| Ask before committing | No commits without Allan's go-ahead. The worktree isolates this from the dirty main checkout. |
| Planning doc ≠ verified spec | The hall-of-fame doc was checked against code. The mechanisms it implied as new (H2H pairs, playoff week) already exist. The one real gap (bracket) was found by measurement, not taken from the doc. |

**Gate: PASS.** It's re-checked after design, and the result is unchanged. The design adds one
table and one config block, and both are covered above.

## Project Structure

### Documentation (this feature)

```text
specs/034-rivalry-schedule-grid/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R11, with measurements
├── data-model.md
├── contracts/api.md
├── quickstart.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code

```text
backend/src/main/resources/db/migration/
└── V32__league_bracket_match.sql                 # new

backend/src/main/java/com/ballknowers/draftsim/
├── store/LeagueBracketRepository.java            # new: replaceForLeague(), forLeagues()
├── ingest/BracketIngestService.java              # new: one season's winners_bracket → repo; used by C2 and C3
├── ingest/LeagueHistoryIngestService.java        # + one call per non-skipped season
├── engine/HeadToHeadService.java                 # outcome rule extracted to a shared static (R3), no behaviour change
├── engine/RivalryService.java                    # new: matrix, season grid, recommendation
├── engine/RivalryMatching.java                   # new: pure scoring + bitmask DP, no Spring, no DB
├── config/RivalryProperties.java                 # new: draftsim.rivalry
├── api/RivalryController.java                    # new: C1
└── api/IngestController.java                     # + C2

config/weights.yml                                # + draftsim.rivalry block, labelled ARBITRARY

backend/src/test/java/com/ballknowers/draftsim/
├── engine/RivalryMatchingTest.java               # preference ordering, optimality, odd n, determinism
├── engine/RivalryServiceTest.java                # GameKind, cell states, prior-record cut, mirror
├── engine/RivalryParityIT.java                   # SC-001/SC-002 vs HeadToHeadService on real-shaped data
└── store/LeagueBracketRepositoryIT.java          # replace semantics, nullable slots

web/src/
├── api.ts                                        # + rivalry types, fetchRivalries()
├── destinations.ts (+ .test.ts)                  # + 'rivalries'
├── App.tsx                                       # + route
└── pages/Rivalries.tsx (+ .test.tsx)             # new page, three sections
```

**Structure Decision**: the existing two-project web layout. `RivalryMatching` is kept pure
(plain inputs, no Spring) so the scoring and DP are unit-tested without a DB.

## Build order

1. **V32, the repository, `BracketIngestService`, the C2 endpoint.** Run the backfill on
   local. Confirm 7 matches per completed season and unchanged score sums (quickstart §1).
   Everything after depends on real bracket rows existing.
2. **Extract the outcome rule** from `HeadToHeadService`. `HeadToHeadServiceTest` must stay
   green, unchanged.
3. **`RivalryMatching` and its tests**, before anything uses it: preference ordering first.
4. **`RivalryService` and `RivalryController`**, plus the parity IT against the local chains.
5. **Hook bracket ingest into `ingestChain`** (C3).
6. **Frontend**: types, destination, route, page. The page reads the payload as-is and doesn't
   recompute any record client-side.
7. **Verification pass** (quickstart §2–§6), written to `verification.md`. Then the deploy plan:
   both Railway services, plus the C2 backfill on prod for each league.

Per AGENTS.md: an adversarial plan review (`plan-review.md`) comes before any code, a
bug-hunting code review after the build, and coding subagents run on Sonnet.

## Open risks the review should attack

- **R3's chain assumption.** `(season, week)` is unique inside a chain. If a chain ever holds
  two leagues of one season, the batch query mis-assigns games. A test pins it, but should the
  service refuse such a chain instead?
- **Round type 1 finals counted twice** (research R2, *inferred*). Is "two meetings, one final"
  the right reading for the reason text?
- **Football thinness.** With most pairings below N, does the recommendation still say anything
  useful? Or should a thin-majority league get a softer headline ("not enough history for real
  rivalries yet; here are the closest you have")? The spec allows either. This is a wording
  decision for Allan, not a modelling one.

## Complexity Tracking

No constitution violations. Nothing to justify.

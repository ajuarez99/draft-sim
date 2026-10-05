# Research: Stop saying basketball has no projection source

**What was measured vs reasoned.** R0 is measured (2026-10-05, curl). R6's claim
that the NBA 2026 league reaches the sport check is **reasoned from code**
(`LeagueAnalysisService.java:431-439` plus the league's stored settings) and not
yet observed. quickstart V3 observes it. Nothing else here needed measurement.

## R0: The premise being corrected

- **Decision**: treat "Sleeper has no basketball projection source" as false.
- **Evidence**: `GET /projections/nba/2026/{1,2,10,20}?season_type=regular&position[]=PG…C`
  returned 200 with 945 / 1,072 / 1,172 / 1,091 rows carrying a stat line.
  Rows are one per player per game (`gp: 1.0`, `game_id`, `date`, `opponent`),
  `company: "rotowire"`. Details in `claude/projection-tools.md`.
- **What stays true**: the columns this app stores (`pts_ppr`, `pts_half_ppr`,
  `pts_std`) are football scoring totals. NBA rows carry raw stats instead. So
  "the app's projection pipeline is football-shaped" is true. "No source exists"
  is not.

## R1: Wording of the analysis reason

- **Decision**: `"Roster projections aren't built for " + sport + " leagues yet:
  so far this app only has football projections."` Exact strings are in
  [contracts/messages.md](contracts/messages.md).
- **Rationale**: says what's true (not built, football-only so far) and claims
  nothing about whether a source exists. That's correct for basketball
  (one exists) and for a hypothetical third sport (unmeasured), which is the
  spec's edge case. Contractions and plain words follow spec 013's fan-first copy.
- **Alternatives considered**:
  - *"Sleeper publishes NBA projections but…"*: true for NBA, but it bakes a
    sport-specific measured fact into a message built for every non-NFL sport,
    and it goes stale the day Phase 3 ships. Rejected.
  - *Keep a doc pointer* (`See claude/…`): the old message pointed readers at a
    repo doc. A fan reading the Analysis page can't open `claude/`. Spec 009 R10
    already removed internal endpoints from messages for the same reason. The
    pointer moves to the code comment. Rejected for user-facing text.

## R2: Wording of the ingest 400

- **Decision**: `"projections are football-only for now: the stored columns are Sleeper's
  pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. Basketball
  projections need per-game stat lines scored with each league's settings, which
  isn't built yet (claude/projection-tools.md)."`
- **Rationale**: this endpoint's reader is an operator, not a fan, so the
  technical reason and the doc pointer are useful here. It must not contain
  `/api/ingest` or `POST /api/`: `NoIngestHintsInMessagesTest` scans
  `api/` string literals and would fail. The proposed string contains neither.
- **Alternative**: drop the refusal and ingest NBA anyway. That's Phase 3, and
  storing NBA rows in football-shaped columns would write nulls. Rejected.

### Amended during implementation (2026-10-05): both strings first said "ingest", and that failed

R1 first read "…so far this app only **ingests** football projections" and R2
"**projection ingest** is football-only…". T007 failed on both:
`NoIngestHintsInMessagesTest` has a **second rule** that R1/R2 missed. Any
sentence-like string literal (one that contains a space) in `api/`, `engine/` or
`mock/` that uses the word "ingest" fails as developer jargon. It was added in
live verification on 2026-09-28 (`hasHint`, `:76-89`). Its class javadoc
describes only the endpoint rule, and that's all this research read.
**Fix the text, not the scanner**: M1 now says "only has football projections",
and M2 "projections are football-only for now: the stored columns are…". The
lesson for planning: read a guard test's assertions, not just its javadoc.

## R3: How to test it

- **Decision**:
  1. Extract the analysis reason into a package-private static
     `LeagueAnalysisService.projectionsNotBuiltReason(Sport)`, called from
     `projectionBlocks`. Assert on it in `LeagueAnalysisServiceTest`, which is
     already a pure-function test class (no DB, no mocks). The test asserts the
     reason for `Sport.NBA` contains "aren't built" and `nba`, and does not
     contain "equivalent".
  2. In `IngestControllerTest` (Mockito, already constructs the controller),
     `assertThrows(IllegalArgumentException.class, () -> controller().projections("nba", 2026, 1, 1, false))`,
     assert the message does not contain "equivalent", and verify
     `projectionIngest` was never called (refusal behavior pinned).
- **Rationale**: both fail on today's code (SC-001) and run without Postgres,
  so they can't be silently skipped (memory: the backend suite skips ITs
  silently).
- **Alternative**: an IT through `analyse()` with a seeded NBA league. That's
  heavier, and it's skipped when the DB is down. The live check (V3) covers the
  wiring instead. Rejected.

## R4: The football-only gate in `destinations.ts`

- **Decision**: keep `sports: ['nfl']`. Rewrite the comment's reason from "has
  no basketball equivalent" to "basketball projections aren't wired up yet (see
  claude/projection-tools.md); a basketball league would get one working block
  and two explaining themselves."
- **Rationale**: the gate's justification ("two of three blocks would be
  empty") is unchanged and still sufficient. Only the cause it names was wrong.
  Changing the gate is Phase 3 (roadmap 3.2).

## R5: Docs that stated the premise

- **Decision**: add dated "amended" notes, with no silent rewrites (AGENTS.md
  convention), to:
  - `claude/league-analysis.md:252` (non-goal: "Basketball. `pts_ppr` is a
    football stat key…"). That's still true of `pts_ppr`. The note adds that a
    basketball source exists and points to projection-tools.md.
  - `specs/004-ffwrapped-feature-parity/spec.md:266-270` (out-of-scope:
    "a projection source that does not exist").
  - `claude/competitor-gap-roadmap.md` Phase 0 ("false statement on a live
    page" was overstated: the page isn't linked for NBA leagues).
  - `FootballRulesTest.java:269` comment ("true, and still true").
- **Not amended**: `SportRules.java:85` and `FootballRules.java:25` talk about a
  sport "without a projection source" generically, or about football only.
  Neither asserts anything false about basketball.

## R6: A league to verify against

- **Decision**: NBA "Ball Knowers" 2026, Sleeper id `1339351318115946496`
  (local DB: `status = pre_draft`, `playoff_week_start = 20`, no
  `last_scored_leg`).
- **Reasoned, not observed**: with no scored week, `fromWeek` = 1 and `toWeek` = 19,
  so neither earlier guard (`:431-439`) fires and the request reaches the sport
  check. The 2025 league would hit "the regular season is over" first and never
  show the message, so it's the wrong one to check.

## R7: A regression guard for the phrase

- **Decision**: no new source-scan test. SC-002 is a one-off grep in quickstart V2.
- **Rationale**: the two runtime strings are pinned by R3's tests. A repo-wide
  phrase scan would catch comments too, and that's more ceremony than one
  sentence deserves. `NoIngestHintsInMessagesTest` exists because ingest hints
  were a recurring class. "No equivalent" claims have one origin, and this
  removes it.

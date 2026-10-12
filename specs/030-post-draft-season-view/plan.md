# Implementation Plan: The season after the draft, before the games

**Branch**: `030-post-draft-season-view` | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/030-post-draft-season-view/spec.md`

## Summary

After a draft and before the first scored week, the league home should be about this
season. It should say who has the strongest roster and who the projected favorite is, and
stop leading with last season's blocks.

Reading the code changed the shape of the work in three ways:

1. **"Strongest team" is already built, for football only.**
   `LeagueAnalysisService.projectionBlocks` ranks every roster's best legal lineup by
   projection, by position, with `isMe`, `bench` and `missing`. It's gated off basketball
   by a single `if` (research R4). Story 2 becomes a basketball path through that block,
   not a new ranking.
2. **Basketball needs per-league scoring of a per-game projection line.** Sleeper's NBA
   season projections are per-game raw stats (measured: 529 players, rotowire). They get
   stored as-is in a new table and scored per league by the existing `GameScoringService`
   (R1). The weekly value is one game per starter, the league's measured crediting rule
   (R3).
3. **Draft grades can't run before games.** They're built from realized production
   (R6), so the spec's FR-010 is amended. Before games, the home links the completed
   board. Grades join the home from the first scored week.

On top of that come:
- A single backend `SeasonState` rule (R5).
- One new home-summary endpoint.
- Home changes keyed on that state.
- A favorite headline (margin form, no odds).
- A daily refresh step for NBA season projections.

Story 5 (preseason odds) isn't built. R9 records its measured pre-checks.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads). TypeScript strict, React + Vite.

**Primary Dependencies**: existing only. Spring JDBC, Flyway, RestClient for Sleeper, React Router.

**Storage**: Postgres. One new table, `player_season_projection` (V31). V31 is free on every local branch, checked 2026-10-11.

**Testing**:
- Backend: JUnit 5, with ITs against Postgres 5433. Check that the skip count is 0.
- Web: Vitest + Testing Library.
- Live: browser pass on the real NBA 2026 league (quickstart V5).

**Target Platform**: Railway (two services, which deploy independently); local Windows dev.

**Project Type**: web application (`backend/` + `web/`).

**Performance Goals**:
- The home's new endpoint is one strength pass: one Sleeper rosters call plus an in-memory
  lineup per roster. That's the same cost as today's `/analysis`, which serves NFL already.
  No new target is set. The cost is measured in verification, not guessed.
- The ingest is one ~1.5 MB call, about 0.4 s (measured).

**Constraints**:
- Mustn't change any NFL number (SC-005).
- Mustn't run the projection fetch from a page GET (existing discipline,
  `ProjectionIngestService` javadoc).
- Must ship before NBA week 1 is scored (about 2026-10-20 to 10-26), or `DRAFTED_UNSCORED`
  can't be verified live until next season.

**Scale/Scope**: two NBA leagues and one NFL league on prod, 12 rosters each.

All of the spec's open points are resolved in research.md. Nothing is left as NEEDS CLARIFICATION.

## Constitution Check

| Rule | How this plan meets it |
|---|---|
| Migrations append-only | New `V31__player_season_projection.sql`. V30 isn't touched. |
| `api.ts` mirrors Java records | `Projections` gains 4 fields and `SeasonStart` is new. Both go into `api.ts` in the same commit as the records (contracts C1/C2). |
| Don't retune constants to match guesses | The near-tie threshold is new, hand-set and labelled ARBITRARY in `weights.yml`. The ~15% credited-game gap (R3) is stated, **not** used as a scale factor. `STALE_AFTER` is reused and stays labelled a judgement. |
| `Map.of` null hazard | `gamesThisWeek.byRoster`, `me`, `favorite`, `firstWeek.startDate` and `draft.draftId` are all nullable. Response maps get built mutably. |
| Ask before committing | Unchanged. No commit without Allan's go-ahead. Concurrent sessions are real, so stage only this spec's paths. |
| Planning-doc scope isn't a verified spec | Applied twice. The spec's "new strength ranking" is already built (R4), and the spec's "grades already built, surface them" doesn't hold before games (R6). Both are corrected visibly. |

**Gate: PASS.** No violations, so Complexity Tracking is empty.

**Re-check after Phase 1 design: PASS.** The design adds one table, one endpoint and one
refresh step, and reuses the lineup/ranking/scoring implementations instead of adding
second ones.

## Project Structure

### Documentation (this feature)

```text
specs/030-post-draft-season-view/
├── spec.md              # amended after planning (FR-010, 3.1 question)
├── plan.md              # this file
├── research.md          # R1–R10, measured vs assumed labelled
├── data-model.md        # E1–E7
├── quickstart.md        # V1–V8 live verification
├── contracts/api.md     # C1–C6
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
└── V31__player_season_projection.sql                 # new (E1)

config/weights.yml                                    # + preseason.near-tie-pct (ARBITRARY)

backend/src/main/java/com/ballknowers/draftsim/
├── ingest/
│   ├── SleeperProjectionClient.java                  # + season(sport, season) with per-sport positions
│   └── SeasonProjectionIngestService.java            # new: fetch, validate basis, upsert+prune (E1)
├── store/
│   └── PlayerSeasonProjectionRepository.java         # new
├── engine/
│   ├── SeasonStateService.java                       # new: the one rule (E2)
│   ├── LeagueAnalysisService.java                    # NBA path through projectionBlocks (R4); +unit/provenance/…
│   ├── SeasonStartService.java                       # new: C1 assembly, favorite (E5), first week (E7)
│   └── (reused) GameScoringService, BasketballRules.startingLineup, Ranker,
│       ScheduleGridService, NextMatchupService, DraftGradesService
├── api/
│   ├── SeasonStartController.java                    # new: C1
│   └── IngestController.java                         # + POST /season-projections (C3)
└── refresh/
    └── DailyRefreshService.java                      # + season-projections step (C4)

web/src/
├── api.ts                                            # Projections fields, SeasonStart types, getSeasonStart
├── destinations.ts                                   # Analysis offered for nba (C6)
├── pages/LeagueHome.tsx                              # state-keyed blocks (C5)
├── pages/LeagueAnalysis.tsx                          # unit labels, hide empty byWeek (C6)
└── components/PreseasonStrengthBlock.tsx             # new: favorite headline + top 3 + you
```

**Structure Decision**: this is the existing web-application layout. Nothing new at the top level.

## Design notes the reviewer should attack

1. **The `SeasonState` source.** Stored weeks, not Sleeper's `status`. Measured: Sleeper
   already says `in_season` for this preseason league. The risk is a lagging history
   ingest keeping the home in `DRAFTED_UNSCORED` after week 1 is really scored. That's
   acceptable, because the state is self-consistent with every other stored number on the
   page (same reasoning as `LeagueAnalysisService:224`).
2. **Per-week vs rest-of-season units.** NFL `total` is a rest-of-season sum, and NBA
   `total` is per week. The favorite headline normalizes both to pts/week (E5). Any
   other cross-sport comparison is a bug.
3. **`byWeek` is empty for NBA.** Every NFL consumer of `byWeek` has to tolerate `[]`.
   The build must grep them all.
4. **Bench valuation and `missing`** behave identically for NBA. The `OFF_BOARD_ADP`
   synthetic entry path is reused unchanged.
5. **Analysis page exposure.** Turning Analysis on for NBA exposes the ranking-score and
   scores grid blocks too. Their empty states were written for NFL. The audit list is in
   C6.
6. **Stale-projection risk.** The daily step plus the 6 h freshness. If the upstream
   endpoint vanishes (it's undocumented), the block says projections aren't loaded. It
   doesn't keep showing an old set as current: `fetchedAt` is on the page.

## Amended after plan review (2026-10-11)

[plan-review.md](plan-review.md) read the plan cold against the code and live Sleeper data.
Every finding is accepted unless noted. Those that change the design:

- **B1, IR players were projected as starters.** Measured live: 19 reserve players across
  11 of 12 rosters, all inside `roster.players`, all carrying per-game projections. The
  per-game source has no games-played count, so this would credit an injured star every
  week. **Change**: on the NBA path, players in `roster.reserve` are out of the starter
  pool. They're listed on the bench at 0 and counted in a new `RosterProjection.reserve`.
  The footer says "injuries and missed games aren't modelled; players on IR aren't counted".
  The NFL path is **not** changed here: its weekly projections already zero out most injured
  weeks, and changing it would break SC-005. It's flagged as a separate follow-up task.
  Story 5's "injuries via games-played assumptions" row was false for this source and is
  corrected in spec.md.
- **B2, the state flipped on a partial week.** `SeasonState` now uses
  `ScoredWeeks.Snapshot.latestFinal`, the same signal draft grades and playoff odds use. It
  doesn't use `storedWeeks`, which would have been a fourth "is it scored" rule.
  `DRAFTED_UNSCORED` lasts until week 1 is **final**, so the favorite stays up through
  week 1. The FR-003 removal changes from "drop by state" to **"drop a block whose data is
  from another season"**. The matchup block already detects this (`LeagueHome.tsx:337`), so
  week 1's live 2026 matchup shows as soon as it exists.
- **S1/S2**: the home's `/analysis` fetch is gated on `sport === 'nfl'`, so the NBA home keeps
  its single next-matchup block. `season-start` computes strength **only** in
  `DRAFTED_UNSCORED`.
- **S3**: `reason` stays a sentence. A nullable `reasonCode` is added to `Projections`.
- **S4, rejected in part.** A coverage cut doesn't work: `dd` is on 209 of 529 lines and
  `td` on 26, and the missing lines are legitimate projected zeros. So coverage can't tell
  "unprojected" from "projected zero". The rule stays "a scored key on zero stored lines".
  R2's count of six is corrected to **five** (`ff` appeared on 1 line at review time). Tests
  use fixtures, not the hard-coded list.
- **S5**: "games this week" becomes **"your projected starters with no game in week N"**. It's
  a pure helper over the grid's team rows and each starter's team, because under
  one-credited-game that's the number that costs points.
- **S6**: FR-004/SC-005 are amended to "identical except the draft-grades summary block".
- **S7**: the Analysis page task now names every NBA-false string and control (scoring
  label, rest-of-season, bye weeks, the bump block's sentence, the week picker).
- **S8**: a new task extracts a package-private static ranking core before the ordering test.
- **S9**: a roster with `players: null` is treated as empty.
- **S10**: `PreseasonProperties` is `@Validated` with a positive threshold, registered in
  `DraftSimApplication`, with a test that binds the real `weights.yml`.
- **S11**: the headline always shows **both** projected values. A single favorite is named
  only when the gap is at least `preseason.favorite-min-gap-pct`, **3.0**, hand-set and
  ARBITRARY (raised from 1.0, given errors of unknown size on top of the ~15% basis gap).
  Under that, the headline is a tie. One denominator throughout: a gap is measured as a
  percentage of the **lower** team's total, which matches the spec's "% of 2nd place's
  value". The footer adds "a gap this size is within what these projections can't resolve"
  whenever the gap is under 2× the cut.
- **S12**: parity is captured back to back against the same DB, with no ingest between,
  diffed with the new fields deleted, and the NFL home's block list is captured too.
- **Nits** N1–N12 are applied in tasks.md:
  - `AnalysisProjections` is the type name.
  - Use `sleeperDraftId`.
  - Hide the FR-003 blocks while loading, and keep today's layout only on error.
  - Parse the date without UTC.
  - The week-1 date comes from the grid's week `firstDate`, falling back to state
    `season_start_date` only when `league_season` matches.
  - A missing draft row falls back to `LeagueRow.status`.
  - The daily step is best-effort (`DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT`).
  - Scoring comes from `leagues.scoringOf`.
  - Guard the NBA starting slots.
  - Use "primary position" wording.
  - Use empty lists, not nulls.
  - Add a projection age line past 3 days (`preseason.stale-after-days`, ARBITRARY).

- **R8 (code review), accepted:** NFL in `DRAFTED_UNSCORED` runs two lineup passes per home load
  (`/analysis` and `/season-start`). That state only exists for NFL next August, so the cost is
  accepted and nothing is changed now. *Amended after code review.*

## Amended after live check (2026-10-12): injury status, not the IR slot

The B1 fix ("exclude every `roster.reserve` player on the NBA path") was **wrong for this
league**, measured on live Sleeper data on 2026-10-12:
- The league has IR+ enabled (`reserve_allow_dtd: 1`, `reserve_allow_out: 1`, 2 slots).
- 11 of the 17 IR-slotted players are only DTD or GTD, mostly preseason "Rest" tags.
  Giannis Antetokounmpo is one of them ("DTD · Rest"), so the B1 rule valued him at 0 and
  helped put wags707 last.
- Only 6 IR-slotted players, and 1 active one (Maxime Raynaud, dislocated knee), are tagged
  "Out".
- Across all NBA players the tags are DTD 108, Out 21, GTD 20, IR 2.

Allan's call: in past seasons an IR player was out, but with this year's IR+ they should be
modelled as DTD, because they can still probably play.

**New rule (NBA path only; football unchanged):**
- A rostered player is **unavailable**, counted at 0, when his `injury_status` is `Out`,
  `IR` or `Sus` (case-insensitive), **wherever he sits**.
- Everyone else is a lineup candidate, **including IR+-slotted players**. That means DTD,
  GTD and untagged players. They count fully, because Sleeper credits one game per starter per
  week and a DTD player only has to play once. There's no discount: an availability share
  can't be fitted without injury history, so none is invented.
- They're flagged, not hidden. A starter who sits in an IR+ slot carries `onReserve: true`,
  and every lineup player carries his tag.
- `RosterProjection.reserve` is renamed **`unavailable`**: the count of Out/IR/Sus players
  set to 0. Their bench rows use slot `"OUT"`.
- The footer reads: "Players tagged Out count as zero wherever they sit. Day-to-day and
  game-time players count in full, including ones parked on IR+, since one game a week is
  credited."

## Phases after this command

- **Adversarial plan review** (`plan-review.md`): a cold read hunting gaps before any code.
- `/speckit-tasks`, then the build on Sonnet subagents (AGENTS.md).
- Bug-hunting code review (`code-review.md`).
- Live verification (`verification.md`, quickstart V1–V7 before tip-off, V8 after).

## Complexity Tracking

None.

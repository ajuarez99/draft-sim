# Implementation Plan: Historical Draft Game

**Branch**: `053-historical-draft-game` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/053-historical-draft-game/spec.md`, plus the
design pass in `claude/historical-draft-game.md`.

## Summary

The user drafts a random NBA season, 1970-71 to 2025-26, against neutral bots in a
12-team, 14-round snake draft. Player cards show only the prior season's stats.
After the draft, the real season replays as lock-in best ball: each starter's best
single game per week counts, and the lineup is filled automatically.

The engine needs almost nothing new. `MockDraftEngine` and `DraftContextFactory`
already run on any `List<BoardEntry>`. The new parts are:
- a one-off **import** of historical box scores (CC0 Kaggle for 1970-71 to 2016-17,
  Sleeper for 2017-18 on), with an **era rule** that scores only stats recorded
  by the league and present in at least 90% of played box scores, gaps scored 0 (amended after review: was 95%);
- a **historical board builder** that ranks by prior-season points;
- **separate session tables**, because `mock_draft_pick` has a foreign key to
  `player`;
- a **season replay** that freezes its results;
- a new web page built from the existing draft-room components.

Research found that Sleeper's `adp_std` looks hindsight-contaminated
(research R4), so every season uses the labelled fallback ranking in v1.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5); TypeScript + React (Vite)

**Primary Dependencies**: existing only. Spring JDBC, `RestClient`, Flyway, Jackson.
The CSV is parsed with a small in-house reader: quoted fields only, no new library.

**Storage**: Postgres. Migration `V43__historical_draft_game.sql` (data-model.md).

**Testing**: JUnit 5 unit tests and `*IT` integration tests against Postgres. Vitest
for web. Live verification in the browser pane.

**Target Platform**: Railway (two services), the same as everything else.

**Project Type**: web application (backend/ + web/)

**Performance Goals**: SC-001. Bot advance plus the full-season replay finish in
under 2 s server-side for one game: about 168 rostered players × about 80 games
each, a few thousand scorings. The import runs once and is admin-triggered, with
no latency target. It has to finish inside Railway's request timeout or run async.
That is decided in tasks: async with a polled report if one season takes over 30 s
locally.

**Constraints**:
- Never fetch Kaggle on a user request.
- Pin the dataset by version (2) and verify its SHA-256 (research R1).
- Prod memory is `-Xmx1024m` (spec 041), so the 158 MB CSV must be **streamed**,
  never loaded whole.

**Scale/Scope**: about 1.0M Kaggle game rows plus about 0.25M Sleeper rows (10
seasons × about 25k). Production DB growth is unmeasured; see O3.

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | PASS. One new V43, nothing edited. The number is re-checked at build time against origin/main and every branch |
| `api.ts` mirrors Java records | PASS by plan. contracts/api.md is the single shape. Tasks pair each record with its TS type in one task |
| Never retune a hand-set constant to match a guess | PASS. The era threshold (90%, amended from 95% after review) and the scoring values are new, labelled constants in config. R1's wrong 902 guess is recorded as wrong, not "fixed" |
| No `Map.of` with nullable values in JSON paths | PASS by plan. Nullable fields are listed in contracts. Builders use mutable maps |
| Ask before committing | PASS. Commit, push and PR wait for Allan |
| A planning doc's difficulty isn't a spec | APPLIED. The design doc said "reuse the draft room"; reading the code found `mock_draft_pick.player_id → player(id)` (V3), so storage can't be reused, while the engine fully can (research R9) |

Re-checked after Phase 1 design: no violations, so no Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/053-historical-draft-game/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/api.md
├── checklists/requirements.md
├── plan-review.md        (adversarial review, next stage)
└── tasks.md              (/speckit-tasks)
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── historical/                      NEW package
│   ├── HistoricalImportService      orchestrates; one transaction per season; report
│   ├── KaggleHistoricalSource       download (pinned version + SHA-256), stream both CSVs,
│   │                                Game_ID season decoding, name normalisation, aliases,
│   │                                collision detection, derived dd/td/bonus keys
│   ├── SleeperHistoricalSource      totals + weekly logs 2016..2025 via existing
│   │                                SleeperPlayerStatsClient (new totals method), drops TEAM_* ids
│   ├── EraScoring                   counted-stats rule (R2) → filtered scoring map + exclusion reasons
│   ├── HistoricalWeeks              Mon–Sun bucketing from opening night; 2019-20 cut
│   ├── HistoricalBoardBuilder       board for a season: prior-season fallback rank → BoardEntry
│   ├── HistoricalGameService        create / pick / auto / abandon; drives MockDraftEngine
│   ├── SeasonReplay                 schedule, weekly best-game lineups, standings, comparisons
│   └── HistoricalRepository         JDBC for the six tables
├── engine/RealizedLineupService     EXTRACT bestLineup(Map<Player,Double>, settings, rules) overload
├── api/HistoricalGameController     /api/history/** (contracts/api.md)
├── api/HistoricalImportController   /api/admin/historical/import (AdminAccess)
backend/src/main/resources/
├── db/migration/V43__historical_draft_game.sql
├── historical/historical-name-aliases.csv
├── historical/historical-same-name.csv
└── application.yml                  historical.scoring, historical.era-threshold: 0.90 (labelled hand-set)
web/src/
├── api.ts                           HistoricalGameState & friends (mirrors contracts)
├── pages/HistoricalDraft.tsx        start screen + past games list (US4)
├── pages/HistoricalRoom.tsx         draft room from existing components, then results
└── components/HistoricalResults.tsx standings, weekly matchups, hindsight/bust/steal
```

**Structure Decision**: the existing web-app layout. A new `historical/` backend
package keeps the import and the game off the hot paths of the league features.
The only edit to existing code is the `RealizedLineupService` extraction, plus a
season-totals method on `SleeperPlayerStatsClient`.

## Key design decisions (details in research.md)

1. **One source per season** (R3). The Kaggle/Sleeper seam needs no cross-source
   identity join.
2. **Era rule only for Kaggle seasons** (R2). Sleeper omits zero stats, so absence
   there means 0.
3. **Fallback ranking everywhere in v1** (R4). Sleeper ADP failed the leak check;
   Yahoo history is unavailable.
4. **Identity is fail-loud** (R5). The import refuses a season with a
   fantasy-relevant unmatched name or an uncurated same-name collision.
5. **Results are frozen** (data-model). The replay runs once, at the last pick, and
   is stored with the scoring snapshot.
6. **Bots use neutral profiles and uniform priors** (R9). Fitted priors describe
   Allan's league, not 1985.

## Risks

- **Data provenance**: the CC0 dataset's season file is Basketball-Reference-shaped,
  and its box scores came via NBA.com. We rely on the uploader's CC0 licence.
  Allan should accept this explicitly before production.
- **Early-1970s scoring thinness** (O2): this is a product call.
- **Production DB size** (O3): measure locally first.
- **Board leak** (R6): season-long absentees are missing from the board. This is
  documented and accepted.

## Amended after review (2026-11-02)

[plan-review.md](plan-review.md) found 21 issues: 4 HIGH, 10 MEDIUM, 7 LOW. All
21 are adopted, and the text above is superseded wherever this section disagrees.
Allan decided O1, O2 and F6.

| # | Sev | Resolution |
|---|---|---|
| F1 | HIGH | **Bust formula was backwards.** It is now the argmax over user picks of (season-value rank − overall pick no.), and null if no gap is positive. A preference-ordering unit test is required: pick 3 finishing 150th is the bust, and pick 160 finishing 2nd never is. |
| F2 | HIGH | **Age-continuity check added**: a name in seasons S and S+k must have ages differing by k−1..k+1. A violation counts as an uncovered collision, so the import fails. Every season row of a curated same-name has to be keyed. The adjacent-season cases (Charles Smith 1996-98, Charles Jones 1997-99, Michael Smith 1994-96, Chris Johnson 2012-14) join SC-007's list. |
| F3 | HIGH | **The CSV is player-sorted** (120,134 season switches in 1.31M rows), so a single streaming pass can't group by season. Phase 1 streams the zip entry once into an `UNLOGGED` staging table via pgjdbc `CopyManager`, which is already on the classpath. Phase 2 runs one transaction per season from staging, then drops staging. Bulk writes use COPY or `batchUpdate`. |
| F4 | HIGH | **Hindsight is lineup-aware.** At each user pick it takes the candidate that maximises the hindsight team's season best-ball total. Candidates are the players actually available at that pick, minus earlier hindsight picks; players a bot took later are allowed. The hindsight roster is scored on its own and never changes a bot's roster. If it scores below the user, the page says "you beat the greedy hindsight draft" instead of showing a negative gap. |
| F5 | MEDIUM | **Positions move to `historical_player_season`.** Card and eligibility use the *prior* season's `Pos`; a rookie uses his drafted-season `Pos`, which is a small documented leak. Hyphenated values map to the union, with the first one primary. The import fails on any board player with an empty position set, because `Player.primary()` returns `WR` for an empty list. Non-fantasy-relevant player-seasons with no season-file match are left off the board. Kaggle eras are almost all single-eligibility while Sleeper eras are multi; that is real structure and is stated. |
| F6 | MEDIUM | **Allan: per-game ranking.** Rank by per-game fantasy points × a standard season length (hand-set, 72 games), using the better of the last two seasons among those with at least the minimum-games floor (hand-set, 20 games). Rookies and ABA arrivals in 1976-77 still rank last, and the card says so. *Amended at build 2026-11-02:* window widened to the last 3 seasons (config `rank-lookback-seasons`), because 2 seasons left Jordan 1995-96 (17 G, then retired) unranked — the exact case F6 was chosen to fix. *Amended again 2026-11-02 (Allan):* the newest qualifying season in the window is used, not the best, so declining veterans are judged on their latest full season. |
| F7 | MEDIUM | **Denominator = played rows** (minutes or points > 0). Combined with Allan's O2 decision, the final rule is FR-007 as amended: tracked by the league **and** ≥ 90% coverage, with gaps scored 0 and shown. The outcome table is in research R2. |
| F8 | MEDIUM | `historical_game.reversal_round int not null`, value 0 (plain snake), passed through the 6-argument `LeagueSettings`. The engine and the turn, myPicks and 409 checks all read that one column. |
| F9 | MEDIUM | The import **upserts** `historical_player` and never deletes it. The board (ids, rank, positionalRank) is **snapshotted onto the game** at creation and every later call builds from that snapshot. Ties break by id. The builder documents that it sorts by ascending `adp`, which `PickDecider` depends on. |
| F10 | MEDIUM | Pick, auto and abandon take the row lock (`lockForUpdate`, as in `MockDraftService`). The replay runs only if the locked row is still `DRAFTING`; otherwise 409. |
| F11 | MEDIUM | **DNPs are dropped at import**: for Sleeper, empty stats (`BasketballRules.playedIn`); for Kaggle, minutes and points both 0. A week with no played game is absent and scores 0 only as an empty slot. An all-negative week keeps its negative max, as in real lock-in. |
| F12 | MEDIUM | Card `perGame`/`fantasyPoints` and the ranking use **counting stats only, counted keys only**, the same for both sources, labelled "excludes bonuses". The replay keeps bonuses. Cards filter out non-counted stats (SC-004). |
| F13 | MEDIUM | FR-002 reworded: the app's standard NBA shape (12 × 14, `NBA_ROSTER`) plus Legacy scoring, with the provenance shown on screen. |
| F14 | MEDIUM | The **import is async**: a job row plus `GET /api/admin/historical/import/{id}` for its report. Alias and same-name files are curated **locally** until the report is empty, before prod. `historical.kaggle-zip-url` is overridable, and the SHA-256 is checked regardless of the URL. A season is offered only if its own rows **and** its prior season's lines committed; 1969-70 lines are imported though that season isn't offered. |
| F15 | LOW | Box stats are stored as **typed nullable columns** (smallint pts/reb/ast/stl/blk/to/tpm/fgm/fga/ftm/fta, plus derived dd/td/bonus flags), with NULL meaning unrecorded. An integer `game_id`. Measure locally and check the Railway volume before the prod import. |
| F16 | LOW | Standings get `ties`, ordered W-L-T, then points for, then a seeded coin flip. Weeks are numbered from game dates only, so an empty calendar week is skipped. |
| F17 | LOW | `historical/team-codes.csv` maps between the box and season team codes. Dates parse with `parseCaseInsensitive()`, `Locale.US`. An explicit column map (TOV→to, FG3M→tpm…), plus parser unit tests. |
| F18 | LOW | Routes `/history` and `/history/:id` go inside the signed-in shell in `App.tsx`, with a global rail entry like `/managers`. A missing identity header returns 401. Deploy the backend before the web service. |
| F19 | LOW | A rookie's card has team and age null, or age from birth year where known. Sleeper positions are today's, a mild documented leak. Sleeper age comes from `birth_date`. "Steal passed on" excludes players the user drafted later. |
| F20 | LOW | The lineup overload takes players keyed by id, breaking ties by points then id. At the replay call site, an empty roster-week is 0 points, not `absent`. |
| F21 | LOW | US1 reworded: the drafted season's *stats* are hidden; the label is shown. |

## Open items (resolved)

- **O1 → Legacy scoring** (Allan, 2026-11-02).
- **O2 → count stats the league tracked at ≥ 90% coverage, gaps as 0, disclosed**
  (Allan). 1970-71 through 1982-83 keep reb and ast, 1979-80 on add 3PM, 1982-83
  adds blk, and 1983-84 on is complete.
- **O3 → measure after F15**, before deploy.

## Open items as first written (superseded by the section above)

- **O1 (Allan)**: which scoring set is the Sleeper default, Legacy or Ball Knowers
  bonuses? Legacy is the provisional choice.
- **O2 (Allan)**: the early-1970s era-rule outcome. Options (a)/(b)/(c) are in
  research.
- **O3**: production storage footprint.

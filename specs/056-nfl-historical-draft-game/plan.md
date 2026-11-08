# Implementation Plan: Football Time Machine

**Branch**: `056-nfl-historical-draft-game` (off `053-historical-draft-game`, PR #53 unmerged) | **Date**: 2026-11-05 | **Spec**: [spec.md](spec.md)

## Summary

This adds football to spec 053's "draft a random past season" game. The user chooses
Football, gets a random NFL season from 2000 to 2025, drafts QB/RB/WR/TE in a 12-team,
15-round plain snake against neutral bots ranked by last season's PPR per game, then the
real season replays week by week as best ball.

The engine, scoring, lineup, schedule, standings and comparisons are already
sport-neutral (research R6). The work is:
1. **V45**: a `sport` key on `historical_season`/`historical_game`, plus two NFL fact tables.
2. **An nflverse import**: 27 weekly CSVs, admin-triggered and async, through 053's job machinery.
3. **Generalizing 053's NBA-typed seams**: a `StatLine` interface and one
   `HistoricalSport` strategy per sport, so the six hardcoded `Sport.NBA` call sites read
   the game's sport.
4. **The web side**: a sport choice on the start page, football position filters and
   stat labels, "Week N", attribution and data notes.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5; TypeScript + React (Vite)

**Primary Dependencies**: existing only. `RestClient` for the download, 053's in-house
`KaggleCsv` reader (quoted-field CSV, enough for nflverse's files), Flyway, Jackson,
`java.util.zip.GZIPInputStream`, which reads the `.csv.gz` assets (about 1 MB each
instead of 8 MB).

**Storage**: Postgres. `V45__historical_nfl.sql` ([data-model.md](data-model.md)). About
27 seasons × 5.5k skill rows ≈ **150k week rows** (an estimate from 4 measured seasons),
small next to 053's 1.25M.

**Testing**: JUnit unit tests (board ordering, PPR keys, the importer parsing a fixture
CSV) and `*IT` against Postgres (V45 on a V43 DB, game flow, SC-008 regression). Vitest
for the web. Live verification in the browser pane.

**Target Platform**: Railway, the same as 053.

**Performance Goals**: SC-001. A replay is 12 teams × 15 players × 18 weeks plus the
hindsight search, which is smaller than NBA's.

**Constraints**: never download on a user request. Stream each file (`-Xmx1024m`).
Fail loudly on unexpected headers.

**Scale/Scope**: 26 offered seasons. The NBA game must be unchanged (SC-008).

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | PASS. A new V45. V43 is not edited, though unmerged, because it is applied on the 5438 scratch DB. The number is re-checked at build |
| `api.ts` mirrors Java | PASS by plan. `sport`, `dataNotes` and `attribution` are added in the same task as the records |
| No retuning a constant to match a guess | PASS. `rank-standard-games: 17`, `rank-min-games: 6`, the roster size and the expected game counts are new and labeled (game counts are schedule facts, the rest are hand-set) |
| No `Map.of` with nullables | PASS by plan. `team`, `age`, `prior`, `attribution`, `startDate` and `gameDate` are nullable, so mutable maps are used |
| Ask before committing | PASS. Commit, push and PR wait for Allan |
| A planning doc's difficulty isn't a spec | APPLIED. The guess was "copy the game". Reading the code found only 6 hardcoded sites plus two NBA-typed switches, and found that the schema keys collide (R6), which a copy would have hit at the first insert |

Re-checked after design: no violations.

## Project Structure

```text
backend/src/main/java/com/ballknowers/draftsim/historical/
├── StatLine, WeekStatLine            NEW interfaces (data-model "Java shapes")
├── HistoricalDataRepository          SeasonLine/GameLine implement them; the two switches move here;
│                                     sport-scoped season queries (offered, find)
├── HistoricalSport                   NEW interface; NbaHistoricalSport (wraps today's behaviour), NflHistoricalSport
├── NflHistoricalRepository           NEW: JDBC for the two NFL tables + NFL season rows
├── NflverseSource                    NEW: download .csv.gz per season, stream, map columns, aggregate season lines
├── HistoricalImportService           + part NFLVERSE, one transaction per season, report rows
├── HistoricalBoardBuilder            takes sport + counting keys + label fn; no Sport.NBA literals
├── SeasonReplay                      statMap reads WeekStatLine.stat(k)
├── HistoricalGameService             resolves HistoricalSport from the game's sport; create(…, sport, …)
├── HistoricalGameRepository          sport column on insert/select/summaries
├── HistoricalGameState               + sport, SeasonView.dataNotes/attribution, GameSummary.sport
├── StatOrder                         + NFL keys after the NBA ones
├── config/HistoricalProperties       + nfl: { teams, rounds, roster, scoring, rank-*, expected-games, url-template }
├── api/HistoricalGameController      sport required on create and seasons
backend/src/main/resources/
├── db/migration/V45__historical_nfl.sql
└── application.yml                   historical.nfl.* (hand-set values labelled)
web/src/
├── api.ts                            sport, dataNotes, attribution
├── pages/HistoricalDraft.tsx         sport choice (Basketball / Football) + sport tag on past games
├── pages/HistoricalRoom.tsx          sport from state; attribution + data notes; posPill sport
├── components/HistoricalPlayerList.tsx  filters per sport (QB RB WR TE)
├── components/HistoricalResults.tsx  "Week N" when startDate is null
└── historicalCopy.ts                 NFL stat labels; "no NFL stats last season"
```

## Key design decisions

1. **One source, nflverse, for every season** (R1). It has stable ids, so no identity layer.
2. **PPR includes special-teams TDs** (R2, measured at 100% agreement with the source's
   own PPR in 4 seasons). FR-003 is amended to match.
3. **Positions are today's listing, FB/HB → RB** (R3). This is a documented leak, the same
   one 053 accepted for its Sleeper seasons.
4. **Week = the source's week, no dates** (R4).
5. **Strategy, not fork** (R6). NBA code paths keep their exact arithmetic. The NBA switch
   bodies move verbatim, and the SC-008 regression IT proves it.
6. **`sport` is required in requests**, with no default (contracts).
7. **Data notes are computed at import and stored on the season row**, and snapshotted
   onto the game at creation like 053's disclosure (053 review finding 7), so a re-import
   never changes a finished game's text.

## Risks

- **Constraint names in V45** (data-model): verify them on a real V43 DB first.
- **nflverse republishing**: a re-import can change a season. Finished games are frozen.
  A game *in progress* during a re-import hits 053's "re-imported while drafting" guard,
  which is accepted.
- **The 2001 week numbering** is unmeasured (R4). T-measure covers it.
- **Rookies all go last** (R7, O1). This is a gameplay weakness that is bigger in football
  than in basketball.

## Open items for Allan

- **O1**: order rookies by their real NFL draft slot (nflverse `draft_picks`, CC-BY), or
  keep 053's "rookies last"? **Plan default: keep 053's rule.**
- **O2**: derive per-season positions from the stat profile (Pryor 2012 = QB) instead of
  today's listing? **Plan default: today's listing, documented.**

## Amended after review (2026-11-05)

[plan-review.md](plan-review.md) found 16 issues (5 HIGH, 5 MEDIUM, 6 LOW), and **all 16
are adopted**. Where this section disagrees with the text above, this section wins.
Allan decided O1, O2, F6 and F13 on 2026-11-05.

| # | Sev | Resolution |
|---|---|---|
| F1 | HIGH | **053 merged to main** (5a47471, closes PR #53). The branch was re-pointed at `origin/main` before any code (it had no commits). The PR targets main. **Deploy web before backend**, since today's backend ignores an unknown `sport` field, and the new web needs the new backend only for football. SC-008 covers real prod NBA games: count `historical_game` rows in prod before V45. |
| F2 | HIGH | V45 statement order, measured working: add both `sport` columns → drop `historical_game_season_start_fkey` → drop `historical_season_pkey` → add PK `(sport, season_start)` → add FK `(sport, season_start)` → swap `historical_season_source_check` and `historical_player_source_check` → drop not null. No CASCADE. |
| F3 | HIGH | Sport-scope four statements: `HistoricalDataRepository.upsertSeason:207` (`on conflict (sport, season_start)`), `HistoricalGameRepository.summaries:94` (join on both columns), `findSeason:230`, `offeredSeasons:241-253` (its prior-lines `exists` reads the sport's own line table). Add an IT with NBA 2005 + NFL 2005 rows: one list row per game, and an NBA re-import still upserts. |
| F4 | HIGH | `SeasonReplay:222` becomes null-safe on `l.date()`. A unit test replays an NFL week with null dates. The web shows "Week N" in the locked-game column for football, and the copy is per sport. |
| F5 | HIGH | SC-005 now compares **stats**, not points: nflverse season sums of the 8 main counting stats against Sleeper's, joined via Sleeper's player dump (`gsis_id`). The measured share is reported with no pre-committed 95%. The spec is amended. |
| F6 | MEDIUM | **Allan: points over replacement.** Ranking score = PPR/game × 17 − the score of the Nth-best qualifying veteran at that position on the same board, where N = teams × that position's dedicated starter slots (QB 12, RB 24, WR 24, TE 12). The FLEX is ignored. N is derived from the roster, and "dedicated slots only" is a hand-set choice, labeled. T-measure reports QBs per bot roster from a seeded auto-finish. |
| F7 | MEDIUM | Blank-id rows are team-total rows. A data note counts only blank-id rows with a skill position or a nonzero scored stat (expected 0). The raw count goes in the import report only. |
| F8 | MEDIUM | V45 adds `sport` with the default for backfill, then `drop default` on both tables. |
| F9 | MEDIUM | `readSnapshot` reads `dataNotes`/`attribution` with `has()` → empty/null. The SC-008 IT reopens a game whose board JSON is a fixture written in 053's shape. |
| F10 | MEDIUM | The extra NBA sites get their own task lines: controller `seasons:62`, the two `SETTINGS_SOURCE` copies, `label` in `prior`/`bestOfTwo`, `FIRST/LAST_OFFERED`, `create`'s top-level props, `SeasonDisclosure` copy, `historicalCopy` `STAT_ORDER`/`SHORT` and the `'SF'` default, the start-page copy, and the `HistoricalProperties` constructor in ITs. |
| F11 | LOW | The download reuses 053's `HttpClient` construction (follows redirects), and fetches `.csv.gz`. |
| F12 | LOW | Replay tests compare totals and lineup sets, not JSON order. `FootballRules` is untouched. |
| F13 | LOW | **Allan: INT −1** (Sleeper and his league). SC-004 recomputes with −2 to compare against the source's PPR column. |
| F14 | LOW | **Allan (O1): rookies by real NFL draft slot.** nflverse `draft_picks.csv` (CC-BY), joined on `gsis_id` (measured: every round 1–2 skill pick in 2000/2005/2012/2022 has an id, and 71/74 appear in that season's stats). A rookie drafted in NFL rounds 1–2 takes the score of the veteran at positional rank `rookie-positional-offset` (hand-set **12**) + his order among that position's round 1–2 rookies, or the last veteran if there are fewer. Later picks and undrafted rookies stay in the seeded tail. The card says "rookie, NFL pick #N". |
| F15 | LOW | SC-002 names 2022 = 271 (cancelled game). |
| F16 | LOW | Cards show `st_td`, `fum_lost` and the 2-pt keys as **season totals** when nonzero, alongside per-game for the yardage/TD/rec keys. |

**Config, amended at tasks (analyze I3)**: the NFL settings live in a separate
`NflHistoricalProperties` (prefix `historical-nfl`), not as an `nfl` component of
`HistoricalProperties`. That way 053's record constructor and its IT callers don't change.

**O2 → listed position, disclosed** (Allan). The draft screen says "positions are each
player's current listing".


## Amended 2026-11-06: migration renumbered V45 → V46

Spec 057 (commissioner score override, worktree peaceful-panini-70c6a7) also wrote a
`V45__week_score_override.sql`. **Allan decided that 057 keeps V45**, so this feature's
migration is now `V46__historical_nfl.sql`, and **057 must merge before 056**. Flyway refuses
an out-of-order V45 after V46 has been applied. V46 was free on every branch and worktree
(checked). Everywhere this folder says "V45" for this feature's migration, read V46. The
review and verification records keep the number they were written under.

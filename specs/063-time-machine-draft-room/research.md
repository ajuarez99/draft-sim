# Research: Time Machine draft room and playoffs

Each entry says whether it was **read** from code, **run**, or is an **assumption** still to be checked.

## R1. Reuse `useRevealedBoard` or write a new hook

- **Decision**: a new `usePickReveal` hook.
- **Rationale** (read, `web/src/useRevealedBoard.ts`): the existing hook reveals a predicted board from pick 0 each time its `resetKey` bumps, pauses on the user's picks, and exposes only `revealedThrough` / `pausedAt`. Time Machine needs to resume from the last response's end, reveal only the picks one response added, and derive an available list that puts unrevealed players back. The league simulator (`DraftView.tsx`) depends on the current hook's exact reset semantics.
- **Alternatives considered**: adding a "resume" mode to `useRevealedBoard` (touches a hook another room's correctness hangs on); pacing on the server with SSE (new failure modes, no gain: the picks are already decided).

## R2. Where the result is frozen, and what old results look like

- **Read**: `finishDraft` writes `write(result)` once (`HistoricalGameService.java:346-374`); `state()` returns the stored JSON as a `JsonNode`. Nothing re-derives a result on read.
- **Decision**: no migration and no backfill. New fields are appended to the `HistoricalResult` record; an old row simply lacks them.
- **Assumption to check in the build**: no code path deserialises a stored result back into `HistoricalResult` with `FAIL_ON_UNKNOWN_PROPERTIES` or relies on `userFinish` meaning standings rank other than the list SQL (`HistoricalGameRepository.java:106`). The plan review should grep for it.

## R3. Week counts per season

- **Read**: weeks are numbered only where a counted game exists (`HistoricalWeeks`), and stored per season as `week_count`. NFL: 17 weeks through 2020, 18 from 2021 (spec 056).
- **Run 2026-11-13** (T001) against a fresh import (KAGGLE, SLEEPER and NFLVERSE 2019-2021 parts) into a throwaway Postgres 14 database, `select sport, season_start, week_count from historical_season`:
  - NBA 1970-71 to 2025-26: 24 weeks (1970-71, 1971-72), 26 weeks (1978-79, 2017-18, 2018-19), otherwise 25, **except** 1998-99 = **14**, 2011-12 = **19**, 2019-20 = **21**, 2020-21 = **21**.
  - NFL 2019 = 17, 2020 = 17, 2021 = 18 (other NFL seasons not imported here).
  - So four NBA seasons take the shortened path: 1998-99 (11 regular weeks, exactly the one-round-robin floor), 2011-12 (16), 2019-20 (18), 2020-21 (18). No imported season falls into the no-playoffs arm. Normal seasons leave 2 to 4 real weeks unplayed after the final.
  - The spec's guess of "24 to 26 weeks" held; its short-season list was missing 2020-21, as the plan review suspected.
- **Superseded note, kept for the record**: the actual NBA `week_count` per season in the database was not run during planning. The spec's "three short seasons" and "24 to 26 weeks" are from memory of the NBA calendar, not from a query. **Task**: run `select season_start, week_count from historical_season where sport='NBA' order by 1` (table name to confirm) on the local DB and record the output here before the replay change is tested. If any other season has fewer than 22 weeks, it gets the shortened path automatically; the list in the spec's edge case is then corrected in place.

## R4. Seeding and reseeding rule

- **Decision**: seeds = regular-season standings order (wins + half ties, then points for, then the game's seeded coin). Round two reseeds: seed 1 plays the lowest surviving seed.
- **Rationale**: Allan's description ("top 2 teams get byes and then week by week eliminations") matches Sleeper's default six-team bracket, which reseeds. **Assumption**: not confirmed against his league's setting; it is stated in the spec's Assumptions so he can correct it.

## R5. Tie in a playoff game

- **Decision**: the better seed advances; the matchup is flagged `decidedBySeed`.
- **Rationale**: deterministic, needs no new random draw (keeps FR-019 trivially), and is the common platform rule. Alternative (bench points) needs bench scoring the replay does not compute.

## R6. Comparisons after the change

- **Read**: `Comparisons.compute(in, team, userPf)` takes the user's whole-season points for; `SeasonReplay` currently passes the standings PF because they are the same number.
- **Decision**: keep comparisons whole-season; compute a separate whole-season PF for them. A test asserts `hindsight.userPointsFor` equals the sum of the user's weekly best lineups over all `weekCount` weeks, not the regular-season PF.

## R7. Roster by slot

- **Read**: `web/src/teamNeeds.ts` `computeTeamNeeds` and `components/TeamStrip.tsx` already seat a roster into slots for both sports, pinned to the backend by a parity fixture (`lineup.parity.test.ts`).
- **Decision**: reuse `computeTeamNeeds`; draw a vertical panel. No second seating rule.
- **Assumption to check**: the seating sorts by `PlayerRef.adp`; `cardToPlayerRef` must give it the board rank. To confirm when wiring.

## R8. Reveal intervals

- **Decision**: 2000 / 1000 / 400 / 0 ms. **Hand-set guesses**, labelled as such in code. To be retuned by Allan after playing, not by anyone's measurement.

## R9. FantasyPros observations

- **Run** (2026-11-13, by hand in the browser): layout as described in the spec. Bot pick speed "21 picks in roughly 8 seconds" is an eyeball figure from screenshots 8 s apart, not a timing.

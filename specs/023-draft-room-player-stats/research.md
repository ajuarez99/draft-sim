# Research: Player stats in the draft room

All findings below were read from code or measured on 2026-10-08, in worktree `draft-sim-023` at
022's head 555592e, against the local DB on port 5433. Each item says which of those it was.

## R1. How the room finds its league

- **Finding (code)**: `SeatsResponse` (`web/src/api.ts:110`) carries `draftId`, `sport`, the
  seats and the roster shape, but **no league id**. The leaderboard route is
  `/api/leagues/{sleeperLeagueId}/stats`. On the backend, `LeagueController.seats()` already
  loads the `LeagueRow` (`api/LeagueController.java` ~line 131), and that row carries
  `sleeperId`.
- **Decision**: add `sleeperLeagueId` to the `seats` response. The value is `league.sleeperId()`,
  or null when the league row is missing, which the existing `Optional` handling already allows.
- **Rationale**: it's one field from a row that's already loaded, with no extra query. `seats` is
  already the room's single source of league-level facts (its comment says so).
- **Alternatives**: a new `/drafts/{id}/league` endpoint (an extra request, for one string), or
  resolving it through the leagues list on the client (fragile across the user's many leagues).

## R2. Which scoring the fantasy figures use

- **Finding (code)**: `PlayerStatsService.readLeaderboard` resolves the requested league to the
  latest season with stored games (`LeagueSeasonResolver.Rule.STORED_GAMES`). It then scores with
  `leagues.scoringOf(league.id())` (line 378), where `league` is the **resolved** one. For a
  2026 draft, that means 2025's league scoring, not 2026's.
- **Finding (measured)**: the NBA "Ball Knowers" leagues are id 210 (2026, `pre_draft`), id 211
  (2025, complete) and id 212 (2024). A `full join` of the 210 and 211 `scoring_json` keys
  returned **0 rows that differ**, and 2026 has 15 keys.
- **Decision**: keep 022's behaviour. Add `scoringSeason` (the season whose scoring was used) and
  `scoringMatchesRequested` (a boolean, or null when there was no fallback) to `StatLeaderboard`.
  The room names the scoring. If the boolean is false, it shows a one-line warning that this
  league's scoring changed since that season.
- **Rationale**: this gives identical numbers for the league that matters on 10-10, with no
  change to 022's verified scoring path. A league whose scoring changed is told, not silently
  misled.
- **Alternatives**: rescoring under the requested league's scoring. That's more correct in
  general, but it's a new path through the scorer, ranks and VOR, it would make the leaderboard
  and the room disagree, and FR-002 requires them to match. Deferred, and recorded as an
  amendment to the spec's FR-006 in plan.md.

## R3. Join key between the pool and the leaderboard

- **Finding (code)**: the room's pool is `PlayerRef` (`id: number`, internal, and
  `sleeperId: string`). The leaderboard's `LeaderboardRow.sleeperPlayerId` is a string.
- **Decision**: join on `PlayerRef.sleeperId === LeaderboardRow.sleeperPlayerId`, in a pure
  function `joinPoolStats(pool, rows)` in `draftRoomStats.ts`. The output has **exactly one row
  per pool player**, in pool order, before sorting.
- **Rationale**: Sleeper ids are the one id both sides share. A pool-driven join guarantees that
  the table's row count equals the board's undrafted count (spec edge case, "a player missing
  from the stats data").

## R4. Players with no stats (rookies and others)

- **Finding (code)**: leaderboard rows exist only for players with at least one line in the
  shown season (`lines.byPlayer()`), so rookies have no row. `PlayerRef` has no
  years-of-experience field, so the client cannot tell a rookie from a veteran who didn't play.
- **Decision**: a pool player with no leaderboard row gets `stats: null` and the reason
  `NO_SEASON_GAMES`. It renders as "No NBA games in 2025–26" across the stat cells (one spanning
  cell, not a zero per column) and sorts after every row with a value, whichever direction is
  chosen.
- **Rationale**: the sentence is true for rookies and for everyone else. Calling a player a
  "rookie" would need data the room doesn't have, and guessing would break the honesty rule. The
  spec's "Rookie: no NBA games yet" was an example wording, and this replaces it.
- **Alternatives**: adding `yearsExp` to `PlayerRef`. That touches the board, the engine records
  and `api.ts` for a label, so it isn't worth it before 10-10.

## R5. The "likely there at my next pick" threshold

- **Finding (code)**: `AvailabilityPanel.tsx:76-77` already defines `RISK_MAX = 0.35` and
  `SAFE_MIN = 0.65`. Below 0.35 the sheet's verdict calls a player at risk.
- **Decision**: "likely there" means survival to the next pick is **at least `RISK_MAX`**. That's
  the same boundary the sheet's own verdict uses, exported from one place. The table prints the
  rule: "35%+ chance he's there at pick 3.07".
- **Rationale**: it adds no new hand-set constant, and the filter and the verdict beside it can't
  disagree.

## R6. The pickable catalog

- **Finding (code)**: `statLeaderboard.ts` registers columns in five groups. Two concepts appear
  twice. `fgPct` (Basic) and `fgPctMA` (Shooting) are both FG%, and the same goes for `ftPct` and
  `ftPctMA`. `leagueRank` appears in both Fantasy and Draft value.
- **Decision**: export `PICKABLE`, the Basic, Shooting, Advanced and Fantasy groups with the
  Draft value group dropped. Within it, the Shooting versions (`fgPctMA`, `ftPctMA`, which show
  makes and attempts) replace the Basic `fgPct`/`ftPct`, so each stat appears once. `usg` (usage
  rate) is in Advanced, so the user's example is covered with no new work.
- **Default choice** (FR-009, mapped to ids): `gp, min, fp, pts, reb, ast, stl, blk, tpm, tov,
  fgPctMA, ftPctMA`.

## R7. Reusing the leaderboard's cells, not copying them

- **Finding (code)**: cell rendering (`Cell`, plus the number formats and reason text) lives
  inside `pages/StatLeaderboard.tsx:119`. It is not exported.
- **Decision**: move `Cell` and its helpers into `web/src/statCells.tsx`. Both the leaderboard
  page and the room import it. This is a pure move, and the leaderboard's existing tests must
  stay green unchanged.
- **Rationale**: FR-002 (the same value, shown the same way) is only guaranteed if there's one
  renderer. Two copies is the "two implementations of one rule" class from memory
  (multi-sport landmines).

## R8. Opening the player page without leaving the draft

- **Finding (code)**: the live room holds its connection in `useLiveDraft`, and navigating away
  in the same tab unmounts it.
- **Decision**: player names in the stats table open
  `/leagues/{sleeperLeagueId}/players/{sleeperId}` in a **new tab** (`target="_blank"`,
  `rel="noopener"`).
- **Rationale**: the room keeps running untouched (FR-013) with no new state persistence. A
  modal containing the player page would be nicer, but it's a bigger lift, and it's deferred.

## R9. Mock rooms (US3)

- **Finding (code)**: `MockSessionState`/`MockSessionSummary` carry an optional
  `sourceSleeperLeagueId` (V16). It's null for a mock started with no league. Mocks have no
  survival curves.
- **Decision**: a basketball mock with a source league gets the stats view against that league.
  One without a source league shows the switch disabled, with "Stats need a league; start the
  mock from a league to see them". The next-pick filter is never offered in a mock (FR-018).

## Not researched, on purpose

- **Leaderboard latency on the deployed backend.** It will be measured during verification
  (quickstart step V3), not guessed here.

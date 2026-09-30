# Implementation Plan: Superlatives full standings

**Branch**: `010-superlatives-full-standings` | **Date**: 2026-09-29 | **Spec**: `spec.md`
**Design**: `claude/superlatives-full-standings.md` (read that first)

## Technical Context

- **Backend**: Java 21 and Spring Boot 3.5. The change lives in
  `backend/src/main/java/com/ballknowers/draftsim/engine/SeasonSuperlativesService.java`,
  plus a new pure helper class alongside it.
- **Frontend**: React, TypeScript and Vite:
  - `web/src/pages/Superlatives.tsx`;
  - a new `web/src/components/SuperlativeStandingsModal.tsx`;
  - the hand-mirrored types in `web/src/api.ts`.
- **Storage**: no migration, and no new query. Everything is derived from rows the
  service already loads (amendment 1).
- **Constitution**: `.specify/memory/constitution.md` is still the unfilled template.
  AGENTS.md's hard rules stand in for it.
- **Testing**: JUnit (engine unit tests without Postgres, and `SuperlativesControllerIT`
  with Postgres), plus Vitest with Testing Library (`Superlatives.test.tsx`).

## Payload shape (added to `Superlative`)

```java
public record Standing(Integer rank, Holder team, Double value, String note, boolean hasValue,
                       String missingReason) {}
public record PlayerStanding(int rank, String playerId, String playerName, String position,
                             String team, int adds, int distinctTeams) {}
// Superlative gains: List<Standing> standings, List<PlayerStanding> playerStandings
```

- `rank` is `null` exactly when `hasValue` is false.
- `missingReason` is non-null exactly when `hasValue` is false.
- `note` is the per-row context line and may be null.
- `Holder` is the existing record, and it gets its `username` from `withUsernames`, the
  same way `holders` does.

## Amended after checking the code (2026-09-29)

These are corrections to `claude/superlatives-full-standings.md`, found while turning it
into tasks:

1. **The four record kinds: the design doc was right, and this amendment's first version
   was wrong.**

   > **Corrected 2026-09-29 by `/speckit-analyze` (finding F1).** The first version of
   > this amendment claimed that building standings from the loaded `leagueBreakdowns`
   > and `games` "could put a team at rank 1 that isn't the card's winner", because the
   > winners' queries aren't filtered to `scoredWeeks`. That was never run, and **it is
   > false by construction**:
   > - `scoredWeeks` is the stored weeks before `playoffWeekStart`, or all stored weeks
   >   when there's no playoff format (`SeasonSuperlativesService.java:205-211`).
   > - `bound = WeekBound.through(max(scoredWeeks))` (`:225-229`). So every stored week at
   >   or below the bound is already in `scoredWeeks`, and the filter removes nothing the
   >   bound keeps.
   > - `leagueBreakdowns` comes from `breakdownsFor`: the same `roster_week_points` rows,
   >   with no extra filter (`RosterWeekPointsRepository.java:129-140`).
   > - `games` is `matchups.pairedWithScores(…, bound)`, the same call `LeagueRecordService`
   >   makes for the winner.
   >
   > The first version was a guess stated as fact, which AGENTS.md's hard rules name
   > explicitly. It's kept visible here, not deleted.

   **Decision:** build the week-score standings from `leagueBreakdowns` and the margin
   standings from `games`. No extra query. `SuperlativesControllerIT` (T017) asserts that
   rank 1 equals `holders` on real data, which would catch the two sources ever drifting
   apart (for example, if someone changes how `scoredWeeks` is filtered).
2. **CLOSEST_GAME's holders are the game's *winner* only** (`marginSuperlative` maps
   `r.winner()`). The design doc's "rank-1 rows equal holders" invariant fails for it if
   teams are ranked by their closest game either side. The spec keeps "either side",
   because it's the natural reading of "where did everyone rank", and weakens the
   invariant to `holders ⊆ rank-1` for this one kind.
3. **`Holder` gained a `username` field on main** (filled after building by
   `withUsernames`). `withUsernames` must map the standings rows too, or every row after
   rank 1 has no username.
4. **JOEL_EMBIID: absent does not always mean zero** (added by `/speckit-analyze`, finding
   U1). `absenceSuperlative` tracks, per roster, the weeks where a player's absence
   couldn't be classified (`unclassifiedWeeksByRoster`, `:851-858`). Today it reports
   those only for the winners (`unclassifiedCoverage(topRosters, …)`, `:888`). A
   non-winner's figure can therefore be an undercount with no caveat.
   - **Decision:** every Embiid row carries its own unclassified-week count in `note`
     (`"N weeks couldn't be classified as a bye or a missed game"`, or `"1 week …"` when N is 1, matching `unclassifiedCoverage` at `:914-921`).
   - A roster missing from `byRoster` **with** unclassified weeks is still `hasValue=true`
     and `value=0`, because 0 is what was counted. Its note says what wasn't counted.
     This matches how the card treats the winner.
5. **Formatting follows the card, by kind** (finding I1). The card doesn't use one
   formatter:
   - CLOSE_WINS / CLOSE_LOSSES go through `formatCloseGameCount` plus "by under N points";
   - everything else goes through `formatValue(value, unit)`.

   The modal takes a `formatValue: (v: number) => string` prop that the card builds with
   **the same branch it uses for its own value line**, so the two can't disagree.

## Amended after the adversarial plan review (T000, 2026-09-29)

The findings are in `plan-review.md`. R1 and R2 were re-checked by the parent session in the
code before being accepted.

6. **R1 – the controller builds the JSON by hand.**
   `SuperlativesController.superlativeRow` (`:206-226`) copies fields one at a time. It gets
   `standingRow`, `playerStandingRow` and `holderRow` reuse, built as `LinkedHashMap`s because
   `rank`, `value`, `note` and `missingReason` are nullable (the `Map.of` null trap). **The IT
   asserts on the controller's response, not on the service result.**
7. **R2 – the roster universe is `roster_season`, not `nameByRoster`.** `teamMaps` skips a
   roster with no name (`:1221`), for example an orphaned roster whose manager left. The
   universe is `managerByRoster.keySet()`, which `teamMaps` fills for every `roster_season`
   row, null manager included. Names come from `holder(...)`, which already falls back to
   `"Roster N"`.
8. **R3 – a new IT seeds its own season.** `SuperlativesControllerIT` never GETs superlatives
   on a scored season. A new `SuperlativesStandingsIT` seeds a small synthetic season: 4
   rosters, **one orphaned** (`manager_id` null), 3 regular-season weeks of scores and
   pairings, and one tied score. It asserts the invariants on the controller response.
   Local data has no orphans, so only a seeded fixture exercises R2.
9. **R4 – a winner that isn't ahead of anyone.** WAIVER_WIRE_WARRIOR and JOEL_EMBIID pick
   winners only from rosters in their map.
   - **Rule:** absent rosters count as a real 0. **If the map's best value is ≤ 0, the kind
     returns its empty state** instead of crowning a team that is level with, or behind, teams
     that did nothing.
     - Waiver: `"no started pickup has scored yet"`.
     - Embiid: `"no absence has cost anyone points yet"`.
   - This **changes the existing card** in that corner case. It is recorded as a behaviour
     change, and it makes the card less certain, not more.
   - With it, rank 1 = holders holds for both kinds.
10. **R5 – one figure formatter per kind, shared.** "The card's own value line" exists for only
    some kinds. A new `standingFigure(kind, unit, value)` in `Superlatives.tsx` defines the
    modal figure per kind:
    - close-game kinds use `formatCloseGameCount`;
    - LUCKIEST / UNLUCKIEST use a signed `"+1.30 wins vs expected"` / `"−1.30 wins vs
      expected"`;
    - every other kind uses `formatValue(value, unit)`.

    Amendment 5 is superseded by this.
11. **R6 – the "your row" highlight is dropped.** `BkUser.username` (the Sleeper handle) and
    `Holder.username` (`manager.display_name`) aren't the same field. The app decides "me" on
    the backend (`isMe` in `LeagueAnalysisService`), and threading the caller into
    `forLeague` is out of scope for an unrequested nicety. FR-009 is removed.
12. **R7 / R8 – the modal is opened by a real button, and the modal state lives in the card.**
    - Each openable card gets a `<button className="sl-see-all">See all</button>` in its
      header. That is the keyboard and screen-reader path.
    - A click anywhere else on the card (mouse only) also opens it, except clicks inside
      `<details>`, which stop propagation.
    - The `<article>` gets **no** `role="button"`.
    - `SuperlativeCard` owns `const [open, setOpen]` and renders the modal itself, so the
      formatter is local.
    - The modal card calls `e.stopPropagation()` (as `StartMockModal.tsx:104` does), and adds
      `bk-modal-fullscreen` to `body` while open on phones (the PowerRankings fix,
      `PowerRankings.tsx:420`).
13. **Low findings folded in:**
    - **R9:** margins are subtracted in `BigDecimal`.
    - **R10:** amendment 1's wording now mentions the `season =` filter.
    - **R11:** rows whose roster isn't in the universe are skipped, with a comment.
    - **R12:** close-game count kinds give a roster that appears in **no** paired game
      `hasValue=false`, `"no games yet"`.
    - **R13:** per-roster undercounts other than Embiid's unclassified weeks are covered only
      by the kind's league-wide `coverage` line, which the modal shows. This is a known limit.
    - **R14:**
      - The luck builder is passed the name maps.
      - The luck note is `"{actual} actual vs {expected:0.00} expected"`.
      - A 0-margin CLOSEST_GAME note reads `"tied with {opponent} · week N"`.
      - `top()` applies `MIN_ADDS_TO_NAME` itself.
      - The Jabari card is openable when `playerStandings` is non-empty.
      - The IT also checks Jabari when it has data.

Amendment 1 detail (R10): `breakdownsFor` also filters `season = league.season()`. A league row
is one season, so the filter removes nothing: 0 mismatched rows were measured locally by the
reviewer.

## Risks

- **A defaulted field that encodes a rule.** `Superlative`'s shorter constructors will
  default `standings` to `[]`. That's right for unavailable and empty kinds, and silently
  wrong if a builder forgets to pass it. `SuperlativesControllerIT` guards against it: it
  asserts that every available kind with holders has `standings.size() == rosterCount`
  (see the memory note "optional params that encode rules").
- **Sign and direction** (recurring bug class #1). Every direction gets an ordering
  assertion, not just a count assertion.

# Implementation Plan: Hall of fame (trophy case and discovered rivalries)

**Branch**: `047-hall-of-fame` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/047-hall-of-fame/spec.md`. Research: [research.md](research.md).

## Summary

> **Amended after review (2026-10-24):** see "Amended after review" (A1–A16) at the end, which wins
> over the text here. Most visibly:
> - last place **is** built, with a refusal rule;
> - this adds migration **V40**, so "no migration" below is superseded.

- **Trophy case.** One row per completed season of a league chain:
  - the champion, by the existing rule, now made into one shared helper;
  - the runner-up, as the loser of the stored winners-bracket final;
  - the regular-season points leader(s), from Sleeper's `points_for`.

  Last place is **not shown**. That was measured, and the spec is amended (research R2).
- **Rivalry discovery.** Most-played, closest, most lopsided and playoff meetings, as a new `discovery`
  block on the existing Rivalries response. It's computed from the same pair records as the matrix.
- **Nemesis and victim per chain.** From a new manager endpoint that reuses spec 034's per-chain game
  list and pair calculation, split out of `RivalryService.forLeague`. It includes former opponents.
- **Past award winners.** Fetched lazily from the existing superlatives endpoint.

There's no migration and no new hand-set constant.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)

**Primary Dependencies**: existing only (Spring `JdbcClient`, React Router)

**Storage**: Postgres. Reads only:

- `league`
- `roster_season`
- the scored games via `LeagueMatchupRepository.pairedWithScores`
- `league_bracket_match` (V33)

**No migration**. The highest on main is V39.

**Testing**: JUnit 5 unit tests and `*IT` against local Postgres; vitest for the web.

**Target Platform**: Railway (two services); local dev on Windows.

**Project Type**: web application (backend + web)

**Performance Goals**:

- Trophy case: one standings read per chain league, plus one bracket read and one chain-games pass.
- Rivals: at most 3 `pairedWithScores` per chain, the same cost as one Rivalries load.

**Constraints**:

- One implementation per rule: the champion rule, the disputed-final check, and N.
- Every claim prints its count.
- Both sports, never mixed.

**Scale/Scope**: chains of 1–3 seasons, 12–14 managers, about 6 real chains.

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | N/A. There's no migration. |
| `api.ts` mirrors Java records | **Applies.** There are three new wire shapes (`TrophyCase`, `RivalryDiscovery`, `ManagerRivals`). Their TS types land in the same task as the Java records. |
| No retuning of hand-set constants | Holds. N is reused (`rivalry.min-meetings: 3`) and unchanged. The research R9 rules are definitions, not tuned weights. |
| `Map.of` null hazard | **Applies.** The runner-up and the notes are nullable. These responses are records only, with no `Map.of`. |
| Ask before committing | Holds. |
| Planning doc ≠ spec | Applied. The doc's "last = highest `final_placement`" and "add the split to `HeadToHeadService`" were checked, and both are wrong today (spec Background, research R2/R5). |

**Gate: PASS.** Re-checked after the design below: still PASS.

## Design

### Backend

1. **`engine/ChampionRule`** (new, static): `isChampion(Integer finalPlacement, boolean seasonComplete)`.
   - It replaces the inline checks at `LeagueHistoryController.java:318` and `ManagerCareerService.java:203`.
   - Behaviour is identical, and the before/after capture checks it (SC-006).
2. **Split `RivalryService.forLeague`** (`RivalryService.java:200-336`) without changing its output:
   - `chainGames(List<LeagueRow> chain)`: formats, brackets, the `pairedWithScores` + `classify` loop
     (`:229-247`), `managersOfScoredLeague`, `byPair`;
   - `pairCalc` stays the one calculation, callable for any pair in the chain.

   `forLeague` keeps restricting the matrix to current managers. It's guarded by the existing rivalry
   tests plus the before/after `/rivalries` capture, which must be identical apart from the new
   `discovery` field.
3. **Discovery** (US2): built inside `forLeague` from the matrix's own `calc` map, so the population
   is current managers (research R5). It's added as `Response.discovery`. The ranking follows research
   R9 exactly.
   - **Playoff meetings** are pairs with a nonzero `PLAYOFF` `byKind`, each with its `finals`.
   - PLACEMENT and CONSOLATION games are never called "playoffs".
4. **`engine/HallOfFameService`** (new).

   **`trophyCase(sleeperLeagueId)`**: for each `complete()` row of `chainBySleeperId`, newest first:
   - The **champion** is the roster where `ChampionRule.isChampion` holds.
   - The **runner-up** is the loser of the `placement == 1` bracket match. It's null with
     `runnerUpUnavailable` when there's no bracket.
   - **`sourcesDisagree`** is true when the bracket's winner ≠ the champion's roster.
   - **`finalDisputed`** is true when the champion-vs-runner-up pair's
     `Final.scoresAgreeWithWinner == false`, using `chainGames` + `pairCalc` (research R8, one
     implementation).
   - The **points leaders** are max `points_for`, ties included.

   The response also carries the fixed `lastPlaceNote` (research R2).

   **`rivals(managerId)`**: for each chain the manager owns a roster in (chain head found via the
   forward step at `LeagueRepository.java:459`, deduplicated by head, the approach
   `ManagerCareerService.java:295` uses):
   - take every opponent in that chain's `chainGames`, current and former;
   - compute `pairCalc`, then nemesis and victim (research R9);
   - when no opponent qualifies, return `status: NONE_QUALIFY` with `minMeetings`.
5. **`api/HallOfFameController`** (new):
   - `GET /api/leagues/{sleeperId}/trophy-case`
   - `GET /api/managers/{managerId}/rivals`

   Both use the same league-scope checks their sibling endpoints use (`/rivalries` and
   `/managers/{id}/history`), and the same 404 shape.

### Web

6. `api.ts`: the `TrophyCase`, `RivalryDiscovery` (and `Rivalries.discovery`) and `ManagerRivals`
   types, plus `getTrophyCase` and `getManagerRivals`.
7. `components/TrophyCase.tsx`, mounted as a "Hall of fame" section in `LeagueHistory.tsx`:
   - a table of seasons × roles;
   - the disagreement and disputed notes. The disputed sentence reuses `finalDisagreeText` from
     `Rivalries.tsx`, moved to a shared module (one wording);
   - `lastPlaceNote`, once;
   - a per-row "Awards" toggle that lazily calls the existing superlatives fetcher for that season and
     lists each award's winner(s) as that response names them (US4, research R7).
8. `Rivalries.tsx`: a "Notable rivalries" block above the matrix. It has four short lists, each entry
   with its record, meeting count and versus link, plus N labelled hand-set.
9. `ManagerHistory.tsx`: a "Rivals" block per chain, with nemesis and victim (or the "no opponent met
   N times" line) and versus links.

Styling follows the existing card and tint conventions (memory: avoid flat uniform cards), and the
layout follows the content: a table for the trophy case, short lists for discovery and rivals.

## Scope adjustments recorded here

- **No last-place column** (spec FR-004 amended; research R2).
- **Discovery covers current managers only** (research R5). Former managers appear through US3.
- The trophy case goes on History as the doc says. Discovery goes on Rivalries, which didn't exist when
  the doc was written.

## Project Structure

### Documentation (this feature)

```text
specs/047-hall-of-fame/
  spec.md  plan.md  research.md  data-model.md  quickstart.md
  contracts/api.md
  checklists/requirements.md
  later: plan-review.md  tasks.md  code-review.md  verification.md  baseline/
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
  engine/ChampionRule.java           new
  engine/HallOfFameService.java      new
  engine/RivalryService.java         split chainGames; add discovery
  engine/ManagerCareerService.java   use ChampionRule
  api/LeagueHistoryController.java   use ChampionRule
  api/HallOfFameController.java      new
backend/src/test/java/com/ballknowers/draftsim/
  engine/ChampionRuleTest  engine/RivalryDiscoveryTest  engine/HallOfFameServiceIT
web/src/
  api.ts  pages/LeagueHistory.tsx  pages/Rivalries.tsx  pages/ManagerHistory.tsx
  components/TrophyCase.tsx (new)  rivalryText.ts (shared finalDisagreeText)
```

**Structure Decision**: the existing web-application layout, with new code beside spec 034's.

## Amended after review (2026-10-24, plan-review.md F1–F17)

These amendments **override the Design section above** wherever the two differ.

- **A1 (F1, Allan's call): last place is built, and refuses on doubt.** This adds:
  - migration **V40** `league_losers_bracket_match` (same columns as V33; V40 is free on every local
    branch, checked 2026-10-24);
  - `SleeperClient.losersBracket`;
  - `BracketIngestService` writing both brackets in its one write path, including `backfillChain`;
  - the refusal rule in research R2 (amended). It lives in `engine/LastPlaceRule` and is called by
    `HallOfFameService`.

  `TrophySeason` gains `lastPlace: ManagerRef | null` plus `lastPlaceReason`. The section-level
  `lastPlaceNote` is removed. **Post-deploy:** run the bracket backfill per chain (no `/ingest/all`
  needed) so complete seasons get their losers bracket.
- **A2 (F2): most-played needs a floor.** It considers only `meetings >= N`, the same as the other
  claims. Below that, the list is empty with "no pair has met N times yet".
- **A3 (F3): lopsided needs a real gap.** It's shown only when its |W−L| is ≥ 2 **and** greater than
  the closest tier's |W−L|. Otherwise: "no pair is one-sided yet". Any tie list longer than 3 renders
  as a count with an expander (e.g. "16 pairs are level at 2-2"), so every entry is still reachable
  (FR-010).

  Expected outputs on today's data go into quickstart:
  - FBK lopsided is empty (7 × 2-1);
  - NBA closest is 16 pairs at 2-2;
  - fantasy😍 has no qualifying pairs at all.
- **A4 (F4, F6): rivals scope and chain identity.**
  - Chains are restricted to `LeagueMembership.leagueIdsFor(caller)` (admin sees all), and a chain
    with no visible season is omitted.
  - The chain is the manager's newest **visible** season, then `chainBySleeperId` backward. That's
    the same walk careers and membership use, with **no forward step**.
  - Chains are keyed by head sleeper id and grouped under each sport's career block, with one
    sub-block per chain (manager 7 has three NFL chains).
  - `former` = no roster in that head season.
  - A new IT checks that a caller sharing one league sees only that chain.
  - History's own careers scope is unchanged and out of scope.
- **A5 (F5): contract C1 is corrected.** It returns the chain from this season backward. The rail
  links the newest season. There's no forward walk.
- **A6 (F7): runner-up when the sources disagree.**
  - `runnerUpUnavailable: boolean` becomes `runnerUpReason: NO_BRACKET | SOURCES_DISAGREE | NO_LOSER
    | null`.
  - When the bracket's p=1 winner ≠ the stored champion, the runner-up is null with
    `SOURCES_DISAGREE`, and the note reads: "Sleeper's bracket and its season result name different
    champions; the season result is shown."
  - If the champion is null, the row says "no champion reported" and shows no runner-up. The bracket
    winner is never promoted.
- **A7 (F8): where "final disputed" comes from.** It's read from `Final.scoresAgreeWithWinner` of the
  `pairCalc` for the **bracket p=1 match's two rosters' managers**, so no third copy of the check is
  written. The wording keeps the champion authoritative: "The stored game scores differ from
  Sleeper's result." Expected today: both NBA rows show it (215 vs 248, 199 vs 234), and that's not a
  bug.
- **A8 (F9): playoff meetings.**
  - `DiscoveredPair` carries `playoffMatches` (from `PairStats.playoffMeetings`, per match, not per
    week), and the UI prints that.
  - If any chain season has a split note, the playoff list's empty and partial states name those
    seasons ("can't tell for 2025: the bracket isn't stored"). It never says "no playoff meetings".
- **A9 (F10): US4 awards.**
  - When the response's `requestedSeason != null`, or its league id isn't the row's, the row shows
    "awards aren't available for this season" and none of the returned awards.
  - The winner-line rendering is extracted from `Superlatives.tsx:209-360` into a shared
    `AwardWinners` component, used by both pages, with a vitest per state:
    - player holders;
    - empty with `emptyReason`;
    - tied holders;
    - `available: false`, at both the response level and the kind level.
  - Each expanded row caches its response.
- **A10 (F11): the career `counted` gate.** The career-titles gate (`ManagerCareerService.java:185`)
  stays. It's a counting rule for careers, not a second champion rule. An IT asserts that for every
  manager, `titles` = the number of trophy-case champion rows over counted seasons.
- **A11 (F12): deploy skew.**
  - `Rivalries.discovery?` is optional in api.ts, and nothing renders when it's absent.
  - The new fetchers map a 404 to "section not shown" (hidden), not to a page error.
- **A12 (F13): ordering tests.** These are required in `RivalryDiscoveryTest` and the rivals test:
  - lopsided: 4-0 > 3-1, and 3-0 > 5-2 by share;
  - closest: 2-2 > 3-2, and 3-2 > 2-1 by meetings;
  - nemesis: computed for a manager who is `hi` in every pair, with 0-3 above 1-3 and a 2-1 opponent
    never named;
  - victim: the mirror;
  - the A2/A3 floors.

  `LastPlaceRule` gets one test per refusal reason, plus the four measured seasons as fixtures.
- **A13 (F14): naming.** The service is `HallOfFameService` with `trophyCase()` and `rivals()`.
  "RivalsService" in research R6 is superseded.
- **A14 (F15): nullable `managerId`.** `ManagerRef.managerId` is nullable (an orphan roster), and
  such a name has no link. Records only, no `Map.of`.
- **A15 (F16): stale sentence.** The `playoffAppearances` reason text (`ManagerCareerService:126-128`)
  is updated: "the bracket is stored, but appearances aren't derived from it yet".
- **A16 (F17): frozen captures.** Both the before and after captures run against the same scratch-DB
  snapshot (docker, a free port), with the poller and the daily refresh off.

## Complexity Tracking

No constitution violations to justify.

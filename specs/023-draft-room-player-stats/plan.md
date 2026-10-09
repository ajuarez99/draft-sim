# Implementation Plan: Player stats in the draft room, with stats you choose

**Branch**: `023-draft-room-player-stats` (worktree `draft-sim-023`, branched from `022-player-stat-analysis` at 555592e) | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/023-draft-room-player-stats/spec.md`

## Summary

The live draft room's available-players sheet gets a second view, a **stats table**, alongside the
tier list. It reads spec 022's existing leaderboard endpoint once per window, joins its rows to the
room's undrafted pool by Sleeper player id, and renders the columns the member picked in a **stat
picker modal**. The choice is stored in `localStorage` the way the room's sound and pick-card
preferences already are.

Almost all of this is frontend work. The backend needs two small additions:

- the draft room learns its league's Sleeper id (`seats` gains `sleeperLeagueId`);
- the leaderboard says whether the scoring it used is the requested season's (`scoringSeason`,
  `scoringMatchesRequested`), so the room can name the scoring honestly.

There is no new stat math, no schema migration and no new endpoint.

**Deadline**: the "Ball Knowers" NBA 2026 draft starts **2026-10-10 19:15 UTC** (measured from
the local `draft` row: 12 teams, 14 rounds, `pre_draft`). Build order is US1, then US2, then US3.
Each story merges only after live verification (spec, Clarifications 2026-10-08).

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5) backend, TypeScript and React (Vite) frontend. Both
are unchanged from 022.

**Primary Dependencies**: existing only. These are 022's `PlayerStatsService.readLeaderboard`,
`statLeaderboard.ts` (columns, sort, filters) and `statCopy.ts` (reason sentences), plus the
room's `AvailabilityPanel`, `useLiveDraft` and `pickCardsPref` pattern.

**Storage**: there are no schema changes. The stat choice lives in browser `localStorage` under
one versioned key.

**Testing**: Vitest and Testing Library for the frontend, JUnit with the existing ITs for the two
backend fields, then live verification against a real backend and a real browser. A replayed
2025 NBA draft stands in for "live" until 10-10.

**Target Platform**: the web app, desktop and phone (375 px).

**Project Type**: web application (`backend/` and `web/`).

**Performance Goals**: the stats view adds **one** leaderboard request when it is first opened,
and one more per window change. A landed pick re-filters the rows the client already holds, with
no request. There's no added work on the pick-announcement or re-simulation path (SC-006).

**Constraints**:
- Every value must be byte-for-byte the leaderboard's (FR-002), so the room reuses 022's column
  definitions and cell formatting. It does not copy them.
- Frontend and backend deploy independently (memory: "new frontend, old backend" took the home
  page down on 2026-09-14). The room must cope with a backend whose `seats` lacks
  `sleeperLeagueId`, by hiding the stats view with a reason, never by throwing.

**Scale/Scope**: about 582 leaderboard rows for 2025 (022 verification.md), and an undrafted pool
of a few hundred players. Client-side join and sort at this size is trivial.

## Constitution Check

*GATE: must pass before Phase 0 research, and is re-checked after Phase 1 design.*

| Rule | How this plan meets it |
|---|---|
| Migrations append-only | No migration. |
| `api.ts` mirrors Java records in the same change | `SeatsResponse.sleeperLeagueId` and `StatLeaderboard.scoringSeason` / `scoringMatchesRequested` are added to `api.ts` in the same commit as the Java change (contracts/api.md). Both are optional on the TS side, for the split-deploy reason above. |
| Don't retune hand-set constants | No new constant. The "likely there" filter reuses the sheet's existing `RISK_MAX = 0.35` boundary (research R5), so there's one rule and not two. |
| `Map.of` with nullable values | `seats` already builds a `LinkedHashMap`. `sleeperLeagueId` comes from a non-null column but goes in the same map anyway. The leaderboard is a record, not a map. |
| Ask before committing | No commits without asking. Merge and deploy are confirmed separately (spec Clarifications). |
| Planning docs aren't verified specs | The spec's FR-006 assumption about scoring was checked against code and data, and it was wrong in one respect. The spec is amended visibly (research R2). |

Result: **PASS**. There are no violations and nothing in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/023-draft-room-player-stats/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/api.md     # Phase 1
├── checklists/requirements.md
└── tasks.md             # produced by /speckit-tasks, not by this command
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── api/LeagueController.java          # seats(): + sleeperLeagueId
└── engine/PlayerStatsService.java     # StatLeaderboard: + scoringSeason, scoringMatchesRequested

web/src/
├── api.ts                             # mirror the three fields (optional)
├── statLeaderboard.ts                 # + PICKABLE catalog (deduped), + sort wrapper for missing rows
├── statCells.tsx                      # NEW: Cell/format pulled out of pages/StatLeaderboard.tsx, shared
├── statChoice.ts                      # NEW: read/write/reset the stored choice (pickCardsPref pattern)
├── draftRoomStats.ts                  # NEW: pure join of pool × leaderboard rows → DraftStatRow[]
├── components/DraftStatsTable.tsx     # NEW: the stats view (pinned name, sideways scroll)
├── components/StatPickerModal.tsx     # NEW: the picker (StartMockModal's dialog pattern)
├── components/AvailabilityPanel.tsx   # + "Tiers | Stats" switch, hosts DraftStatsTable
├── pages/StatLeaderboard.tsx          # imports Cell from statCells.tsx (no behaviour change)
├── pages/LiveDraftView.tsx            # passes sleeperLeagueId + survival to the panel
└── pages/MockDraftView.tsx            # US3: passes sourceSleeperLeagueId, no survival
```

**Structure decision**: this is the existing web-app layout. The new frontend pieces are split so
that the parts with rules in them (the join, the stored choice, the catalog) are plain `.ts`
modules with unit tests, and the components stay thin. That's the same split 022 used for
`statLeaderboard.ts`.

## Phase 0 and Phase 1 outputs

- [research.md](research.md): R1–R9. The decisions on the league id, the scoring finding, the join
  key, missing players, the threshold, the catalog, reuse, the player link and mock rooms.
- [data-model.md](data-model.md): `StatChoice`, `DraftStatRow`, `ShownSeason`.
- [contracts/api.md](contracts/api.md): the three added wire fields and the `localStorage` key.
- [quickstart.md](quickstart.md): how to verify each story live, including the replayed-draft
  setup.

## Spec amendment made during planning (shown, not hidden)

**FR-006 amended after planning, 2026-10-08.** The spec said fantasy figures default to "the
drafting league's current scoring applied to last season's games". The code doesn't do that.
When 022's leaderboard falls back a season, it scores with **the fallback season's** league
(`PlayerStatsService.java:378`, `scoringOf(league.id())` where `league` is the resolved,
fallback league).

Changing that would mean a second code path through 022's scorer two days before a draft.
Measured on 2026-10-08 in the local DB, the 2025 and 2026 "Ball Knowers" NBA leagues have
**identical** scoring: 15 keys and zero differences. So for this league the two readings give
the same numbers.

The plan keeps 022's behaviour, names the scoring season in the view, and adds
`scoringMatchesRequested`. That way a league whose scoring *did* change gets a visible warning
rather than silently wrong fantasy figures. The amended FR-006 reads: "Fantasy figures MUST be
computed under one stated scoring, and the view MUST name it. If it differs from the drafting
league's current scoring, the view MUST say so."

## Complexity Tracking

None.

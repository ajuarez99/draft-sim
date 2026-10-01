# Build notes: spec 013

Measured values recorded during the build. Each entry says whether it was
**measured** (run or observed) or **chosen** (a hand-set value, with reason).

## T001 baseline (measured, 2026-09-30, worktree @ 5b3f446)

| Suite | Result |
|---|---|
| `web`: `npx tsc -b` | OK |
| `web`: `npx vitest run` | 66 files, 806 tests, 806 passed |
| `web`: `npm run build` | OK, main chunk 442.02 kB (132.91 kB gzip) |
| `backend`: `./gradlew test` | 945 tests, 0 failures, 0 errors, **0 skipped** (Postgres on 5433 was up) |

## T002 data (measured)

Explicit re-ingest was refused locally (`403 admin_token_required`; `ADMIN_TOKEN` is blank in
local dev by design). Not needed: the local DB already matches production for the NFL league:
expected-wins `weeksScored: 3`, weekly-report default `week: 2` with `latestScoredWeek: 3`
(identical to prod, research R2). NFL and NBA history both have 12-row standings for 2026.
Unlike prod, the local NFL league *has* a stored season forecast.

## T004 baseline depth (measured, harness `measure.js`, 1440×900, before any change)

`depth` = worst count of bordered/filled containers above a text leaf, outside the board.
`boardDepth` = same, inside the draft board. Harness limits are under review (adversarial review, quickstart §4a).

| Route | depth | worst at | boardDepth | h-scroll |
|---|---|---|---|---|
| / | 3 | "Draft complete" | – | no |
| /managers | 2 | manager row | – | no |
| /mock/new | 2 | seat summary | – | no |
| /mock/653 | 3 | pick feed "2.11" | 3 | no |
| /drafts/{nba} (sim, pre-start) | 0 | – | 3 | no |
| /drafts/{nba}/live | 2 | "Waiting for the draft to start" | 3 | no |
| /drafts/{nfl}/board | 1 | "Draft board" | 2 | no |
| NFL history | 3 | a PF cell | – | no |
| NFL power | 2 | ladder header | – | no |
| NFL analysis | 2 | team row | – | no |
| NFL roster-management | 3 | "left on the bench" | – | no |
| NFL expected-wins | 3 | luck value | – | no |
| NFL forecast | 3 | win range | – | no |
| NFL weekly-report | 3 | matchup team | – | no |
| NFL superlatives | 1 | section header | – | no |
| NBA history | 3 | a PF cell | – | no |
| NBA power | 1 | "The ladder" | – | no |
| NBA weekly-report | 3 | matchup team | – | no |
| NBA superlatives | 2 | season label | – | no |

## T004 re-baseline with the amended harness (measured, 1440×900, before any change)

The harness was amended after adversarial review (B4). **The table above used the old
harness and is superseded by this one.**

| Route | depth | worst at | gridDepth | h-scroll |
|---|---|---|---|---|
| / | 3 | league card status line | 0 | no |
| /managers | 1 | intro text | 0 | no |
| /mock/new | 2 | seat chip | 0 | no |
| /mock/653 | 2 | "You" seat | 1 | no |
| NBA sim (pre-start) | 2 | "▾ Players" | 1 | no |
| NBA live | 2 | availability heading | 2 | no |
| NFL completed board | 1 | header | 1 | no |
| NFL history | 3 | a manager name | 1 | no |
| NFL power | 2 | segmented control | 0 | no |
| NFL analysis | 3 | a disclosure | 1 | no |
| NFL roster-management | 2 | week chip | 1 | no |
| NFL expected-wins | 2 | footnote | 1 | no |
| NFL forecast | 1 | header line | 1 | no |
| NFL weekly-report | 2 | matchup team | 0 | no |
| NFL superlatives | 1 | section header | 0 | no |
| NBA history | 3 | a manager name | 1 | no |
| NBA power | 2 | segmented control | 0 | no |
| NBA weekly-report | 2 | season label | 0 | no |
| NBA superlatives | 2 | season label | 0 | no |
| NBA roster-management | 2 | season label | 1 | no |

**Harness limitation (measured, stated so it isn't over-read):** the harness measures
*nesting* only. Superlatives scores 1, yet it is the most boxed page: 13 sibling
cards in a grid. SC-001 can't see sibling-box density, so "fewer boxes" on card
grids is judged per page against the design review (US9's trophy list), not by
this number.

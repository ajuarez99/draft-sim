# Quickstart / validation: spec 040

## Prereqs

- Docker Postgres on `localhost:5433` (`docker compose up -d postgres` from the main checkout); NOT the native 5432.
- Backend from **this worktree** (`backend/.\gradlew.bat bootRun`) — check the bootRun classpath points here, not
  at the main checkout (memory: worktree preview serves main).
- `web/`: `npm run dev`; hard-refresh any tab open from before a restart.
- Leagues: NBA 2025 `1229352720222134272`, NBA 2026 `1339351318115946496` (no scored week yet locally — the
  new-season case), NFL 2025 `1254190892974084096` and `1262506916429430784`, NFL 2026 `1346366555759341568`.

## Before any code change: capture baselines

Save `GET /api/leagues/{id}/superlatives` for all four 2025 leagues and NFL 2026 to
`specs/040-superlatives-commissioner-ux/baseline/` — the before side of SC-002/SC-003.

## Scenarios

1. **Embiid, NBA 2025 and NBA 2024** — every roster's JOEL_EMBIID total ≤ its old total; for each ABSENCE row,
   `estimatedPointsLost == round2(weeksMissed × pointsPerGame)`; every counted (roster, player, week) has 0
   `player_game` rows; no (player, week) counted for two rosters; hand-check one partial week (cost 0). Report
   before/after per roster and the old vs new holder.
2. **Embiid, NFL 2025 ×2** — per-roster totals identical to baseline (SC-003).
3. **Players view** — on the three kinds, Σ `playerContributions.value` per team == that team's `standings.value`,
   0 mismatches on every real league (SC-004). In the UI: card → See all → Players toggle shows them.
4. **Jabari** — `playerStandings` length == number of eligible players (≥ 2 adds, D/ST excluded), no cap of 10. *(Amended after code review M2.)*
5. **Commissioner's list, new season** — as the NBA 2026 commissioner, open `/leagues/1339351318115946496/superlatives`:
   the page shows 2025's awards; the Unethical card's "Commissioner's list" panel names **2026**, lets you search a
   player by name, set from 2 / through 3, save, edit the reason, remove with confirm — no id typed, no reload.
   Time the add (SC-001, < 30 s).
6. **Week range** — on NBA 2025 add an entry from week 5 through week 7 for a rostered player; the Unethical card
   and the entry's `playerWeeks` count only rostered weeks 5–7 (SC-005); clear the through week → counts to the
   end of the window. **Delete the test entry afterwards** (shared local DB).
7. **Read-only** — as a non-commissioner (different `X-Sleeper-User`) the panel has no inputs/buttons; a direct
   PUT/POST/DELETE returns 403 (SC-006).
8. **Validation** — through < from → 400 with message, shown inline.
9. Backend suite (`cleanTest test`, Postgres up, check skipped count is 0), `npx tsc -b`, `npm test`, `npm run build`.

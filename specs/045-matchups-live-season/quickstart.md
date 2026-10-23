# Quickstart: verifying spec 045

Prerequisites: Postgres on 5433 (`draftsim`/`draftsim`), and the backend built from this worktree. Check
the bootRun classpath, because a preview started from a worktree can serve main (memory). Then
`POST /api/ingest/all/1339351318115946496`.

1. **Opening / live week.** `GET /api/leagues/1339351318115946496/weekly-report/0` with a member's
   `X-Sleeper-User`. Expect `season 2026`, `live true`, `requestedSeason null`, and 6 matchups. Compare each
   side's `points` with `https://api.sleeper.app/v1/league/1339351318115946496/matchups/{week}` read in the
   same minute.
2. **Fallback still honest.** For an offseason league (no games, not started), the response is unchanged
   from main: last season, with `requestedSeason` set.
3. **Past seasons identical.** Snapshot `weekly-report/{1..N}` for 2025 NBA (`1229352720222134272`) and
   2025 NFL on main and on this branch. Diff them, ignoring only the three new keys.
4. **Other pages identical.** Expected wins, playoff odds, roster management, superlatives and refresh
   status for both real leagues, main vs branch.
5. **Live calls.** `GET /api/leagues/1339351318115946496/lock-ins`. Each matchup card's calls on the page
   equal `live.calls` for its two `rosterId`s.
6. **Past locks.** `GET /api/leagues/1229352720222134272/lock-ins?week=5`. The page's week 5 cards list
   exactly these locks, per roster, each with a labelled baseline kind.
7. **UI.** Open `/leagues/1339351318115946496/matchups` (or the rail's Matchups link). Check the in-progress
   label, no rankings or awards, no fallback note, the calls under each side, and the caption. Football
   Matchups has no lock-in content.
8. Backend `./gradlew test` (read the skip count), `cd web && npx tsc -b && npm run build && npx vitest run`.

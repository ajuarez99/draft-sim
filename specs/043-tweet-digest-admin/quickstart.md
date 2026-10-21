# Quickstart / live verification: spec 043

Prereqs: Postgres on 127.0.0.1:5433 (password `draftsim`), backend from this worktree on 8080
with `ADMIN_TOKEN` set, `vite dev` on 5173. Confirm the classpath is this worktree's
(memory: worktree preview serves main's launch.json).

| # | Check | Expected |
|---|-------|----------|
| V1 | `curl localhost:8080/api/admin/digest` (no token) | 403 `admin_token_required` |
| V2 | same with `-H "X-Admin-Token: $ADMIN_TOKEN"` | 200, NBA then NFL `SportDigest` |
| V3 | `?date=2026-10-20` (NBA opening night, NFL none) | NBA: 3 games, `NIGHT_INCOMPLETE` until a refresh-stats run after 2026-10-21 10:00 UTC, then `READY`. NFL: `NO_GAMES_ON_DATE` *(amended after review F9)* |
| V3b | `POST /api/admin/digest/refresh-stats` with token, then repeat V2 | `statsFetchedAt` advances; a `NOT_FETCHED` night becomes `READY` once complete *(added F2)* |
| V4 | `?date=2026-10-19` | NFL `READY` (MNF), week from rows |
| V5 | `?date=2026-04-12` (NBA 2025 finale) | NBA season resolves to the date's season; trending `TRENDING_NOT_HISTORICAL` |
| V6 | For V3/V4, every `points` vs `select stats->>'pts_std'` (NFL `pts_ppr`) for the same player+date | 0 mismatches (SC-003) |
| V7 | Top performers = SQL `order by points desc, player id` for the date | same 10, same order |
| V8 | Open `/admin/digest` signed out, then signed in as a non-admin member with no token | signed out: the existing sign-in gate. Signed in: the token prompt only. Neither shows digest content (SC-004) *(amended F12)* |
| V9 | Enter token; click Copy on each card; paste into a textarea | identical text; counter ≤ 280 (SC-002) |
| V10 | Edit a card past 280 | counter turns red; Copy copies edited text; reload restores generated |
| V11 | Paste one card into X's composer (Allan, by hand — not posted) | X's own counter agrees with ours ±0; Jokić/Dončić intact (SC-006) |
| V12 | Default (no date) on a morning after an off-night | `NO_GAMES_LAST_NIGHT`, names last slate + next game, 0 cards (SC-005) |
| V13 | Time from page open to clipboard | < 30 s (SC-001) |

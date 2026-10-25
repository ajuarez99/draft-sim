# Baseline captures (spec 047, T001/T002/T033)

- Scratch DB: docker container `draftsim-pg-047`, **localhost:5437** (db/user/password `draftsim`),
  restored from a plain `pg_dump` of the 5433 container (`draftsim-pg`) on 2026-10-24. Nothing was
  written to 5433 or 5432. The dump was at Flyway V38; the first boot applied V39 (main), and the
  047 boot applies V40.
- Backend: `bootRun` from this worktree on port **8087** (`DB_URL=jdbc:postgresql://localhost:5437/draftsim
  ADMIN_TOKEN=scratch047 PORT=8087 REFRESH_ON_VISIT_ENABLED=false`). No draft is tracked, so the
  poller is idle; the daily refresh route needs `REFRESH_SECRET`, which is unset, so it doesn't exist.
- `before/`: captured from **main `ebf4ee8` code** (worktree had no Java changes yet). `after/` is
  captured from the 047 code against the same scratch DB (before the T034 backfill).
- Caller: `X-Sleeper-User: 1122386008709910528` (popsharky = manager 7) on every call, plus
  `X-Admin-Token: scratch047`.
- Leagues: "(Foot) Ball Knowers" 2026 (1346366555759341568), "Ball Knowers" NBA 2026
  (1339351318115946496), West Coast FF 2026 (1389361939561332736). Managers 7, 23, 55.
- Script: `scripts/047/capture.sh <outdir> [port]`. Two back-to-back captures of main were
  byte-identical (deterministic).

## T033 result (after/, 047 code, same scratch DB, before the T034 backfill)

Compared with `before/` by deep equality (not text diff):

- `history-{fbk,nba,wc}` and so every `champion` flag: **byte-identical**.
- `rivalries-{fbk,nba,wc}`: identical once the added `discovery` field is removed. Nothing else added or removed.
- `manager-{7,23,55}-history`: identical except the `playoffAppearances` reason text (T029, intended);
  every career's `titles` is equal.
- Discovery on today's data: FBK 2026 mostPlayed 1, closest 1 (the 2-2 pair), lopsided **0** (7 x 2-1, as
  predicted); NBA 2026 closest **16** (all 2-2); West Coast 2026 closest 1, lopsided 1.

# T001: measurements before building (spec 049)

Measured 2026-10-26 on this machine (Windows), local backend started with `./gradlew bootRun` from
`.claude/worktrees/048-player-compare/backend` (process classpath confirmed to be this worktree's, not main's),
Postgres = the `draftsim-pg` docker container on 5433 (already running, healthy; the separate Postgres on 5432
was not touched). Server stopped afterwards. Caller: a member of each league, via `X-Sleeper-User`.

League ids: NBA 2025 "Ball Knowers" `1229352720222134272` (complete, 582 leaderboard rows); NBA 2026
"Ball Knowers" `1339351318115946496` (in_season). `curl` timings, same machine, no network hop, so they are a
floor for the deployed site (SC-007), not a prediction of it.

## SEASON leaderboard, full NBA 2025 season (`GET /api/leagues/{nba2025}/stats?window=SEASON`)

| | Measured |
|---|---|
| Raw bytes | 1,417,839 B (1.42 MB) |
| Gzipped bytes | 327,156 B (327 KB), `Content-Encoding: gzip` |
| Cold | 1.52 s (first request after the server booted) |
| Warm | 0.21 s, 0.25 s, 0.26 s (three further runs) |

Matches spec 023's 1.46 MB raw / 327 KB gzipped to within noise. **Warm is well under 1 s**, so plan A9's
threshold is not tripped: the chooser reuses this endpoint (no slim search endpoint). The cold 1.5 s is a
one-time box-cache warm-up on the server; the chooser only loads when opened.

## Player page, warm (`GET /api/leagues/{nba2026}/players/{id}`)

| | Measured |
|---|---|
| One page (player 1658), 11,695 B | 190 ms (first), then 66 ms, 71 ms |
| Two pages in parallel (players 1658 + 1970), wall clock for both | 224 ms, 157 ms, 170 ms |

SC-007 is "under 2 s for a warm pair on the deployed site"; locally the pair is ~0.2 s. The deployed figure is
**not measured**.

## Also seen (not asked for)

The new `thisWeek` and `windowQualification` fields were present on the NBA 2026 player page in this run:
`thisWeek = {week: 2, played: 0, remaining: 3}`, and all three windows `NOT_QUALIFIED` for that player.

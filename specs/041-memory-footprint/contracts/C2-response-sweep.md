# C2: Live response sweep (JVM flags + any cache change)

Run against the production-shaped container (quickstart), with old and new images on the
same database snapshot.

## Endpoints (`X-Sleeper-User` of a member of each league)
For "(Foot) Ball Knowers" NFL 2026 and "Ball Knowers" NBA 2026:
league list, board, analysis, history, superlatives, player stats (current + fallback season),
player trends, schedule grid, next matchup, draft grades, managers. Plus `/api/health`.

## Exclusions (named, per FR-003)
Fields whose value depends on wall clock or live upstream state: `generatedAt`/`fetchedAt`-style
timestamps, live poller tick data, and anything whose source row changes between the two runs
(the DB snapshot is frozen to avoid this). The exact JSON paths are listed in verification.md as found.

## Pass
Every body is equal after removing the excluded paths. Every status code is equal.

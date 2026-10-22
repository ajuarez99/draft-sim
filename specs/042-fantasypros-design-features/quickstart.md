# Quickstart: verifying spec 042

Prereqs: Postgres on 5433 (per AGENTS.md), `bootRun` restarted after the Java change (no hot reload), and
`vite dev`. Re-ingest both sports' players first (`POST /api/ingest/players?sport=nba`, then `nfl`) so
`injury_status` and `status_capture` are fresh. Use a real NBA league with known rosters (Allan's).

1. **T001 measurements first** (research R3, R5): the distinct `injury_status` values per sport, and
   whether NFL rows exist in `sport_schedule`. Paste the output into `verification.md`.
2. **US1**: open `/leagues/{id}/stats`. Pick Availability → "My roster". Only your players remain, and
   each shows the "Yours" mark. Reload: the filter holds (`?avail=mine`). Open Trends: your players in
   the Rostered lists carry the same mark. Compare 10 rows against Sleeper's live `/rosters` at the marks' as-of time (SC-001). Count the clicks from league home to the filtered list: it must be 2 or fewer (SC-002).
3. **US2**: open a rostered player on a game day. The header shows the owner, status with as-of time,
   and "Wed Oct 21 · vs OKC". Switch tabs: the URL changes, and the numbers equal `main`'s page for the
   same player (diff the JSON, SC-004). Check at 375×812 that the header fits without scrolling (SC-003).
4. **US3**: league home shows "Check your lineup". With no flagged starter it shows one line, "All
   starters are active". To see flags without waiting for a real injury, use a league where a starter is
   tagged in the fresh ingest. If none is, say so in verification rather than faking a fixture as live.
   ESPN league: "No starters to check for ESPN leagues". Signed out: the section isn't shown.
5. `./gradlew test` (report the skip count) and `npx tsc -b && npm run build`, both green.

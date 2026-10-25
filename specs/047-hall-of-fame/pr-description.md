# Spec 047: Hall of fame (roadmap 4.1)

## What it adds

- **Trophy case** on League History: one row per finished season.
  - Champion, from the same rule the 🏆 standings mark uses (now one shared `ChampionRule`).
  - Runner-up: the loser of the stored bracket final.
  - Regular-season points leader(s).
  - Last place.
  - A per-season **Awards** toggle that reuses the Superlatives rendering (`AwardWinners`).
- **Notable rivalries** on Rivalries: most played, closest, most one-sided, and playoff meetings. Every
  entry is taken from the matrix's own pair records.
- **Nemesis / victim** per league chain on manager pages. It's scoped to leagues the caller is in.

## Last place refuses on doubt

New migration **V40** adds `league_losers_bracket_match`. Bracket ingest and the bracket backfill now fetch
both brackets. Sleeper doesn't say whether a losers bracket is a toilet bowl or a consolation bracket, so
`LastPlaceRule` names last place only when **every** match meets all three conditions:

- it is scored;
- it was actually played between those two rosters in the mapped weeks;
- it agrees on direction.

Measured results:

| League | Season | Last place |
|---|---|---|
| (Foot) Ball Knowers | 2025 | roster 2 (toilet bowl) |
| West Coast | 2025 | roster 10 (consolation) |
| NBA | 2024, 2025 | refused (one match each contradicts Sleeper's winner, the known score drift) |

## Other changes

- `RivalryService`: `chainGames` is split out with its output unchanged, and `discovery` is added
  (optional in `api.ts`).
- New endpoints:
  - `GET /api/leagues/{id}/trophy-case`
  - `GET /api/managers/{id}/rivals`
- `RosterSeasonRepository.forManager` gains a tie-break order (`l.id`).
- The `playoffAppearances` reason text is updated.

## Verification

- Backend: 2433 tests, 0 failed, 6 skipped (the known `PlayerStatsLeaderboardReadIT`).
- Web: vitest 2544 passed; `tsc` and the build are clean.
- Before/after JSON capture on a frozen scratch DB: the only differences are `rivalries.discovery` and the
  intended reason text. `champion` and `titles` are identical.
- Live browser check at desktop and 375px, covering History, Rivalries and the manager page.
- Rivals scope checked with three callers:
  - an FBK-only member sees 1 chain;
  - popsharky sees 4;
  - admin sees 4.

Full detail is in `specs/047-hall-of-fame/`: `verification.md`, `plan-review.md` (F1–F17) and
`code-review.md` (S1–S4, all fixed).

## After deploy

- Run the bracket backfill per chain (`POST /api/ingest/brackets/{id}`, admin token) so finished seasons
  get their losers bracket. **Never during a live draft.**
- V40 was free on main `ebf4ee8`. Re-check it before merging if another migration lands first.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

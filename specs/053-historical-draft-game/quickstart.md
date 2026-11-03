# Quickstart / Validation: Historical Draft Game (spec 053)

Prerequisites: the local Postgres on 5433 (AGENTS.md) and the worktree's own
backend/web launch entries. Check the bootRun classpath before trusting a live
check, because `preview_start` from a worktree can run main's launch.json (see
project memory).

## 1. Import

```bash
curl -X POST localhost:8080/api/admin/historical/import -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"part":"ALL"}'
```

Expect a report with no `unmatchedFantasyRelevant` and no `uncoveredCollisions`.
Check these game counts:

| Season | Games |
|---|---|
| 1970-71 | 697 |
| 1975-76 | 738 |
| 1990-91 | 1107 |
| 1998-99 | 725 |
| 2010-11 | 1230 |
| 2011-12 | 990 |

These come from research R1 (SC-002). For 2019-20, check that no game after
2020-03-11 is stored.

Record the database size before and after (`pg_total_relation_size` on the four
`historical_*` reference tables) and write the number into verification.md.
This is review item O3.

## 2. Play one game per era

Start three games through the UI, re-rolling until each era is covered: 1970s, the
Kaggle 1980s–2010s range, and the Sleeper range.

For each game:
- The draft screen names the season and lists the counted stats. For a 1970s
  season it also lists the excluded stats and says why.
- The board label says "ranked by last season's points" and never "ADP" (SC-006).
- No card shows anything from the drafted season (FR-003). In the browser,
  inspect the JSON returned while drafting: no drafted-season totals, team or
  games.
- Finishing the draft shows weekly matchups, standings and your finish.

## 3. Hand-check scoring (SC-003)

In three different seasons, pick one team and one week from `result.lineups`. For
each starter, take the `gameDate` shown and pull that player's games that week
from `historical_player_game`. Then confirm:
1. the locked game is the max-scoring one under `historical_game.scoring`, and
2. the lineup total equals the matchup points.

## 4. Source parity (SC-005)

Pick one season present in both Kaggle and Sleeper, such as 2018-19. Compute every
player's season fantasy points both ways. Expect agreement within rounding for at
least 95% of players, and list the disagreements in verification.md.

## 5. Same-name players (SC-007)

For each entry in `historical-same-name.csv`, query `historical_player`. Expect
distinct ids and no game rows merged across people.

## 6. Reopen (FR-013)

Reload a finished game: the result is byte-identical. Edit the scoring config,
restart and reload: the result is still identical, because it reads the frozen
snapshot.

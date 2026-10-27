## Spec 049: compare two players (roadmap 3.3)

A basketball-only page at `/leagues/:id/compare?a=&b=`. It shows two players' player-page figures side by side
and marks the better value per row. Nothing on the page says who to start. You reach it from "Compare with
another player" on any NBA player page, and choose the second player by name (accent-insensitive).

Specified, built and reviewed as **048**. While it was in progress, `048-weekly-high-score-ledger` merged to
main (#48) under the same number, so this spec was renumbered to 049 before its first commit.

### What's marked, and what's never marked

| Marked | Never marked (why) |
|---|---|
| Fantasy pts/game, minutes/game (each window) | FG% / 3P% / FT%: 1-of-1 would beat 38-of-100. Made-attempted is shown underneath instead. |
| PTS, REB, AST, STL, BLK, 3PM per game (higher) | TOV per game: it mostly rewards a smaller role. |
| Turnover rate (lower) | Games and the season box-score total: they follow schedule and health. |
| | Games this week: these leagues credit one game per player per week. |

A window that is short of its games, stale (more than 14 days before the season's newest game) or a small
sample (under 100 minutes) is withheld, and the cell says which. That rule comes from the server's own `qualify`,
so the client keeps no copy of it.

### Backend: additive only, no migration, no new endpoint

The player page response (`GET /api/leagues/{id}/players/{pid}`) gains two fields:
- `thisWeek {week, played, remaining, scheduleStored}`: lock-ins' `liveWeek` and its counting, read from the
  URL league.
- `windowQualification {qualified, reason, lastN, stale}` per window.

Both are mirrored in `api.ts` as optional fields, so a skewed deploy reads "unknown".

### Review trail (all in `specs/049-player-compare/`)

- **Plan review, F1–F15.** It corrected:
  - a verdict scan that would have matched "starter";
  - a wrong "more games isn't more points" claim (lock-ins say otherwise);
  - a third "current week" rule.

  It also led to the three marking decisions above.
- **Code review, B1–B3 / S1–S4 / N1–N7, all fixed.** The bugs were:
  - no way to swap a player;
  - a stale low-minutes window still got marked;
  - the owner showed unlabelled on a fallback season.

### Verified (locally; see `verification.md`)

- **10 player pairs on the complete 2025 NBA league: 0 value or mark errors.** Every cell and mark was checked
  against an independent rule. The pairs included stale, short-season, bench and recency-boundary players.
- **375 px:** no horizontal scroll.
- **Football league:** refused.
- **Unknown player id:** the other column renders, and nothing is marked.
- **This week on 2026:** the counts equal the stored week-2 schedule.
- **Warm pair load:** 63–160 ms locally.
- **Merged with main (#48):** `tsc` clean, vitest 2616/2616, build OK, targeted backend unit tests 110/110.

### Not verified

- Production, including SC-007 latency there.
- A week where games have already been played (local box scores end 10-21; unit tests cover it).
- The full backend IT suite after the merge. It was not re-run, to avoid applying #48's V41 to the shared local
  DB. Before the merge it was 2442 tests, 1 failed, 6 skipped. The one failure,
  `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt`, reproduces on unmodified main: spec 047's
  bracket backfill is missing from the shared DB.

### Deploy notes

No migration and no backfill. The backend and frontend can deploy in either order.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

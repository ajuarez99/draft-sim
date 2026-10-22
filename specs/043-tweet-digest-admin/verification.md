# Verification: spec 043 (Morning Tweet Digest)

Run on 2026-10-20 between 23:00 and 23:30 CDT (2026-10-21 04:00–04:30 UTC). The backend ran with
`bootRun` from **this worktree**, started by hand rather than through `preview_start`, because the
memory note says a worktree preview serves the main checkout's code. It used a local
`ADMIN_TOKEN` and `DB_URL=127.0.0.1:5433`, and was **restarted after the code-review fixes**. The
web side was this worktree's `vite` on 5173, driven in the in-app browser and signed in as
`popsharky`.

## Verified (executed)

| # | Check | Result |
|---|-------|--------|
| V1 | `GET /api/admin/digest`, no token | 403 `admin_token_required` |
| V2 | Same, with the token | 200, NBA then NFL. NBA `NIGHT_INCOMPLETE` (10-20 opening night, before the 10:00 UTC cutoff). NFL `NO_GAMES_LAST_NIGHT`, last slate 10-19, next 10-22 |
| — | `?date=bad` | 400 `bad_date` |
| V3 | `?date=2026-10-20` | NBA `NIGHT_INCOMPLETE`, as plan-review F9 predicted; NFL `NO_GAMES_ON_DATE` |
| V4 | `?date=2026-10-19` (MNF) and `2026-10-18` (Sunday) | NFL `READY`, week 6, 1 and 12 games |
| V5 | `?date=2026-04-12` | NBA resolves to **season 2025**, `READY`, 15 games; trending `TRENDING_NOT_HISTORICAL` |
| V6 / SC-003 | Every point figure on those 3 nights (top performers, key players, position leaders) vs `player_game.stats->>'pts_std'` / `'pts_ppr'` (missing = 0) | **51 / 51 equal, 0 mismatches**. Re-run after the fixes: 51 / 51 again |
| V7 | Top 10 vs an independent SQL ranking (resolved, non-TEAM players; NFL QB/RB/WR/TE) | **30 / 30**: same players, order and points on all 3 nights |
| V8 / SC-004 | `/admin/digest` signed out, then signed in with no token | Signed out: the sign-in gate. Signed in: the token prompt only. No digest content either way. The rail has no admin link |
| V9 (part) | Click Copy | The button flips to "Copied". **The clipboard contents were not readable here**: the pane blocks `clipboard.readText` and a synthetic Ctrl+V. The unit test checks the text handed to `writeText` |
| V10 | Edit a card past 280 | Counter shows `304 / 280` in the red token. Reset restores the generated text (lengths back to 273/219/65/115). Changing the date regenerates the cards with no stale edits |
| SC-002 | Generated card lengths on 10-18, 10-19 and 04-12 | All ≤ 280. NFL and NBA top-score cards fit 4 of 10 and say "top 4 of 10 shown" |
| SC-005 | NFL default view | "No games last night. Last games: Oct 19. Next games: Oct 22", with zero cards |
| SC-006 (part) | Diacritics | "Nikola Jokić" is intact in the generated text |
| B1 fix | `?date=2026-10-19` leaders card | `🏈 Monday night leaders (Wk 6): …`; it no longer claims a whole week |
| S1 fix | Default mode after midnight ET | NBA `nextGameDate` = 2026-10-21 (today's slate now counts) |
| L1 fix | `?date=2027-01-05` | `DATE_IN_FUTURE` for both sports |
| Tests | Backend `--tests '*AdminDigest*'` 29/29, 0 skipped. Full suite 2138: 1 known unrelated failure (`BoardRepositoryLatestBeforeIT`, shared-DB timestamps), 6 skips (`PlayerStatsLeaderboardReadIT`, a data assumption: "2026 has stored games now"). Web: tsc clean, vitest 2333/2333, build OK | Ran (the fix-pass agent ran the full suites; I ran the live checks) |

## Not verified — owed

- **Trending card on a READY default night**, meaning points against Sleeper's adds. NBA's first
  night can't be complete before 10:00 UTC on 10-21, and there is no clock override. Covered only
  by unit tests. **Check: on the morning of 10-21, press "Fetch latest stats", then reload.**
- **V11 / SC-006:** paste a card into X's composer and compare X's counter with ours. This is
  Allan's check, and nothing gets posted.
- **V9, the real clipboard:** paste a copied card anywhere and confirm it matches the card.
- **V13 / SC-001:** under 30 seconds from opening the page to the clipboard; Allan's own morning.
- **`POST /api/admin/digest/refresh-stats` happy path:** it calls Sleeper and has not been
  executed. Only its 403 is covered by the IT.
- **Prod:** whether `ADMIN_TOKEN` is set on the Railway backend (DEPLOY.md says it isn't).

## Observed, not fixed (Allan's call)

- NFL and NBA top-score cards fit only **4** players under 280, because the stat lines are long.
  A shorter line format would fit 5.
- With a past `?date=`, key players use **today's** Sleeper search rank. For example, the
  2026-04-12 NBA "stars" are the 2026 top 15.
- An off-season date such as `?date=2025-07-04` reports season 2026 with no next game. That is
  harmless but imprecise.
- L4 (the spotlight sentence is not trimmed) and L10 (a teamless trending player shows as DNP,
  shared spotlight logic) are left as documented in code-review.md.

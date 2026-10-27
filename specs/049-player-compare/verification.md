# Verification: Compare two players (spec 049)

Parent session, 2026-10-26, after the code-review fixes. Everything below was **run**, unless it says otherwise.

## Setup

- **Backend**: this worktree's `bootRun` on **8091**, with `--refresh.on-visit.enabled=false` so it would not write to the shared DB. The startup log shows the classpath `...\worktrees\048-player-compare\backend\build\classes\java\main` (memory: "worktree preview serves main" — checked, not assumed).
- **Database**: the shared Postgres `draftsim-pg` on 5433.
- **Frontend**: vite from this worktree on **5181**, proxied to 8091 via `DRAFTSIM_PROXY_TARGET`.
- **Browser**: the built-in Browser pane, signed in through the app's local sign-in as **popsharky** (local dev auth takes a username only).
- **Local data limit**: 2026 NBA box scores in this DB stop at **2026-10-21**, fetched 10-22. Every player has 1 game. So the 2026 checks cover "this week" and the withholding paths, and the marks were checked on the complete 2025 league (1229352720222134272).

## Results

| Check | Result |
|---|---|
| **SC-001 + SC-002**, 10 pairs, 2025 league | ✅ **0 errors, all 10 pairs**. Method: an in-page script moved the app through router history (no reload) for each pair, fetched both player JSONs, and checked every rendered cell (fantasy pts/game and minutes/game for each window, games, the per-game line, turnover rate) against the JSON. Marks were checked against an **independent** rule, not `compare.ts`: literal 10/5, the 14-day recency against the season's newest game (2026-04-12), the 100-minute small sample, displayed-string equality, and higher-better except turnover rate. It also asserted that FG%/3P%/FT% and the season total are never marked. Pairs: SGA–Jokić; Giannis (stale)–LeBron; AD (stale, 94 days)–Banchero; Kessler (5 games)–Duren; Reed–Valančiūnas (bench); Matković (last game exactly 14 days before the newest, the recency boundary)–Cisse; Lovering (2 games, small sample)–Garcia (0 marks of 13); KD–Amen Thompson; Markkanen–MPJ (both stale); Vukcevic–KPJ. |
| No verdict text (SC-006) | ✅ Read on every page: no start/sit, tally or recommendation. The vitest scan with positive controls passes (fix pass). |
| **SC-003**, entry and chooser | ✅ Player page → "Compare with another player" → "Choose a player" → typed `jok` → **Nikola Jokić** (accent-folded match) → picked. That is three actions after the page loads, and the URL became `?a=1957&b=1658`. Not timed with a stopwatch: there were no waits beyond the chooser's list load. |
| SC-004, shared address | ✅ Every pair above was loaded straight from its URL and rendered the named players. |
| **SC-005**, 375 px | ✅ The Valančiūnas / Gilgeous-Alexander pair at 375×812: `documentElement.scrollWidth` = 375, and no element's right edge passes the viewport. The columns stay side by side and the names wrap. Screenshots were taken in the session. |
| SC-007, latency (local) | ✅ locally: a warm pair rendered in **63–160 ms**. In the first sample, the two player requests started 1 ms apart (8 ms and 9 ms), so they run in parallel. A warm single player page with `thisWeek` took 47–63 ms (curl), against 66–71 ms in T001 before `thisWeek`, so **N7's extra reads cost nothing measurable locally**. **Deployed: not measured.** |
| **This week** (quickstart 9), 2026, Monday 10-26 | ✅ `week: 2` for SGA, Jokić and Edwards: week 1 in the schedule runs 10-20..10-25, and week 2 runs 10-26..11-01. `remaining` is 3 / 3 / 4, which equals the stored week-2 `pre_game` rows for OKC, DEN and MIN exactly. `played` is 0, which is honest for this DB (box scores end 10-21). The page shows "0 played · 3 left", the lock-ins note, and "schedule as of Oct 22, 4:23 PM EDT". **Not checked:** the league's stored `leg`, and the live lock-ins call's own week (`/lock-ins` answers `NO_SCORED_WEEKS` locally). The week was verified against the schedule, not against lock-ins' output. |
| Withholding on thin data | ✅ 2026 one-game players: SEASON reads "small sample (36 min); not compared", LAST_10 reads "1 of 10 games", and LAST_5 reads "1 of 5 games". No cell is marked. |
| Ownership (B3) | ✅ 2026 shows "As of Oct 26, 2026", the owner, and **Yours** on SGA. 2025 shows "End of 2025–26 regular season (week 18)" and "Free agent". |
| Football (FR-013) | ✅ The "(Foot) Ball Knowers" 2026 compare page reads "Player comparison is available for basketball leagues only." |
| One column fails (FR-012) | ✅ `?b=not-a-player`: column A renders in full, B reads "We have no player with that id in this league.", and there are 0 `.pc-win` marks. |
| The "Missed N of his team's games" line (S4), and the "Change" control on both columns (B1) | ✅ Present. The swap itself is covered by vitest (fix pass), not clicked live. |

## Not verified

- **Production**: nothing is deployed. SC-007 on the deployed site is not measured.
- **Live lock-ins week**: the comparison's week matches the stored schedule, not a live lock-ins response (none exists locally yet).
- **A real `played > 0` this week**: the local box scores are stale, so this was not exercised live. The backend unit tests cover it.
- **Backend suite**: 2442 tests, 1 failed, 6 skipped (fix pass's run, not re-run here). The failure is `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt`, which the build agent reproduced on unmodified `ec72031`. It comes from the shared DB's missing spec 047 losers-bracket backfill, not from this change. The 6 skipped tests were not compared one-for-one with main's.

# Verification: spec 035 (2026-10-14/15, partial)

Everything here was **run**, unless a line says otherwise.

**Status: partial.** The happy path needs real ESPN data, and Allan's league is private. Those steps are listed at the end as **not run**.

## Setup

- **Scratch DB:** container `draftsim035-pg` on `localhost:5435` (Docker named volume), database `parity035`. It's a fresh `pg_dump` of the shared 5433 `draftsim`, run inside the container (host `pg_dump` 14 refuses a 17 server). Its history holds 31, 34 and 33. Both servers run with `--spring.flyway.ignore-migration-patterns=*:missing,*:future`.
- **Correction, shown:** the scratch copy was first restored into a new database **on the shared 5433 server**. That's what lessons #42 says not to do. It caused no damage: league count was 10 before and after, and the database was dropped within minutes. The copy was then redone in its own container.
- **Servers:**
  - Baseline = a detached worktree `.claude/worktrees/035-baseline` at **8346cdf**, 035's merge base, on port 8145.
  - 035 = this worktree, on port 8135.
  - Both run with `REFRESH_ONVISIT_ENABLED=false`, so no Sleeper data is written. Their launch configs are `draft-sim-035base-api-8145` and `draft-sim-035-api-8135` in the main checkout's `.claude/launch.json`.
  - The startup log line confirms the 035 server's classpath is the 035 worktree's `build/classes`.
  - Flyway on 035 applied `35 | espn platform`.
- **Dev credential key:** generated locally, stored at `C:\Users\allan\.draftsim035\credential.key`, outside the repo.

## SC-005: Sleeper parity, measured

The capture script (`scratchpad/capture.sh`, signed in as popsharky) fetches 214 routes:
- `/api/drafts`, `/api/board`, `/api/managers?sport=nfl|nba`;
- 17 league routes × 10 Sleeper leagues;
- 4 draft routes × 10 drafts.

| Comparison | Status codes | Structural or string differences | Numeric differences |
|---|---|---|---|
| baseline vs the same baseline process again | identical | none, except league `1400986983953608704` ("test"): analysis and ballot change on every call | none |
| baseline vs a **fresh** baseline process | identical (202×200, 10×400, 2×500) | none | roster-management `efficiency` (last float bit) and analysis projections (0.1 rounding flips) on 7 files |
| baseline vs **035** | identical (202×200, 10×400, 2×500) | **only** `platform: "SLEEPER"` added to all 10 `/api/drafts` rows | the same two kinds as baseline vs fresh baseline (max |Δ| = 0.1, rounding), on 8 files |

- `/api/managers?sport=nfl` and `?sport=nba` are **byte-identical**. This is the F1 check: ESPN data must not leak into Sleeper tendencies. There's no ESPN league in this DB, so this pins the code path, not the filter; the filter itself is pinned by `DraftRepositoryPlatformIT`.
- **Verdict: FR-013 / SC-005 hold.**
- **Labelled caveat:** the analysis file of league `1346366555759341568` shows a 0.1 flip under 035 that the two baseline restarts didn't happen to produce for that file. It's the same mechanism seen on two other leagues between baseline restarts, so this is a strong inference, not proof per file.
- **Pre-existing finding, not 035:** analysis projections and roster-management efficiency differ between server restarts on unchanged code and data. Some summation order isn't deterministic across JVM runs. Recorded in HANDOFF as a separate issue.

## US1 error paths against the **real** ESPN league (1314454666, NBA, ESPN 2027)

Row counts in `league / manager / draft / manager_link / league_credential / platform_player_link` were `10 44 10 0 0 0` before **and** after every call below (FR-005).

| Request | Response |
|---|---|
| your real link, no cookies | 403 `ESPN_PRIVATE_NO_ACCESS` |
| the same, with `claimRosterId: 8` | 403 `ESPN_PRIVATE_NO_ACCESS` |
| `"not a link"` | 400 `MALFORMED_REF` |
| `leagueId 12345678, nba, 2026` | 404 `ESPN_NOT_FOUND` (real ESPN 404) |
| no `X-Sleeper-User` | 401 `SIGN_IN_REQUIRED` |
| `X-Sleeper-User: espn:{ABC}` (S4) | 401 `SIGN_IN_REQUIRED` |
| real link plus **invented sentinel cookies** (test values, not credentials) | 403 `ESPN_CREDENTIAL_REJECTED`, nothing stored |

**Leak check:** the server log runs at DEBUG for the app package (confirmed by DEBUG lines present). It contains **zero** matches for the sentinel `espn_s2` value. The sentinel isn't in any response body either; they're shown above.

## Not run (blocked) and what unblocks each

| Step | Blocked on |
|---|---|
| quickstart §1, real ESPN shapes (R9) and `EspnScoringMap` confirmation | Allan saving the API JSON from his signed-in browser into `fixtures-real/`, or pasting cookies in the UI |
| §2 add through the UI, §3 compare with ESPN (SC-002/003), §4 pages (SC-004) | the same, plus a browser session |
| any browser check of the 035 UI (setup panel, badge, gates, unmatched note) | the preview tool allows 5 dev servers per folder and 4 belong to other chats, so the 035 web server couldn't start |
| §6 refresh with real data | real data |
| F11 re-measure of the match rate against the app's `player` table | real roster data |

**Nothing about the happy path is verified.** All ESPN field mappings remain the guesses in research R9, marked `// unverified (T053)` in code.

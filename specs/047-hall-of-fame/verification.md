# Verification: spec 047 (Hall of fame)

Run 2026-10-24 in worktree `047-hall-of-fame` (from main `ebf4ee8`). "Run" means executed; "not run" means exactly
that. Nothing is committed.

## What was executed

| Check | Result |
|---|---|
| `cd backend && ./gradlew test` (all, `DB_URL` = scratch DB on 5437) | **2433 tests, 0 failed, 0 errors, 6 skipped** (re-run after the code-review fixes) |
| the 6 skips | all `PlayerStatsLeaderboardReadIT`, the known data-dependent skip (HANDOFF spec 045/041); not caused by this change |
| new/extended backend tests, run on their own | `ChampionRuleTest`, `LastPlaceRuleTest`, `RivalryDiscoveryTest`, `HallOfFameRivalsTest`, `BracketIngestServiceTest`, `HallOfFameServiceIT` (15 tests, 0 skipped; now asserts FBK 2025 -> roster 2 TOILET_BOWL, West Coast 2025 -> roster 10 CONSOLATION and NBA MIXED_DIRECTION against the stored losers brackets and stored league_matchup pairing), `RivalryParityIT` (6 tests, 0 skipped) |
| `cd web && npx tsc -b` | clean |
| `cd web && npx vitest run` (all) | **144 files, 2544 tests passed, 0 failed** (re-run after the code-review fixes) |
| `cd web && npm run build` | built (the usual >500 kB chunk warning only) |
| T033: before/after capture, same scratch DB | see below |
| T034: bracket backfill on the scratch DB | see below |

## T035: live browser check (parent session, 2026-10-24, after the code-review fixes)

Run against the 047 backend on 8089 (scratch DB 5437, refresh-on-visit off) and vite on 5179, in the
Browser pane, as popsharky unless noted.

**(Foot) Ball Knowers History > Hall of fame (desktop).** One 2025 row, matching `/trophy-case`:

| Column | Shown |
|---|---|
| Champion | 🏆 popsharky |
| Runner-up | FatDeebo's DoubleDogCombo (ChedddaBob) |
| Points leader | popsharky, 2042.84 |
| Last place | T. Lawrence of her Labia (BamAddABio, TOILET_BOWL) |

- **Awards toggle:** it shows "Loading awards…", then the full award list through the shared
  `AwardWinners`.
  - Highest week is GraftonCarlson, 205.04 in week 8, matching the record book.
  - The list is long: it's the whole Superlatives content, including waiver details. That's
    acceptable under FR-012, but it's a candidate for a compact winners-only view later.
- **Two fetches:** `/trophy-case` was fetched twice on load. It's suspected to be React StrictMode's
  dev double-mount. That's not checked, and it doesn't matter in production builds.

**Ball Knowers NBA Rivalries > Notable rivalries (desktop).** These match the measured data:

| List | Shown |
|---|---|
| Most played | (Wag) town vs Jay Son or Thot Daughter, 4-2 in 6 meetings |
| Closest | "16 pairs are level at 2-2" (expander) |
| Most one-sided | "4 pairs are tied at 4-0" |
| Playoff meetings | "10 pairs have met in the playoffs" |

The threshold is printed as hand-set.

**Manager 7 `/rivals`:**

| Caller | Chains returned |
|---|---|
| popsharky | 4 (three NFL, one NBA) |
| admin (no user header) | the same 4 |
| FBK-only member (manager 41) | exactly 1, (Foot) Ball Knowers |

The NBA chain's nemesis is GraftonCarlson (0-3 in 3), and its victim is BamAddABio (4-2 in 6).

The page (desktop) shows a "Rivals · {league}" block per chain:
- The FBK chain has a victim list (2-1, 3 met ×2) and a nemesis line saying nobody they've met 3+
  times has a losing record against them.
- fantasy😍 and West Coast read "No opponent has met them 3 times yet (3 is hand-set)".
- The new `playoffAppearances` text shows: "the bracket is stored, but appearances aren't derived
  from it yet".

**375px.**
- West Coast History: `scrollWidth` = 375, so no page overflow. The trophy table scrolls sideways
  inside its card, with the season column pinned.
- NBA Rivalries: the discovery cards stack, the "16 pairs" expander opens to readable rows, and
  `scrollWidth` = 375.

**Console.** The only errors are 403s from `/api/leagues/{id}/refresh`. That's expected, because the
backend ran with refresh-on-visit disabled. Nothing came from 047's endpoints.

**Seen, not fixed (copy nit).** The trophy-case intro says last place is named "only when the
consolation bracket's direction can be read from the stored scores". After S2/S3 the rule is
stricter: every match must be scored and actually paired, and agree. The sentence understates the
rule. It isn't wrong.

## Not run

- Production. Nothing was run against the real 5433/5432 databases or Railway.
- West Coast 2026's 14-team losers bracket (12 matches) was fetched by the backfill, but that season is in progress,
  so `LastPlaceRule` has not been exercised on a 14-team shape. It refuses anything its arithmetic doesn't fit.

## T033: before/after (scripts/047/capture.sh)

Compared with deep equality, scratch DB, same `X-Sleeper-User` (manager 7), poller and refresh off:

- `history-{fbk,nba,wc}`: identical, so every `champion` flag is unchanged.
- `rivalries-{fbk,nba,wc}`: identical once `discovery` is removed; `discovery` is the only field added.
- `manager-{7,23,55}-history`: identical except the `playoffAppearances` reason text (T029, intended); every
  career's `titles` is equal.

## T034: bracket backfill (scratch DB, backend on 8087, `POST /api/ingest/brackets/{id}` with the admin token)

All four chains: each season fetched 7 winners and 7 losers matches (the 14-team 2026 West Coast and fantasy leagues
returned 12 losers matches). Trophy case afterwards:

| League / season | Champion | Runner-up | Last place | Final disputed |
|---|---|---|---|---|
| "(Foot) Ball Knowers" 2025 | popsharky (r1) | ChedddaBob (r3), from the backfilled bracket (was NO_BRACKET before) | **BamAddABio (r2), TOILET_BOWL** (2-12, the worst record) | no |
| "Ball Knowers" NBA 2025 | GraftonCarlson (r12) | jpelwell (r7) | **not reported: MIXED_DIRECTION** | yes (known NBA stored-score drift) |
| "Ball Knowers" NBA 2024 | njerickson (r2) | theadambomb98 (r6) | **not reported: MIXED_DIRECTION** | yes |
| West Coast FF 2025 | gregmullen (r6) | achan20 (r3) | **stevenamano (r10), CONSOLATION** (the p=5 loser) | no |

Points leaders: FBK 2025 popsharky 2042.84; NBA 2025 ChedddaBob 4583; NBA 2024 ImReallyHarry 5260; West Coast 2025
aerie33 (r7, 1916.44, a former manager).

Discovery on today's data: FBK 2026 lopsided is empty (7 pairs at 2-1, as the plan predicted); NBA 2026 closest is
16 pairs at 2-2; West Coast 2026 closest 1, lopsided 1.

## Incident to report

The first `./gradlew test` of this build ran **without `DB_URL`** and so pointed at the shared 5433 database.
Flyway applied **V39 (main's) and V40 (this branch's `league_losers_bracket_match`)** to it. The integration tests that
ran then (`RivalryParityIT` and friends) clean up their synthetic rows (checked: no `it-%` leagues remain), but the two
migration rows and the empty `league_losers_bracket_match` table are still in 5433's schema. Dropping them was refused
by the permission system, so they were left. Consequence: a session running a build that doesn't contain V40 against
5433 will fail Flyway validation until the V40 row and table are removed (or this branch is merged). Every later run
used 5437.

# Implementation Plan: Morning Tweet Digest (admin copy-and-paste page)

**Branch**: `043-tweet-digest-admin` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/043-tweet-digest-admin/spec.md` (clarified 2026-10-20).

## Summary

One admin-only endpoint, `GET /api/admin/digest[?date=]`, assembles per sport the covered night's
trending adds, top performers, key players and (NFL) position leaders from data already stored,
scored with **Sleeper's own computed points** (`pts_std` NBA, `pts_ppr` NFL — research R1). One
unlinked web page, `/admin/digest`, turns that into ≤ 280-weighted-character post cards with Copy,
in-place edit and a live counter. No migration, no new upstream call, no writes.

What it builds on (read in the code, not assumed):

- `PlayerSpotlightService.choosePeriod` (`engine/PlayerSpotlightService.java:474`) — the night and
  its completeness; static, package-private, reused for both sports in NIGHT mode (R2).
- `PlayerSpotlightService.trending` (same file, static) — PLAYED / DID_NOT_PLAY / NO_GAME
  classification in Sleeper's order; reused with an empty ownership map (R3).
- `PlayerGameRepository.forDate / datesForSeason / forPlayers` — sport-wide rows (spec 009 stores
  every player's entries, not only rostered: `ingest/PlayerGameIngestService.java` class doc).
- `AdminGateInterceptor` already covers `/api/admin/**` (`config/WebConfig.java:42`) and CORS
  already allows `X-Admin-Token` (`WebConfig.java:60`).

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5), TypeScript (React + Vite)
**Primary Dependencies**: existing only — Spring JDBC `JdbcClient`, React Router
**Storage**: Postgres, read-only for this feature; no migration (highest on main is V38)
**Testing**: JUnit 5 unit tests for the assembler (pure, fed rows); one `*IT` against the 5433 DB
for the repository reads; Vitest for `tweetText.ts` and the page
**Target Platform**: Railway backend + web; admin uses desktop browser
**Project Type**: web application (backend + web)
**Performance Goals**: one page load < 2 s locally (one night is ≤ ~300 NBA rows / ~1,000 NFL)
**Constraints**: weighted length ≤ 280; never show 0 for a non-game; never relabel a stale/older
night as "last night"
**Scale/Scope**: one operator; ~1 request per morning

## Constitution Check

| Rule | Status |
|------|--------|
| Migrations append-only | PASS — no migration |
| `api.ts` mirrors Java records field-for-field | PLANNED — T-task pairs each record with its TS type in the same change |
| No retuning hand-set constants | PASS — all constants new and labelled arbitrary (data-model) |
| `Map.of` null rule | PLANNED — `line` and every response map built mutably; nullable opponent/team/points |
| Ask before committing | PASS — nothing committed without Allan |
| Planning doc ≠ verified spec | PASS — reuse points verified by reading code (above) |

Post-design re-check: PASS, no violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/043-tweet-digest-admin/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/admin-digest-api.md
├── checklists/requirements.md
└── (later) plan-review.md, tasks.md, code-review.md, verification.md
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/AdminDigestService.java        # new; same package as PlayerSpotlightService for its static helpers
├── api/AdminDigestController.java        # new; GET /api/admin/digest
└── store/AdpRepository / PlayerGameRepository / SportScheduleRepository
                                          # small read methods added if missing (top-N search rank,
                                          # seasons on a date, next game date)
backend/src/test/java/.../engine/AdminDigestServiceTest.java   # pure
backend/src/test/java/.../api/AdminDigestControllerIT.java     # 403 / 200 / 400 on the real DB

web/src/
├── api.ts                 # AdminDigest types + getAdminDigest(token, date?)
├── digest/tweetText.ts    # pure: entries → card text, weighted length, fit-to-280
├── digest/tweetText.test.ts
├── pages/AdminDigest.tsx  # token prompt, date picker, cards, copy/edit/counter
├── pages/AdminDigest.test.tsx
└── App.tsx                # route /admin/digest, no nav link
```

**Structure Decision**: existing backend + web layout; the service sits in `engine/` so it can call
the spotlight's package-private static helpers instead of copying them.

## Design notes

1. **Night per sport** (R2): `datesForSeason` + `sport_week_stats` → `choosePeriod(true, …)`.
   Default mode: READY only if the chosen night == yesterday (America/New_York); else
   `NO_GAMES_LAST_NIGHT` (no complete night yesterday, and no rows dated yesterday) or
   `NIGHT_INCOMPLETE` (rows dated yesterday exist but are not complete). `?date=` mode: READY if
   that date is complete, `NIGHT_INCOMPLETE` if it has rows but isn't, `NO_GAMES_ON_DATE` if none.
2. **Season for a date**: default mode uses `SportLeagueSeasonSource.leagueSeason(sport)`; `?date=`
   mode uses the season whose `player_game` rows carry that date (a new `seasonsOn(sport, date)`
   read), so the 2025 NBA finale (dated 2026-04) resolves to 2025.
3. **Scoring**: points = `stats[POINTS_KEY]` as a double; absent ⇒ row excluded and counted in
   `unscoredRows`. A player with several rows on one date (not expected) sums, as spotlight does.
4. **Trending** only in default mode; `?date=` ⇒ `TRENDING_NOT_HISTORICAL`.
5. **Key players** (R4): newest `sleeper_search_rank` snapshot, top 15; included only if his team
   is among the night's teams (`team` or `opponent` of any row); expectation from `forPlayers`
   for this and last season.
6. **Position leaders** (NFL): best by points per primary position QB/RB/WR/TE.
7. **Frontend text** (R5): cards = Trending, Top performers, Key players, Spotlight (top performer's
   full line), NFL Position leaders. `fitToLimit` drops trailing entries until weighted length
   ≤ 280 and appends nothing to the tweet; the card's footer shows "top N of M shown".
8. **Token**: `sessionStorage['bk.adminToken']`, sent only by `getAdminDigest`; 403 clears it.

## Complexity Tracking

None.

## Amended after review (2026-10-20, plan-review.md F1–F16) — overrides the sections above

- **F1 (HIGH): an absent points field means 0.0, not "unscored".** Sleeper leaves out zero-valued fields.
  Measured: 993 played NBA 2025 rows have no `pts_std`, and no row has `pts_std = 0`. Example:
  `{"sp": 81, "plus_minus": 0}`. Points come from `PlayerSpotlightService.scoreAll` with the
  one-key scoring map `{pts_std: 1.0}` (NBA) or `{pts_ppr: 1.0}` (NFL), so a missing field scores 0
  through the app's own scorer. `unscoredRows` is dropped from the contract.
- **F2 (HIGH): nothing fetches stats on a schedule.** The daily cron
  (`DailyRefreshService.runAll`) never calls `PlayerGameIngestService`. Only league visits and the
  admin ingest route do. Amendments:
  - (a) Whether there were games comes from `sport_schedule`, never from `player_game` alone.
  - (b) A new status, `NOT_FETCHED`, means games were scheduled that night but no fetch has
    completed.
  - (c) Every `SportDigest` carries `statsFetchedAt`, the covered week's
    `sport_week_stats.fetched_at`.
  - (d) A new admin route, `POST /api/admin/digest/refresh-stats`, runs
    `refreshSportSeason(sport, currentSeason, now)` for both sports. It is single-flighted and
    skips final weeks. The page gets a "Fetch latest stats" button.

  `GET /api/admin/digest` itself stays read-only. Adding the step to the daily cron is **not**
  done here, because it would change every page's freshness. It is a follow-up.
- **F3: SC-003 is reworded** in the spec, with a visible note. Every figure must equal Sleeper's
  own computed points for that game (`pts_std` or `pts_ppr`), with 0 mismatches. The page labels
  the basis ("Sleeper default points" or "Sleeper PPR"). `pts_std` is Sleeper's default
  points-league score. It is not the "Test" league's scoring, which has foul penalties, so it must
  never be compared with league-scored pages.
- **F4: exclusions.** `TEAM_*` ids, and ids that don't resolve to a `player`, are excluded
  everywhere. NFL top performers and position leaders cover QB, RB, WR and TE only. K and DEF are
  excluded; that choice is hand-set and labelled. V7's SQL check joins `player` and applies the
  same filter.
- **F5: FR-008 is amended in the spec**, with a visible note. The list stays Sleeper's search rank
  all season, because Sleeper recaptures it daily. The expectation is this season's mean over at
  least 5 games before the night; else last season's mean over at least 10 games; else
  `NO_BASELINE`. Projections are not used, because NBA projection rows carry no `pts_std`.
- **F6: the expectation uses only games dated before the covered night.**
- **F7: a key player appears** if he has a row that night. In default mode only, he also appears
  (as DNP) if his current team is on that night's schedule. On `?date=` only rows count, because
  the current team can't be trusted for past dates.
- **F8: how NO_GAME renders.** The trending tweet shows only PLAYED and DID_NOT_PLAY entries, in
  Sleeper's order with their true rank numbers. NO_GAME entries are left out of the tweet, and the
  page counts them ("3 of the top 10 had no game"). Spec FR-006 is amended.
- **F9: V3 is fixed.** 2026-10-20 had 3 games. Locally that night stays incomplete until a fetch
  runs after 2026-10-21 10:00 UTC.
- **F10:** `isComplete` in `PlayerSpotlightService` becomes package-private, with no behaviour
  change.
- **F11:** no change; `game_date` is the US date.
- **F12: V8 is fixed.** A signed-out visitor hits the existing sign-in gate first. The check is
  that neither a signed-out visitor nor a signed-in non-admin sees digest content.
- **F13:** the digest calls go through `apiFetch`, which keeps `Authorization` and
  `X-Sleeper-User`, and they add `X-Admin-Token`. Whether `ADMIN_TOKEN` is set in prod gets
  checked at deploy.
- **F14:** the weighted length NFC-normalizes first and counts each emoji grapheme as 2. Allan's
  paste into X's composer (V11) is the real check.
- **F15:** the spec's reason for not building usage cards is corrected. Snap and target data *is*
  stored (`off_snp`, `rec_tgt`); usage cards are left out for scope, not for lack of data.
- **F16:**
  - Reuse `NextGames` (America/New_York) for the next game date.
  - Cup-final nights are included, because Sleeper scores them.
  - Injured key players show as DNP.
  - Search rank has no ties.
  - In default mode the trending card comes back whenever a snapshot exists, even when the night
    isn't READY, since it doesn't depend on the night. Each player's outcome is then `NO_PERIOD`,
    and the page shows the card as adds only.

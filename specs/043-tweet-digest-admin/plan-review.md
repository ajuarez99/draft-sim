# Adversarial plan review: spec 043 (Morning Tweet Digest)

**Reviewed:** 2026-10-20, cold, against the worktree code and the local 5433 DB
(`psql -h 127.0.0.1 -p 5433 -U draftsim -d draftsim`). No code was written.

Every finding says whether it is **measured** (a query or code read, shown) or **inferred** (no
execution). Severity is how badly the plan as written would ship something wrong.

---

## F1 — HIGH — A missing points field means 0 points, not "unscored" (measured)

**Claim (plan note 3, R1):** "points = `stats[POINTS_KEY]`; absent ⇒ row excluded and counted in
`unscoredRows`", "never treated as 0".

**Found:** Sleeper leaves out zero-valued fields, `pts_std`/`pts_ppr` included. So a missing field
is exactly a 0-point game the player **did play**:

- No row anywhere has `pts_std = 0` (NBA 2025: 0 zero rows, 157 negative). Absent is how Sleeper
  writes zero.
- NBA 2025: 993 of 29,143 rows have no `pts_std`. 168 of them have non-zero box stats that net to 0
  under Sleeper's formula, e.g. `{"sp":194,"reb":1,"to":1,...}` (1 − 1 = 0) or `pts 4, to 2`
  (0.5·4 − 2 = 0). The rest are short-minute rows like `{"sp":187,"plus_minus":-5}`.
- NFL 2026, fantasy positions: WR 296/962, TE 284/637, RB 163/630 rows have no `pts_ppr`. Samples
  are `gp:1, off_snp:10, …` (played, recorded nothing). Only 2 NFL rows have scoring stats but no
  `pts_ppr`, and both have `pts_std: -1` (a −1 offset by +1 PPR, so PPR = 0).
- `player_game` holds **played** games only: an entry that wasn't a play goes to `player_absence`
  (`PlayerGameIngestService.java` "ENTRY_WITHOUT_PLAY" branch; `BasketballRules.playedIn` = non-empty
  stats, `FootballRules.playedIn` = `gp > 0`).

**Consequence:** with the plan as written, `trending()` (`PlayerSpotlightService.java:373-389`)
finds no scored game for a player who played and scored 0. His team is in `opponents()` (built from
*all* rows), so he becomes `DID_NOT_PLAY`, and the card shows "DNP" for someone who played. That is
the reverse of FR-006. Key players get the same mislabel, and their season means come out biased
upward (0-point games dropped).

**Amend:** absent ⇒ 0.0. The simplest version that also meets FR-007's "same scoring math as the
rest of the app": reuse `scoreAll` / `GameScoringService.score` with the one-key scoring map
`{"pts_std": 1.0}` (NBA) or `{"pts_ppr": 1.0}` (NFL). `score()` iterates the scoring keys, so an
absent stat contributes 0 (`GameScoringService.java:60-67`). Drop `unscoredRows` from the contract,
or redefine it as "rows whose player id doesn't resolve" (see F4).

## F2 — HIGH — Nothing fetches the night's stat lines on a schedule, and the plan can't tell "not fetched" from "no games" (measured)

**Claim (spec Assumptions; plan "no new upstream call"):** per-game stat lines are "already fetched
daily for both sports (specs 009, 014)".

**Found:** the daily cron (`.github/workflows/daily-refresh.yml`, 11:00 UTC) runs
`DailyRefreshService.runAll` (`refresh/DailyRefreshService.java:109-120`): PLAYERS, BOARD, TRENDING,
SEASON_PROJECTIONS, PROJECTION_SNAPSHOT. **No per-game step.** `refreshSportSeason` is reached only
from a league page visit (`LeagueRefreshService.java:231`, stale after 1 h) or the admin
`/api/ingest/player-games/{id}` route (`IngestController.java:119`).

`choosePeriod`'s NIGHT rule calls a night complete only if its week was fetched at or after
D+1 10:00 UTC (`PlayerSpotlightService.java:510-521`). So on a morning when nobody has opened a
league page since 10:00 UTC, last night is never complete. Locally right now: NBA week 1 was fetched
2026-10-20 23:48 UTC, mid-slate. The schedule shows SAS–OKC `pre_game`, NYK–PHI `in_game` and
DET–BOS `complete`, and `player_game` has rows for only 2 of those 3 games.

The plan's status rule (note 1) makes this worse: "`NO_GAMES_LAST_NIGHT` (… no rows dated
yesterday)". When no refresh has run since the games, there are **no rows dated yesterday**, so the
page says "No games last night" on a night with a full slate. That breaks FR-011 and the spec edge
case "Night not finished … says … when the stats were last fetched". The contract also has no
fetch-time field at all.

**Amend:**
1. Decide "were there games yesterday" from `sport_schedule` (it's stored, keyed by date, and its
   dates match `player_game` for 1,232/1,232 NBA 2025 games and 93/93 NFL 2026 games). Rows decide
   only "are they fetched and complete".
2. Add a status for scheduled games that are not yet fetched, or not complete (e.g.
   `NIGHT_NOT_FETCHED`), and add `statsFetchedAt` (the covered week's
   `sport_week_stats.fetched_at`) to `SportDigest`.
3. Give the page a way to fix it without leaving: a "Fetch last night's stats" button. The admin
   token is already in hand, and the existing admin route `POST /api/ingest/player-games/{leagueId}`
   works (it needs any league id of the sport). Or make the digest endpoint itself call
   `refreshSportSeason` when the covered week is stale. That would break the plan's "no writes"
   claim, so it is Allan's call. Note it in the spec either way. Adding per-game refresh to the
   daily cron is a third option, but it changes spec 009.

## F3 — MED — SC-003 can't be met as worded; V6 quietly swapped its yardstick (measured)

**Claim (SC-003):** every figure "matches the same player's figure on the app's existing stats
pages under the same scoring". **Quickstart V6** checks against raw `stats->>'pts_std'` instead.

**Found:** the app's pages score with the **league's** map (`loadPeriodRows` →
`leagues.scoringOf(league.id())`, `PlayerSpotlightService.java:290`). The "Ball Knowers" NBA 2025/2026
league's map differs from `pts_std`: `dd 2`, `td 3`, `bonus_ast_15p 2`, `bonus_reb_20p 2`, `ff -2`,
`tf -2`. Measured fit: across 28,150 NBA 2025 rows, `pts_std` = 0.5·pts + reb + ast + 2·stl + 2·blk −
to + 0.5·tpm + dd + 2·td + 2·bonus_pt_40p + 2·bonus_pt_50p, **with no foul penalties (28,150/28,150)**.
The local "Test" NBA league (probably a fresh default, *inferred*) does score `ff -2, tf -2`, so even
"Sleeper default" ≠ `pts_std` for the ~1.2k rows with a technical or flagrant foul. A smaller point:
R1's formula "0.5·pts + 1·reb + 1·ast" is right for its one sample only.

**Amend:** reword SC-003 to "matches Sleeper's own `pts_std`/`pts_ppr` (or the one-key scoring of
F1) for the same player and date", which is what V6 tests. Label the basis honestly on the page
("Sleeper standard points"). Write down in R1 that it ignores technical and flagrant fouls, and that
a default-created Sleeper league may charge them. Replace R1's formula with the measured one.

## F4 — MED — TEAM_ rows and unresolved ids dominate any raw "top performers" query (measured)

**Found:** `player_game` stores team-total rows: `TEAM_DET` 155.0 `pts_std` on 2026-10-20, and
`TEAM_NE` 144.86 `pts_ppr` on 2026-10-11. The `player` table has none of them (`count(*) where
sleeper_id like 'TEAM_%'` = 0). NFL 2026 also has 6,229 rows (IDP/OL/team) with no `player` row. The
app only excludes them because `scoreAll` skips unresolved ids (`PlayerSpotlightService.java:298`).
The plan never says so, and V7's "SQL `order by points desc`" would put TEAM_ rows at the top.

**Amend:** state in the plan that top performers, key players and position leaders use
player-resolved rows only (reuse `scoreAll`). Write V7's SQL as a join to `player`. Also decide on
NFL `DEF`/`K` (they resolve and carry `pts_ppr`, 186 rows each): FR-009 says "all players", so keep
them or exclude them on purpose. Don't let it happen by accident.

## F5 — MED — Key-player design silently departs from FR-008 (code/spec read)

**Spec FR-008:** start from preseason rank or ADP, **switch to season-average fantasy points** after
a hand-set point; the expectation is the season average, **or the projection** before one exists.
**Plan R4:** always the newest `sleeper_search_rank`, with no switch-over constant; the expectation
falls back to **last season's mean**, and projections are rejected.

**Amend:** either amend the spec, with a dated note (search rank is re-captured daily, `captured_on`
2026-10-20, so it already moves with the season; last-season mean replaces projection because NBA
2026 projections lack `pts_std`), or build the switch-over as the spec says. The plan should not
change the spec quietly.

## F6 — MED — Key-player expectation leaks the night itself, and later games on `?date=` (inferred)

**Plan note 5:** expectation = "`forPlayers` for this and last season" mean.

**Found:** `forPlayers` returns the whole season (`PlayerGameRepository.java` `forPlayers`), so the
mean includes the night being judged. With `?date=` it also includes every game after that date.

**Amend:** compute the expectation from games with `game_date < covered date` only, and count
`KEY_SEASON_MIN_GAMES` over those.

## F7 — MED — "Team played" uses the current team, which is wrong for traded players on past dates (measured)

**Found:** `player.team` is current. Giannis Antetokounmpo is now `MIA` (top-7 search rank), so on
any 2025-season `?date=` his MIL games don't count as "his team played", and he is dropped even
though he has a row. A related fix: the plan says "`team` or `opponent` of any row", but
`player_game` has no team column (`PlayerGameRepository.Row`). The night's teams come from
`opponent` alone, which does cover both sides because every team's players name the other.

**Amend:** include a key player if he **has a row** that night, OR his current team is among the
night's opponents (DNP). Use the newest `sleeper_search_rank` snapshot with
`captured_on <= covered date` for historical dates.

## F8 — MED — The headline trending card will mostly read "no game" on small slates (measured)

**Found:** the current NBA trending top 10 teams are CHI, LAC, CHI, LAC, MIA, OKC, PHX, LAC, LAC,
BOS. On 2026-10-20 (DET–BOS, NYK–PHI, SAS–OKC) only 2 of the 10 had a game. `trending()` labels the
others `NO_GAME` (`PlayerSpotlightService.java:385`), but the spec and the card wording only cover
"DNP". The plan doesn't say how the card text renders NO_GAME. It also doesn't say whether the card
appears when 4 of 5 entries never had a game.

**Amend:** specify NO_GAME's text ("no game", never "DNP" and never 0). Add a rule for a mostly-idle
card, e.g. show it with a note, or let Allan see "2 of 5 played" before copying. Also say that the
adds window is the 24 h before `fetchedAt`, and the fetch is re-run on visit
(`LeagueRefreshService` → `TrendingRefresh`), so it can be later than "last night".

## F9 — MED — Quickstart V3 expects the wrong state (measured)

**V3:** `?date=2026-10-20` → "NBA `READY`, 2 games".

**Found:** the schedule has **3** NBA games on 2026-10-20, and the stored week was fetched before the
10-21 10:00 UTC cutoff, so locally that date is incomplete today. Even after a refresh,
`gamesCount` would be 3.

**Amend:** V3 expects `READY`, 3 games, only after a refresh run after 2026-10-21 10:00 UTC (note
the fetch time in verification.md). Before then, expect the incomplete status from F2.

## F10 — LOW — `isComplete` is private (code read)

The plan reuses "package-private static helpers", but `?date=` mode needs completeness for an
arbitrary date, and `isComplete` is `private static` (`PlayerSpotlightService.java:510`). That means
a visibility change to the spotlight file, so the build stage should know it touches that file. It
also means `PeriodRows`/`ScoredGame` (package-private records) are part of the reuse surface.

## F11 — LOW — `game_date` is the US local date, and "yesterday in ET" lines up (measured; no change)

GSW@LAL on 2025-10-21 tipped off at 10 pm ET (02:00 UTC the 22nd) and is stored as
`game_date 2025-10-21`. The upstream `date` field is the US calendar date (`toRow`), not UTC, so the
America/New_York "yesterday" matches. The ingest's own "today" is UTC, but that only guards future
games. One caveat carries over: the cutoff `NIGHT_COMPLETE_HOUR_UTC = 10` is still unmeasured
(spec 014 V6).

## F12 — LOW — `/admin/digest` sits behind the Sleeper sign-in gate (code read)

`App.tsx:109-183` renders `<SignIn />` instead of `<Routes>` when signed out, inside `AppShell`. So
a signed-out visitor sees the sign-in screen, not the "token prompt only" V8 expects, and Allan must
be signed in to Sleeper to use the page. SC-004 still holds, because the backend gates. Fix V8's
expectation, or mount the route outside the user gate on purpose.

## F13 — LOW — Send the token through `apiFetch`, not bare `fetch` (code read)

`apiFetch` (`api.ts:220`) adds `Authorization: Bearer` when `VITE_API_TOKEN` is set, plus
`X-Sleeper-User`. `ApiTokenFilter` covers `/api/*`. `getAdminDigest` must call
`apiFetch(path, {headers: {'X-Admin-Token': t}})` so it keeps working if `API_TOKEN` is ever turned
on. CORS already allows `X-Admin-Token` (`WebConfig.java:60`).
`api.setupRoute.test.ts` only asserts that the *setup* calls don't send it, so there's no conflict.
DEPLOY.md:169 still says ADMIN_TOKEN is "Not yet set in production". If that's still true, the page
will 403 in prod (*unverified*; check Railway before relying on it).

## F14 — LOW — Weighted-length rule: right in outline, needs NFC and conservative emoji handling (inferred from twitter-text v3 config)

The ranges and weights in R5 match twitter-text v3 (default weight 200, ranges weighted 100, emoji
sequence = 200). Two gaps:
- twitter-text **NFC-normalizes** before counting. A decomposed "Jokić" (c + U+0301) counts 2
  un-normalized and 1 normalized. Normalize with `text.normalize('NFC')` both for counting and for
  the copied text.
- A naive "outside ranges ⇒ 2 per code point" over-counts `⭐️` (U+2B50 + U+FE0F = 4). That is safe.
  It under-counts the few emoji inside 0–4351 (©, ®, keycaps). Avoid those, or segment with
  `Intl.Segmenter` and count any grapheme with `\p{Extended_Pictographic}` as 2.

V11 (paste into X's composer) is still the real check.

## F15 — LOW — The spec's reason for skipping usage stats is wrong (measured)

The spec says snap/target share is "not in the stored stat lines in a reliable form". NFL rows do
carry `off_snp`, `tm_off_snp`, `rec_tgt`, `st_snp` in every sampled row (coverage not measured).
Keeping it out of scope is fine; correct the reason.

## F16 — LOW — Smaller things (code read / measured)

- `nextGameDate`: `ingest/NextGames.java` already works in America/New_York. Reuse it rather than a
  new schedule read. If you do add a read, don't filter by season, so the off-season can find next
  season's first date.
- NBA Cup finals count in Sleeper fantasy but are excluded from real-basketball figures
  (`config/weights.yml` `nba-games.excluded-game-ids`). A digest that compares against "stats
  pages" on that night should say which rule it follows. Fantasy (include) is the consistent choice.
- The key-player list includes injured stars (NFL #12 Achane `IR`, #15 Lamar `Out`). Those are
  weekly DNP lines. Acceptable under FR-006, but worth one line in the spec.
- `sleeper_search_rank` `adp` is a dense ordinal 1..N with no ties (NBA 959/959 distinct, NFL
  2002/2002), so no tie rule is needed. No player has more than one row per date (measured: 0), so
  the "sum several rows" path is dead but harmless.
- Contract: say whether `trending` is returned when status ≠ READY in default mode (the table says
  "every non-READY status carries no entries", but trending doesn't depend on the night).

---

### Checked and fine (measured)

- `pts_ppr` is true PPR: `pts_ppr = pts_std + rec` on 2,283/2,283 NFL 2026 rows.
- Key names: NBA 3PM is `tpm`; NFL interceptions thrown are `pass_int` (and `int` exists separately
  for the defensive stat).
- Sport codes are lowercase (`Sport.code()`, `nfl`/`nba`), matching the DB.
- NFL dates split Thu/Sun/Mon cleanly inside one week (week 6: 10-15 Thu ×1, 10-18 Sun ×12,
  10-19 Mon ×1), so NIGHT mode works for NFL. `choosePeriod(true, …)` doesn't depend on the sport.
  `trending()` works with an empty ownership map (`getOrDefault(…, UNROSTERED)`).
- `?date=2026-04-12` (NBA 2025 finale): 15/15 games stored, weeks final. V5 is viable.
- `/api/admin/**` is gated (`WebConfig.java:42`), and the test JVM sets `draftsim.admin.token=it-admin-token`
  (`build.gradle.kts:40`).

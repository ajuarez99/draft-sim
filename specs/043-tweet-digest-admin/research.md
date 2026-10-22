# Research: Morning Tweet Digest (spec 043)

Each item: Decision / Rationale / Alternatives. "Measured" means a query was run on the local
5433 DB on 2026-10-20; "assumed" means it was not.

## R1. Which points number is "Sleeper default scoring"

**Decision:** read Sleeper's own computed points from the stored stat line — `pts_std` for NBA,
`pts_ppr` for NFL — rather than re-scoring with a hand-written default scoring map.

**Measured:** every sampled `player_game.stats` row carries them. NBA 2025 sample: `pts 5, reb 3,
ast 1` → `pts_std 6.5` (= 0.5·pts + 1·reb + 1·ast, Sleeper's default points league), and a
separate `pts_std_dfs 10.1` (DFS weights). NFL 2026 sample: `pts_std`, `pts_half_ppr`, `pts_ppr`.

**Rationale:** the clarified answer was "match what followers see in their Sleeper apps". The
number Sleeper itself computed *is* that, with no risk of our map drifting from theirs. The app
already relies on these upstream payloads.

**Assumed, not measured:** that Sleeper's default *new NFL league* is PPR. If Allan prefers half
PPR it is one constant (`NFL_POINTS_KEY`). The page labels the basis ("Sleeper PPR",
"Sleeper default points").

~~**Unknown until build:** how many rows lack the field. A row without it is excluded and
counted (`unscoredRows`), never treated as 0.~~ **Amended after review (F1, measured):** Sleeper
leaves out zero-valued fields. 993 played NBA 2025 rows have no `pts_std`, no row has
`pts_std = 0`, and about 27% of NFL 2026 skill-position rows lack `pts_ppr`. A missing field
means **0 points**. Scoring goes through `scoreAll` with the one-key map `{pts_std: 1}` or
`{pts_ppr: 1}`. Also corrected: `pts_std` is Sleeper's default points-league score (measured
formula in plan-review F3), not a formula of ours.

**Alternatives:** re-score with `GameScoringService` and a default map (rejected: a second
definition of Sleeper's rule, the multi-sport-landmines class); use one of Allan's leagues
(rejected at clarify).

## R2. Which night a sport's digest covers, and "complete"

**Decision:** reuse `PlayerSpotlightService.choosePeriod(true, …)` for **both** sports. Its NIGHT
mode is sport-agnostic: newest `game_date` whose week was fetched at or after D+1 10:00 UTC. For
NFL this turns Thursday, Sunday and Monday into separate digest days, which is what a morning
post covers. The NFL week number comes from the rows' own `week`.

**"Last night" rule (FR-011):** yesterday = today's date in America/New_York minus one day. If the
newest complete night ≠ yesterday, the section returns `NO_GAMES_LAST_NIGHT` with the last slate's
date and the next scheduled game date (from `sport_schedule`), and no cards. An explicit
`?date=` asks for that date regardless (labelled with its date), so a missed morning can still
be posted.

**Known risk carried from spec 014:** `NIGHT_COMPLETE_HOUR_UTC = 10` is still an unmeasured
assumption (owed check V6 after 10-20). This feature inherits it; it does not fix it.

**Alternatives:** WEEK mode for NFL (rejected: a Monday morning post about "week 7 so far" mixes
Thursday's games into Sunday's recap).

## R3. Trending window is not "last night"

**Measured:** `sport_trending_fetch` holds one snapshot per sport (`lookback_hours 24`, fetched
2026-10-20 23:48 UTC for both). Adds are counted over the 24h *before the fetch*, which is not the
game night. Only the latest snapshot is stored.

**Decision:** the trending card uses the latest snapshot and the page states its window and fetch
time. For an explicit past `?date=` the trending card is `TRENDING_NOT_HISTORICAL` (no card)
instead of pairing an old night with today's adds. A snapshot older than its lookback is flagged
stale (the spotlight's existing rule).

**Reuse:** `PlayerSpotlightService.trending(...)` (static) already classifies PLAYED /
DID_NOT_PLAY / NO_GAME and keeps Sleeper's order; we pass it rows scored by R1 and an empty
ownership map.

## R4. Key players

**Decision (clarified):** top `KEY_PLAYER_COUNT = 15` (hand-set) players by the newest
`adp_snapshot` row with `source = 'sleeper_search_rank'` for the sport. **Measured:** NBA captured
2026-10-20, 959 players, top = Wembanyama, Jokić, Dončić, SGA, Cunningham…; NFL captured
2026-10-20, 2002 players.

Expectation per player:
1. this season's mean points per played game, when he has ≥ `KEY_SEASON_MIN_GAMES = 5` (hand-set);
2. else last season's mean, when ≥ 10 games then (hand-set);
3. else none → verdict `NO_BASELINE`, shown without ✅/❌.

Verdict `ABOVE` (✅) when the night's points ≥ expectation, else `BELOW` (❌). Only key players
whose team played that night appear; a key player whose team played but who has no row is DNP.
The page shows which expectation each verdict used.

**Alternatives:** `blend` ADP (NBA last captured 2026-10-11, NFL 09-28 — stale, rejected);
projections (`player_season_projection` NBA 2026 rows carry no `pts_std`, so would need
re-scoring — rejected for R1's reason).

## R5. Where the tweet text is composed

**Decision:** backend returns structured entries; the frontend composes text in a pure module
(`web/src/digest/tweetText.ts`) with unit tests. Copy wording is the part Allan will want to tweak,
and it's presentation.

**280 limit:** X counts a weighted length — code points in the Latin/general-punctuation ranges
weigh 1, everything else (including every emoji) 2. We implement that simplified rule
(twitter-text v3 ranges 0–4351, 8192–8205, 8208–8223, 8242–8247 weigh 1; an emoji sequence counts
2). **Assumed:** good enough for our characters (names with diacritics, a few emoji); verified by
pasting real cards into X's composer during live verification, not before.

## R6. Admin access

**Decision:** route under `/api/admin/digest`, already covered by `AdminGateInterceptor`
(`WebConfig` pattern `/api/admin/**`) and already CORS-allowed (`X-Admin-Token`). The web page at
`/admin/digest` asks for the token once, keeps it in `sessionStorage` (per tab session, gone on
close), and sends it only on digest requests. A 403 `admin_token_required` clears it and re-asks.
No nav link anywhere.

**Alternatives:** a new role on Sleeper sign-in (rejected: no roles exist; spec assumption).

## R7. Stat line content

NBA headline keys: `pts, reb, ast, stl, blk, tpm` (3PM; key name to confirm at T001), non-zero
only. NFL: `pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td`, non-zero only.
Backend sends just these keys (mutable map — the `Map.of` null rule); frontend formats.

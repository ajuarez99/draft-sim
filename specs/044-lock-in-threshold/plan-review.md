# Adversarial plan review: spec 044 (lock-in threshold)

This review read spec.md, plan.md, research.md, data-model.md, contracts/lock-in-api.md and
quickstart.md cold on 2026-10-21. It checked them against worktree `044-lock-in-threshold` at `7064eb5`
(the same as `main`, with spec 044 untracked) and against the local Postgres on 5433, using read-only
queries. It ran before any code was written.

Each item is labelled **measured** (run today, with the result quoted) or **read** (from the code, not
executed). The measurements come from throwaway scripts in the session scratchpad
(`review044*.py`). They reimplement `GameScoringService.score` key by key, the same way the research
script did, over fresh exports of `player_game`, `roster_week_points`, `league` and `roster_season`
for leagues 211 and 212.

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| `GameScoringService.score(scoring, stats)` rounds to 2 dp and sums in scoring-key order | `engine/GameScoringService.java` (read) | ✅ The 0.005 tolerance is sound against a 2-dp value. |
| `oneGameShare` uses tolerance 0.005 and a starter's `players_points` | `PlayerTrendsService.java:535-565` (read) | ✅ It uses `<0.005` and `WeeklyReportService.startersOf`, and needs at least 2 games. |
| `breakdownsFor(leagueId, season)` returns week, roster, players_points and starters | `RosterWeekPointsRepository.java:135-147` (read) | ✅ |
| `seasonPlayerGames` / `seasonToken` exist, and `SeasonGame` carries `gameDate`, `opponent` and `week` | `PlayerGameRepository.java:229-272` (read) | ✅ |
| `ProjectionSnapshotRepository.forAccuracy` / `stamp` exist | `:135`, `:165` (read) | ✅ |
| League-access check | `ProjectionAccuracyController` → `LeagueMembership.canSee` (`store/LeagueMembership.java:167`), 404 for both unknown and not visible (read) | ✅ It's copyable as is. |
| `game_mode = 1` on all NBA rows | `league.settings_json` (measured) | ✅ It's `1` on 210, 211, 212 and also 34534 (a second, unrelated NBA 2026 league). It's absent on every NFL row. |
| `player_game` holds played games only | measured | ✅ Every exported row has `sp > 0`. Filtering on `sp > 0` changed no classification. |
| End-of-week `starters` fully explains the roster's total | `starters_points` vs Σ `players_points[starters]` (measured) | ✅ It matches in **288/288** (2024) and **252/252** (2025) roster-weeks. Sleeper's own total is built from the end-of-week starter list, so attributing by it is attributing the way Sleeper scores. |
| Same-day doubles | measured | ✅ 0 multi-game starter-weeks have two games on one date. `(game_date, game_id)` ordering has no practical ambiguity. |
| `manager_id` null | `roster_season` for 211/212 (measured) | ✅ 0 of 24. The column is nullable, though (`\d roster_season`), so the code must still handle it. |
| Spec 036's restricted map is the right tool | `ProjectedCategories.restrict(scoring, projectedKeys)`, `ProjectionAccuracyService.java:170-174` (read) | ✅ The approach is right, but see F9 for the signature. |
| No migration needed | (read) | ✅ |

## Findings

### F1. SHOULD-FIX: the All-Star game leaks in as a fake "later game"

**Evidence (measured).** `player_game` holds the All-Star game rows (opponents `CHK`/`SHQ` in 2024
and `STR`/`STP` in 2025, 7–8 rows each). Sleeper **never** credits them: 0 of 30 starter/bench cases
matched an All-Star score. The plan's inputs are `seasonPlayerGames` "minus the excluded-game-ids",
which does **not** drop All-Star rows. Only `NbaGameLines.of` does that (`engine/NbaGameLines.java:67`,
opponent not a season team code). The research script read raw rows too. Classification with raw
rows versus with All-Star dropped:

| | lock | rodeOut | single | tieNonLast |
|---|---|---|---|---|
| 2024 raw → no All-Star | 1356 → 1351 | 945 → 949 | 121 → 122 | 15 → 14 |
| 2025 raw → no All-Star | 1195 → 1188 | 890 → 894 | 89 → 92 | 10 → 10 |

Under raw rows, 6 (2024) and 7 (2025) "provable locks" are rode-outs or single-game weeks with the
All-Star game as their "later game". `beatenLater` / `bestLater` would also compare against an
exhibition score.

**Amend:** Drop games whose opponent isn't a season team code, using the same rule as `NbaGameLines`.
The simplest way is to read `SeasonBoxCache.get(NBA, season)` (see F3) and filter `games()` with that
rule. Don't use `lines()` as is (see F2). Re-run R2's table with this rule and replace the numbers
(quickstart check 2 compares to them).

### F2. SHOULD-FIX: excluding the Cup final is wrong for this feature, because Sleeper's fantasy scoring credits it

**Evidence (measured).** In 2025 week 9, player 1831 was a starter with credit **30.5**. His games
that week were 12-16 `1305814461864501248` (the configured Cup final) = 30.5, then 17.0, 5.0 and
17.0. The Cup final is the only match. In 2024, one other starter's credit uniquely matched the 2024
Cup final as well (`cup, starter, match, no-other`: 1 per season). So Sleeper's fantasy week counts
the Cup final as a game. Applying `weights.yml` `nba-games.excluded-game-ids` turns that 2025 lock
into UNMATCHED and turns a 2024 lock into SINGLE_GAME. The spec's edge case ("treated the same way
every other stat in the app treats them") and plan step 2 ("minus the excluded-game-ids, the same
filter as other `player_game` readers") both get this backwards. The exclusion exists because *the
NBA* doesn't count the game in regular-season stats (`weights.yml:239-243`). The question here is
what *Sleeper's fantasy matchup* counted, which is the reason `SeasonBoxCache` keeps raw games for
`oneGameShare` (`SeasonBoxCache.java:217`).

**Amend:** For candidate matching, later games and `bestLater`, keep the Cup final. For the stand-in
baseline, decide explicitly. Including it is simplest, and it's one game. Write down in research
and in the spec edge case that this feature deliberately differs from the real-basketball pages, and
why. Drop the excluded-ids filter from the plan's inputs and from the cache stamp. F1 and F2 together
mean the matcher's game set is "raw minus All-Star": neither of the two existing sets.

### F3. SHOULD-FIX: reuse `SeasonBoxCache` instead of a fresh `seasonPlayerGames` read

**Evidence (read).** `SeasonBoxCache.get(sport, season)` (`:118`) already holds the season's raw
`SeasonGame` rows, compacted and token-checked. Trends and the player pages load it. The plan reads
`PlayerGameRepository.seasonPlayerGames` directly, which re-parses ~29k JSON rows on every cache miss.
Spec 036 measured its similar full read at ~1.4 s (`ProjectionSnapshotRepository.stamp` javadoc), so a
fresh read puts SC-005's 2 s at risk on a cold hit. Using the cache also makes its `SeasonToken` the
natural `player_game` part of the stamp.

**Amend:** In plan step 2, the inputs become `boxCache.get(NBA, season).games()`, filtered per F1/F2.
Measure cold and warm times in verification. The research's "≪1 s in Python" was not timed. The
script has no timer, so the claim is a guess and should be labelled as one.

### F4. SHOULD-FIX: the payoff grid's "a later game beat it" overstates the value of waiting

**Evidence (measured, 2025, stand-in baseline, with F1 applied).** A manager who passes on a lock
does not get the best later game. They get whatever they lock next, or by default the last game
(see F5). Comparing "any later game > lock" with "the last game > lock":

| band | 2 games left: any / last | 3+ left: any / last |
|---|---|---|
| 0–10% | 0.47 / 0.29 (n=17) | 0.71 / 0.43 (n=7) |
| 10–25% | 0.28 / 0.13 (n=97) | **0.53 / 0.17** (n=30) |
| 25–50% | 0.31 / 0.18 (n=121) | 0.39 / 0.12 (n=66) |

With 1 game left the two are identical. With more left, "any later" is a hindsight maximum and roughly
triples the apparent cost of locking at 10–25% with 3+ left. This is lessons class 5: the statistic is
correct, and a reader asking "should I have waited?" will read it as the answer, which it isn't.

**Amend:** For the payoff cell, either (a) show "the last game beat it", meaning what riding it out
would have paid, as the headline rate, with "any later game beat it" beside it labelled "in
hindsight", or (b) keep "any later" but caption it as the best case, not what waiting gets you.
Option (a) is the honest one. It changes what US3's table *means*, so it needs Allan's call. Also
consider widening the payoff population to every non-last starter game (locked or not), so the table
isn't conditioned on managers' own choices. That's optional, and it should be named either way.

### F5. SHOULD-FIX: R3's "default is unknown" can now be measured, and it's the last game. "Unmatched" isn't an inference failure.

**Evidence (measured).** `players_points` also carries **bench** players, who aren't locked
(`rv_rwp` rows, for example `"1934": 10.5` on a bench player). For bench players with 2 or more played
games, Sleeper credited:

| | last | first | middle | tie | 0 with no 0-point game |
|---|---|---|---|---|---|
| 2024 | 860 | 1 | 3 | 56 | 96 |
| 2025 | 848 | 13 | 4 | 44 | 120 |

So the default is the **last game** in 99.5% / 98.1% of uniquely matched bench weeks. The handful of
first/middle cases fit a player who was locked while starting and then benched. The "0 with no zero
game" bench cases fit "the last *scheduled* game was a DNP". Every starter UNMATCHED is the same
thing: **44/44 (2024) and 14/14 (2025) unmatched starter credits are exactly 0.0**, and only 6 and 4
of them fall in the season's last week. So:

- R3 goes from "assumed, partially supported" to *measured*: the default is the last scheduled game.
  That's the strongest support FR-002's premise could get. Put it in research R3 and in the UI caveat.
  It also contradicts the memory note "not first, last, or highest", which pooled locked starters
  into "default".
- R2's explanation that unmatched rows "cluster in late/playoff weeks" because of locally partial last
  weeks is a guess dressed as a finding, and it's mostly wrong: 6/44 and 4/14 are in the last week.
  The measured explanation is "credited 0 = defaulted onto a missed last game".
- SC-002 as amended (unmatched ≤ 3%) is fine, but the label in `totals` / FR-010 should say what
  these are: split `unmatched` into `creditedZeroNoGame` (a default onto a DNP: a rode-out, not a
  failure) and `unmatchedOther` (a real inference failure, measured at **0** in both seasons).

**Amend:** Update R2, R3 and the caveats as above. In `CreditedGames`, classify credited == 0 with no
0-point candidate as `RODE_OUT_DNP`, or a separately counted bucket, rather than UNMATCHED. Keep it
out of every margin.

### F6. SHOULD-FIX: the cache stamp misses inputs that change the output

**Evidence.**
- `roster_week_points` has **no** `fetched_at` / `updated_at` column (`\d roster_week_points`,
  measured). The stamp "count + max week" doesn't change when a stored week is rewritten in place.
  Spec 009 refetches non-final weeks and the last scored week through the correction window
  (`refresh/WeekFinality.java`), so a stat correction or a week turning final leaves a stale answer.
- Which weeks are *final* comes from `ScoredWeeks.of` (`engine/ScoredWeeks.java:56-68`), which reads
  `league_week_fetch.final` and `league_refresh.loaded_complete`. A week turning final can happen with
  no change to count or max week.
- The roster → manager map and display names (`roster_season`, `league_member`) aren't stamped.
- The plan lists the "snapshot stamp" and the `player_game` "seasonToken" separately.
  `ProjectionSnapshotRepository.stamp` already includes `player_game` max(fetched_at)/count, so one of
  them is redundant. That's harmless, but say which one.

**Amend:** Build the stamp from: `SeasonBoxCache` token (or `player_game` `seasonToken`), the
`roster_week_points` aggregate `count(*), sum(starters_points), md5(string_agg(players_points::text, ','
order by week, roster_id))` (cheap at 288 rows), `ScoredWeeks.Snapshot.finalWeeks`, the snapshot
stamp, the scoring hash, `game_mode`/`playoff_week_start`, and a `roster_season` (manager_id) digest.
`weights.yml` constants are startup-bound, so they need no stamp (and the excluded ids drop out per F2).

### F7. SHOULD-FIX: the 2026 partial week depends on `ScoredWeeks`' fallback

**Evidence (measured).** League 210 (NBA 2026) has `league_week_fetch` TRANSACTIONS rows only, with
**no POINTS rows**, and `loaded_complete = f`. `ScoredWeeks.of` treats "no POINTS rows at all" as
**every stored week final** (`:62`, javadoc case 2). So as soon as 2026 week 1's partial
`roster_week_points` rows land before a POINTS fetch row exists, they read as final. That leaks the
in-progress week, where every "last game" is really "the latest game so far". It flips rode-outs into
locks and vice versa. Locally 210 has no `roster_week_points` rows yet, so this can't be shown today.
It is the exact path prod will take this week.

**Amend:** Use `ScoredWeeks.of(league.id()).finalWeeks()`, as the plan intends. When the league isn't
`complete()`, **also** exclude any week ≥ `currentLeg` (`LeagueRepository.currentLeg`, `:394`) as a
belt-and-braces guard. Add a test that a non-complete league with no POINTS rows doesn't publish its
`leg` week. Verify on prod-shaped data after week 1 closes (~10-26).

### F8. SHOULD-FIX: two rules for "this league credits one game a week"

**Evidence (read).** Trends already decides the same fact by measurement: `oneGameShare ≥
draftsim.player-trends.one-game-share` (0.9, `weights.yml:214`; `PlayerTrendsService.java:327`). The
plan gates on `settings_json.game_mode == 1`, which no Sleeper documentation was read for (R1) and
which no repo code reads today (grep: 0 Java/TS hits for `game_mode`). It's `1` on every local NBA row
(4 of 4, including an unrelated league), so locally it discriminates nothing. The two rules can
disagree, for example a `game_mode 1` league whose share is below 0.9, or the reverse. Then Trends
says "credits every game" while this panel shows locks. That's the memory's "two implementations of
one rule" class.

**Amend:** Gate on both signals and report which one failed, `NOT_LOCK_IN` with reason
`GAME_MODE` | `SHARE_BELOW_THRESHOLD`. The minimum is to compute this report's own share (lock +
rodeOut + ties over multi-game) and refuse below the Trends threshold. Add `LeagueRepository.gameMode(id)
→ OptionalInt`, never defaulted (memory "optional params that encode rules"). `LeagueRow` carries no
settings (`LeagueRepository.java:169-173`), so plan step 2's `settings.game_mode` needs this new
accessor. Gate ESPN rows (`!league.isSleeper()`) with their own reason, not the "not lock-in" text.

### F9. SHOULD-FIX: delegating `oneGameShare` is under-guarded

**Evidence (read).** `oneGameShare` counts a week as matched when **any** game matches, so ties count
in the numerator. It is order-independent, it reads raw games including the All-Star game and Cup
final, and it reads **every** stored week, not only final ones. Its only test
(`PlayerTrendsServiceTest.java:715-735`) has no tie, no All-Star row, all games on one date per week,
and no non-final week. A rewrite onto `CreditedGames` that counted only unique matches, or applied
F1's All-Star filter or the scored-week filter, would pass that test and change the prod figure
(ties are ~3% of multi-game weeks).

**Amend:** `CreditedGames.match` must take the game list as given. `oneGameShare` keeps passing raw
`games()` and all weeks, with share = (LOCK + RODE_OUT + TIE_* [+ zero-credit-on-zero-game]) /
multi-game. Before the rewrite, add a tie case and an All-Star-row case to the existing test. Also
record `oneGameShare` for 211 and 212 before and after the change, and assert they're equal in
verification. The plan's "its existing test is the guard" overstates that test.

### F10. SHOULD-FIX: early-season stand-ins rest on 1–4 games

**Evidence (measured, F1 applied).** Of the provable locks, 40 (2024) / 35 (2025) have no prior game,
and **90 / 92 more have fewer than 5 prior games**. That's ~7–8% of locks, measured against a 1–4
game average. For 2026, that's essentially every lock in weeks 1–2, which is the only data the
season will have when this ships. A margin against a one-game average is noise that looks like a
number.

**Amend:** Add a hand-set `draftsim.lock-in.min-prior-games` (5 is suggested, labelled arbitrary).
Below it, count `noBaselineStandIn` and exclude the lock. The stand-in is already labelled; this keeps
it from being labelled *and* meaningless. Optionally, `projection_snapshot.season_avg_stats` (a
frozen pre-game season average, from `\d projection_snapshot`) is a 2026-only alternative. It's worth
a sentence in R5, not necessarily a build.

### F11. NIT: SC-001's "rerun the research script" doesn't compute what the plan builds

**Evidence (read).** `measure.py`'s `played()` reads `stats['min']`, which doesn't exist (R4 itself
says minutes are `sp`), so the stand-in's "played" filter is really "nonzero pts/reb/ast". The script
groups by `roster_id`, not manager. It keeps All-Star and Cup rows. Quickstart checks 1–2 say the
endpoint and the script "should agree exactly at 3 dp". They won't, by F1, and they might not by
`played()`.

**Amend:** Fix the script to the final rules (F1, F2, F5, F10) before using it as the hand
computation, or do the hand check in SQL. Update R2/R5's tables from the fixed run, and keep the old
ones struck through per the repo's "corrections are shown" convention.

### F12. NIT: `tieNonLast` is both in and out of the lock total

R2 says ties with all-non-last candidates are counted "in the provable-lock total and in a
`tieNonLast` count". data-model's invariant `lock + rodeOut + tieNonLast + tieWithLast + unmatched
== multiGame` and the contract's `lock: 1195` treat them as disjoint. **Amend:** Pick disjoint (the
contract's reading) and fix R2's wording. The UI can show "provable locks = lock + tieNonLast, of
which tieNonLast have no margin".

### F13. NIT: the per-player view will mostly say "too few"

**Measured (F1 applied).** Players with ≥ `min-locks` (10) locks: 62 of 204 (2024) and **38 of 227**
(2025). The per-player table is mostly "n locks, too few" rows, and the player page block (US2) shows
a median for only a minority of players. That's correct behaviour, not a bug. Spec 044 should say so,
so verification doesn't read it as a defect. The tempting fix of lowering `min-locks` is a
retune-to-look-decisive and should stay off the table. 24 / 56 players were locked by more than one
manager, so US2's cross-manager attribution has real cases to test against.

### F14. NIT: plan details to pin before tasks

- **Player page placement.** `PlayerPage.tsx` is tabbed now (spec 042: `TABS` at `:680`, with
  `overview|stats|log|schedule`). The plan says "a block on the player page" without naming the tab.
  Name it, likely `overview` or a new `lock-ins` tab, and remember the route gate is `stats`
  (`App.tsx:153`).
- **The `?player=` response** recomputes nothing (cached), but it ships the whole report
  (~230 player summaries plus the grid) to a page that needs one player. Either return only that
  player's summary plus `locks` when `player` is set, or say it was accepted.
- **Status vocabulary.** The plan's frontend text says the projection series is "TOO_EARLY". The
  data model has no such status (it uses `n` with a null median). Use one term.
- **Playoff flag.** Reuse `LeagueRepository.playoffFormat(leagueId)` (`:361`), not a second
  `playoff_week_start` reader. Consider flagging eliminated-team playoff weeks too. Their lineups are
  often unmanaged, which inflates rode-out counts.
- **Quantiles.** Python's `statistics.quantiles(n=4)` default is `method='exclusive'` (m = n + 1).
  Pin it with a known-answer test at n = 10 and n = 11, as planned.
- **Mid-season ownership change.** `roster_season` holds one manager per roster-season, so an
  orphaned roster taken over mid-season credits every lock to the final owner. It can't be measured
  from stored data. Name it as an assumption in the spec.
- **Scoring changes mid-season** would show as unmatched credits under the stored (final)
  `scoring_json`. That isn't seen today (F5: 0 real unmatched), but it's the alarm `unmatchedOther`
  exists to raise.
- **`Map.of`.** The watch list is right. Also `manager` / `name` from `RosterOwners.ownerNames`
  (`engine/RosterOwners.java:27`, reuse it rather than a new lookup) can be null for a member-less
  roster.

## Verdict

The plan is buildable and the core inference holds up better than the research claimed. The default
is measurably "the last game" (F5), end-of-week starters reproduce Sleeper's totals exactly, and the
real inference-failure rate is 0. There are no blockers. Four things should change before tasks:
the game set (F1 drop the All-Star game, F2 keep the Cup final), the stamp and partial-week guard
(F6, F7), the gate (F8, one rule with Trends), and the payoff definition (F4). F4 changes what US3's
table means, so Allan should decide it. The research tables (R2, R5) need re-running under the final
rules and visible correction (F11).

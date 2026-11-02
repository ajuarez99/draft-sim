# Research: Weekly median what-if (spec 054)

All findings dated 2026-11-02. Each says whether it was **measured** (run, with the output
summarized) or **read** (from code/docs, not executed).

## R1. Which seasons need a what-if on the requested league — measured

Walked the Sleeper chain from `1339351318115946496` via the public API (`/v1/league/{id}`):

| Season | Sleeper id | Status | `league_average_match` | `playoff_week_start` | Playoff teams | Rosters | Seed type | Divisions |
|---|---|---|---|---|---|---|---|---|
| 2026 | 1339351318115946496 | in_season | **1 (on)** | 20 | 6 | 12 | 0 | 0 |
| 2025 | 1229352720222134272 | complete | 0 | 19 | 6 | 12 | 0 | 0 |
| 2024 | 1141438340626231296 | complete | 0 | 22 | 6 | 12 | 0 | absent |

- **Decision**: 2024 and 2025 get the what-if. 2026 already plays median games, so its Sleeper W-L
  already includes them (spec FR-005, the "already on" case). This is not hypothetical for this
  league: it is the case the league is in right now, and most likely why the question came up.
- All three seasons are a plain best-record ladder with no divisions, so the playoff-line marker
  (FR-008) applies to every season of this league.
- 2024 and 2025 have 21 and 18 regular-season weeks respectively (`playoff_week_start − 1`).

## R2. Why the median column is dashed for older seasons — read

`LeagueHistory.tsx:393-396` uses the expected-wins response only when `expected.season === season`;
`ExpectedWinsService.forLeague` resolves one season (`LeagueSeasonResolver.Rule.PLAYED_WEEKS`).
The *Record vs all* column next to it has the identical gap from the identical cause.

- **Decision**: the history response carries its own per-season block computed from that season's
  games, and both the median and all-play columns read from it. Fixing median and leaving
  all-play dashed beside it would read as broken. **This widens the spec by one column**; the spec
  gets FR-014 with an "amended during plan" note.
- **Alternative rejected**: call `/expected-wins?season=` once per season from the page. It would
  need a new season parameter on the Luck endpoint (out of scope per spec), N extra requests, and
  still not give the what-if.

## R3. One median implementation — read

`ExpectedWinsService.median(List<Game>)` (`:169`) and `allPlay(...)` are static, package-private,
already unit-tested (`ExpectedWinsServiceTest`). The game-loading loop is inside private
`compute()` (`:398-409`): paired games for the league, regular-season bound, both scores present,
`ScoredWeeks.isFinal(week)`.

- **Decision**: extract that loop into one package-visible method
  (`finalGames(LeagueRow, WeekBound)`) used by both `compute()` and the new what-if. The what-if
  lives in a new `engine/MedianWhatIfService` that calls `ExpectedWinsService.median`/`allPlay`
  directly. No second median rule, no second finality rule (spec FR-002; lessons: "two
  implementations of one rule").
- `regularSeasonBound(league)` is already public and is the shared regular-season ceiling.

## R4. The "real" half of the combined record — read + measured (memory)

Two sources exist:
1. Sleeper's official record (`roster_season.wins/losses/ties`, from `/rosters`), what the W/L
   columns already show.
2. Head-to-head re-counted from stored weekly scores.

Measured 2026-10-12 (memory `project_sleeper_nba_2025_matchup_drift`): for NBA 2025, stored
scores agree with official fpts to ≈1%, but **4 of 12 rosters' re-counted wins differ from the
official record by one** (two close games flipped, probably post-capture stat corrections). NBA 2024
matched exactly.

- **Decision**: when every regular-season week of that season is final (all completed seasons),
  the real half is **Sleeper's official W-L-T** (`realSource = OFFICIAL`). The combined
  record then reads as "your W column plus your median wins", which is what a reader will check
  it against (lessons bug class 5). Otherwise (a season in progress with median off) the real
  half is re-counted from the same final weeks the median half uses (`COUNTED_GAMES`) and the page
  names the week count (FR-006).
- "Every regular-season week final" = the set of final weeks ⊇ {1 … playoff_week_start−1}. When
  `playoff_week_start` is unset (< 2) there is no known regular-season length, so always
  `COUNTED_GAMES`.
- **Alternative rejected**: always re-count. Simpler and internally consistent, but on NBA 2025 it
  would print a "real" record that disagrees with the W column beside it for 4 managers.

> **Amended after review (2026-11-02)**: R4's "every regular-season week final" test is replaced by plan.md amendments #1, #2 and #6 (decide from the games actually used, check official W+L+T, fall back on nulls).

## R5. Places and the tie-break — read

`RosterSeasonRepository.forLeague` orders rows by `final_placement` (playoff result), not by the
regular season, so there is no stored regular-season place to compare to.

- **Decision**: compute both places with one comparator: win share `(W + 0.5·T) / games`
  descending, then points for descending, then roster id. Actual place uses the real half; what-if
  place uses the combined record. Points for is Sleeper's default regular-season tie-break for
  seed type 0; the league sets no `playoff_tiebreaker` (absent on all three seasons, measured).
  Labelled in How-this-works as this app's ordering, not Sleeper's seeding.
- Points for comes from `roster_season.points_for` (official) for `OFFICIAL`, from the
  counted games otherwise.
- The playoff line uses `PlayoffFormat.playoffTeams()` and is withheld unless
  `PlayoffFormat.modelable()` is true (same honesty rule as playoff odds).

## R6. Does our median rule match Sleeper's median game? — measured, inconclusive

Re-counted 2026 weeks 1-2 from Sleeper's live `/matchups` and compared with `/rosters` W-L (median
is on, so official wins = head-to-head + median). 6 of 12 rosters disagree, **but the head-to-head
half alone already disagrees** (roster 6 is 4-0 officially, yet its re-counted head-to-head shows one
win in two weeks). So Sleeper's live `/matchups` points for these weeks are not the points the
official record used, which is the degradation memory already describes for older NBA seasons. This
says nothing about the median rule either way.

- **Decision**: no rule change. Verification (quickstart V4) repeats the cross-check against the
  app's **stored** 2026 scores, where official wins should equal re-counted head-to-head plus
  median wins.

## R7. Cost — read, not measured

Today `history()` already loops the chain (3 seasons here) with several queries per season. The
what-if adds one paired-games query + one `ScoredWeeks.of` + one `playoffFormat` per season, all
indexed by league id. Expected well under SC-006's 1 s; **guess, measure in verification.**

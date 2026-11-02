# Adversarial plan review: spec 054 (weekly median what-if)

Reviewed 2026-11-02, cold, against the worktree on branch `054-weekly-median-what-if` (main ad1d031).
**Everything below was verified by reading code, not by running it.** No DB was queried (5433 is not
running; the 5438 scratch DB prompted for a password and was abandoned), so every claim about stored
data is labelled *inferred*. No BLOCKER that changes what the feature *is*; three SHOULD-FIX items
change how the "real" half and the places are built and should be taken into the plan before tasks.

---

## 1. SHOULD-FIX: R4's "every regular-season week final" test checks the wrong set

**Evidence (read):** `ScoredWeeks.of` (`ScoredWeeks.java:56-68`) returns, for a `loaded_complete`
season or one with no POINTS fetch rows, `finals = stored`, where stored = any week with *any*
`roster_week_points` row (`weekPoints.storedWeeks`). `pairedWithScores` then INNER-joins both sides to
`roster_week_points` and drops null `matchup_id` (`LeagueMatchupRepository.java:151-157`). So a week can
be "final" in `ScoredWeeks` while some rosters' games are missing from `games`.

**What goes wrong:** R4 picks `SLEEPER_OFFICIAL` when `finalWeeks ⊇ {1..pws-1}`. A week that is stored
for 10 of 12 rosters passes that test, Sleeper's official W-L covers all 18 weeks, and the median half
covers 17 for two managers. The combined record silently mixes two game counts, which is exactly what
Story 2 scenario 2 / FR-003 forbid. The same hole breaks data-model invariant 1
(`median W+L+T == weeksCounted` "for every team").

**Amendment:** decide `realSource` from the **games actually used**, not from `ScoredWeeks`: per
roster, `gamesCounted` = weeks it appears in `games`; `SLEEPER_OFFICIAL` only when, for every roster,
`gamesCounted == regularSeasonWeeks` **and** `official W+L+T == regularSeasonWeeks` (finding 2).
Otherwise `COUNTED_GAMES`. Add `gamesCounted` to `SeasonMedianTeam`; restate invariant 1 / SC-002 as
`median W+L+T == gamesCounted` (per team), with `weeksCounted` the season-level distinct-week count.

## 2. SHOULD-FIX: "official W-L is regular-season only" is assumed, never checked

**Evidence (read):** Sleeper wins/losses/ties come from `roster.settings.wins|losses|ties`
(`LeagueHistoryIngestService.java:204-206`); ESPN from `record.overall` (`EspnTranslator.java:419-443`).
Nothing in research measures that either excludes playoff games. *Inferred:* Sleeper's roster settings
are regular-season only, and for a median-on season they double; ESPN's `overall` is unknown.

**What goes wrong:** if either includes playoffs (or, for ESPN, counts differently), "with median" =
official + median adds 18 median games to 20 real ones, and actual/what-if places shift by playoff
results.

**Amendment:** make it a runtime guard, not an assumption: `official W+L+T == regularSeasonWeeks`
(median off) is a condition of `SLEEPER_OFFICIAL` (finding 1). Add a quickstart check that it holds
for 2024/2025, and that `W+L+T == 2 × weeks` for the median-on 2026 rows (which also tests that
`league_average_match` means "every week", see finding 11). Rename `SLEEPER_OFFICIAL` →
`OFFICIAL`, since FR-013 makes ESPN rows go through the same path.

## 3. SHOULD-FIX: two sources, one of them known to drift, with no caveat on the wire

**Evidence:** research R4 itself (memory, measured 2026-10-12): NBA 2025 stored scores flip 4 of 12
rosters' head-to-head records vs official. The median half is computed from those **same** stored
scores (`ExpectedWinsService.median`, `:169-182`).

**What goes wrong:** using official W-L hides the drift for the real half but not for the median
half. For 2025 the page will show "with median" and places that combine Sleeper's record with medians
from scores Sleeper itself disagrees with in close weeks. A manager 0.3 points from the median in a
drifted week can flip a what-if place and a "made playoffs" tag. This is the AGENTS.md honesty rule:
it makes output look more certain than it is, and the payload says nothing.

**Amendment:** the plan already adds `headToHead(List<Game>)`. Compute it on every `WHAT_IF` season
and emit `scoreDriftRosters` (count of rosters whose counted head-to-head ≠ official W-L-T). When > 0,
the page names it under the table ("stored weekly scores disagree with Sleeper's record for N
managers; median games use the stored scores"). Unit-test the count. No change to the rule (FR-002).

## 4. SHOULD-FIX: "actual place" is this app's ordering, and the playoff marker hangs off it

**Evidence (read):** `RosterSeasonRepository.forLeague` (`:269-282`) orders by `final_placement`; R5
computes actual place with win share → PF → roster id. Sleeper's real seeding may use other tiebreaks
(R5 notes `playoff_tiebreaker` is absent, measured for this league only).

**What goes wrong:** if the app's actual place disagrees with who really made the bracket, "would have
missed the playoffs" is printed for a manager who actually missed (or vice versa); a fabricated fact,
not a what-if.

**Amendment:** cross-check: `actualPlace <= playoffTeams` must agree with the real outcome
(`final_placement <= playoffTeams`, available when the season is complete and brackets are stored;
spec 047's losers-bracket work suggests 7..12 are present, *inferred*). On any disagreement, withhold
the marker for that season with a reason (`ACTUAL_SEEDING_DIFFERS`). Test both the agreeing and the
disagreeing case.

## 5. SHOULD-FIX: `playoff_week_start < 2` makes "regular season" include the playoffs

**Evidence (read):** `regularSeasonBound` returns `WeekBound.ALL_WEEKS` when pws < 2
(`ExpectedWinsService.java:386-392`); `playoffFormat` coalesces an absent key to 0 (`LeagueRepository.java:364`).

**What goes wrong:** for such a season the median and the `COUNTED_GAMES` real half include any paired
playoff weeks, contradicting the spec edge case "Playoff weeks: never counted". R4 already notices the
"no known length" case but only for `realSource`.

**Amendment:** pws < 2 ⇒ median/all-play columns still shown (they match the Luck page, SC-004), but no
what-if: new status `NO_REGULAR_SEASON_BOUND` (or `WHAT_IF` with every what-if field null and a reason).
State it in How-this-works. Unit-test it.

## 6. SHOULD-FIX: nullable official W-L / missing roster rows

**Evidence (read):** `StandingRow.wins/losses/ties/pointsFor` are boxed `Integer`/`Double` and ingested
via `asIntOrNull` (`RosterSeasonRepository.java:263-266`, `LeagueHistoryIngestService.java:204-207`).
`games` can contain a roster with no `roster_season` row (left join, `LeagueMatchupRepository.java:153`),
and `roster_season` can contain a roster absent from `games` (unowned, never scored).

**What goes wrong:** unboxing a null in `real + median` NPEs; the whole season drops to `UNAVAILABLE`
(caught, but silently loses a season that had data). A roster in one set and not the other breaks the
"permutation of 1..teams.size()" invariant 3.

**Amendment:** any null official field or a roster present in only one of the two sets ⇒
`COUNTED_GAMES` for that season (not a throw). Places rank exactly the rosters in `teams`. Test with a
null-wins row.

## 7. SHOULD-FIX: spec-required measurement was dropped

**Evidence:** spec Assumptions: "the plan must measure how many seasons this affects on the requested
league" (seasons lacking stored weekly scores). research.md R1 measured Sleeper's API settings, not
this app's stored `league_matchup`/`roster_week_points` per season.

**Amendment:** before tasks, measure on prod (or a fresh ingest) per chain season: distinct reg-season
weeks in `pairedWithScores`, rosters per week, and official W+L+T. That single query also answers
findings 1-3 for the requested league.

## 8. SHOULD-FIX: "+9 wins" is misread (bug class 5)

**Evidence:** spec Story 2 scenario 1: 8-6 + 9-5 ⇒ 17-11, "+9 wins". Every manager gains wins; the
league total gained is ≈ `rosters/2 × weeks` regardless.

**What goes wrong:** a 3-15 median manager reads "+3 wins" as an improvement when their win share
falls. "Wins gained" in isolation answers the literal request but not the question behind it.

**Amendment:** keep "Gained" (it was asked for) but always render it with the losses it came with
(`+9 W, +5 L`) and let the place movement carry "better or worse". How-this-works says so in one
sentence. Spec FR-004 wording: "median wins over the same weeks, shown with the median losses".

## 9. NIT: tie-break differs between spec and plan

Spec Assumptions: wins, then fewer losses, then PF. Plan R5: win share `(W + 0.5T)/games`, then PF,
then roster id. They differ with ties and with unequal game counts (finding 1). Pick R5's (it handles
unequal counts), amend the spec Assumption visibly, and have How-this-works state the roster-id last
resort.

## 10. NIT: status enum disagrees across docs

data-model.md lists `WHAT_IF | ALREADY_ON | NO_FINAL_WEEKS`; the contract adds `UNAVAILABLE`. api.ts
must carry all of them (plus finding 5's). `playoffLineWithheld` needs codes, since `modelable()`
(`LeagueRepository.java:356-358`) is a bare boolean: derive `NO_START`/`DIVISIONS`/`SEED_TYPE`/
`NO_TEAMS` in the engine, wording in the page (store/ holds no user text, per `:344-346`).

## 11. NIT: `league_average_match` read as a boolean

`> 0` (`LeagueRepository.java:367`) treats any positive value as "median every week". *Inferred, not
checked:* Sleeper may have values or companion keys for median starting later in the season. The
finding-2 check (`W+L+T == 2 × weeks` for 2026) catches it on this league; enough for v1.

## 12. NIT: frontend tests that will pass for the wrong reason

`LeagueHistory.test.tsx:24-31,116-117,451-466` mock `getExpectedWins` and assert "dashes when the
expected-wins season differs". Once the page stops calling it, a dash test still passes (nothing feeds
the column at all). Plan should list these as **rewritten** against `season.median`, and add one
asserting an older season (not `seasons[0]`) shows figures. Removing the fetch itself is safe: in
`LeagueHistory.tsx`, `expected` only feeds `StandingsTable` (`:393-415`, `:486-518`, `:628`); drop the
`ExpectedWins`/`ExpectedWinsTeam` imports (`:7-10`) or `tsc -b` with `noUnusedLocals` fails (*inferred*
from strict TS). api.ts has no named W-L-T type (`api.ts:2148-2151` are inline) so add one.

## 13. NIT: How-this-works has two stale sentences, not one

FR-011 names "not added to the real W-L". `LeagueHistory.tsx:661-662` ("Both columns come from the
Luck page's calculation … Any season it did not compute shows a dash") and the header tooltip at `:432`
also become false.

## 14. NIT: query cost and reuse

Per season the block adds `playoffFormat` twice (once inside `regularSeasonBound`), `ScoredWeeks.of`
(3-4 queries), `pairedWithScores` (self-join + 4 left joins) and, if the service re-calls it,
`rosterSeasons.forLeague`, which `history()` already ran at `:134`. Pass the already-fetched
`StandingRow`s and one `PlayoffFormat` in. ~8 small indexed queries × 3 seasons is plausibly far
under SC-006's 1 s, but that is a guess until V7.

## 15. NIT: preference-ordering test as planned is too weak

Invariant 4 ("more median wins, equal real record ⇒ better-or-equal place") passes for a comparator
that ignores median entirely (ties on equal real records then decided by PF). Add a **strict** case:
A and B equal real record, A higher PF, B more median wins ⇒ B strictly ahead in what-if, A ahead in
actual; movement sign (+ for moving up) and `MADE`/`MISSED` asserted on that same fixture.

---

## Checked and fine (read)

- One median rule: the plan reuses `median()`/`allPlay()` (`:150-182`), and extracting the loop at
  `:398-406` keeps finality and the regular-season bound in one place. Good.
- `median()` uses only paired rosters (`scoresByWeek`, `:342-350`). For a 12-team Sleeper league every
  roster is paired each regular-season week, so this matches Sleeper's all-roster median there.
  *Inferred:* with byes/odd counts, Sleeper's median also includes the unpaired roster; out of scope
  for this league, but it is why finding 1's per-team `gamesCounted` matters.
- `modelable()` fits the playoff line exactly (`playoffTeams > 0 && pws > 1 && seedType == 0 && !divisions`);
  ESPN writes seedType -1 for non-ladder formats.
- Failure isolation: `history()` has no `@Transactional`, so a caught exception in one season's block
  cannot poison the others or the response.
- SC-004 parity: `/expected-wins` uses `forLeagueRegularSeason` → `regularSeasonBound`, the same bound
  the plan uses. The IT should compare against whichever season the Luck page resolved
  (`PLAYED_WEEKS`), not hard-code the newest.

# Code review: spec 018 (draft grades)

**Date:** 2026-10-06
**Stage:** bug-hunting code review, run cold against the uncommitted working tree on
`018-draft-grades` (base `origin/main` c450f67). This is not a style pass.

**What I reviewed:** `git diff` (DraftSimApplication, HealthController, AccessControlMvcIT,
config/weights.yml, api.ts, DraftBoard.tsx, PlayerCard.tsx, CompletedDraftBoard.tsx(+test),
styles.css, testLiveRoom.ts) and the untracked `DraftGradesService.java`,
`DraftGradesController.java`, `DraftGradeProperties.java`, `DraftGradeStrip.tsx`,
`draftGrades.ts`, plus the new tests. I read them against data-model.md (including the
"Amended during build" log-fit section), contracts/api.md (including invariant 8's amended
note), spec.md, plan.md, AGENTS.md and `claude/lessons.md` #29 and #32.

**What I ran:** GETs against the worktree backend on :8085 for all seven completed drafts
(NBA 2024 and 2025, NFL 2025 ×2, NFL 2026 ×3, including both 14-team drafts) and the two
`pre_draft` ones. I checked the contract invariants on every response with a script, refit
the per-position OLS on the returned numbers, recomputed team sums, best/worst picks and
positional ranks, and measured mean value by draft region and by position (lesson #32).
Read-only psql against :5433. `./gradlew test` on the five new unit-test classes (47 tests,
0 skipped, 0 failed). `npx tsc -b` (clean) and vitest on the four new or changed web test
files (33 passed). I did **not** run the two ITs: they are `@SpringBootTest` against the
shared DB, and I wanted to avoid side effects. I did not drive the UI in a browser.

## Findings

### B1. Medium, measured: NBA "position" is the alphabetically first eligibility, so SGs are graded and labelled as PGs and SG-only picks get no value

`DraftGradesService.java:342` (`position = positions.getFirst()`), `PlayerCard.tsx`
("{n}th {pos} drafted, finished {m}th" and "what a {pos} taken at pick N scored").

The data model assumes the first entry of `player.positions` is "his position". For NBA it
isn't. Sleeper's `fantasy_positions` arrive sorted alphabetically, and ingest keeps that
order:

```
select sport, count(*) filter (where cardinality(positions)>1) multi,
       count(*) filter (where ... positions = sorted(positions)) multi_sorted from player group by sport
 nba | 1679 | 1679        <- every multi-position NBA list is alphabetical
 Anthony Edwards {PG,SG}, Devin Booker {PG,SG}, Donovan Mitchell {PG,SG}, Desmond Bane {PG,SG},
 LeBron James {PF,PG,SF}, Jayson Tatum {PF,SF}, Kawhi Leonard {SF,SG}
```

So the NBA buckets are "C-eligible", "else PF-eligible", "else PG-eligible", "else
SF-eligible", "SG only". NBA 2025's picks split C 45 / PG 58 / PF 43 / SF 20 / **SG 2**, and
NBA 2024's split C 45 / PG 61 / PF 44 / SF 17 / **SG 1** (measured from the :8085 responses).
What goes wrong:
- Anthony Edwards (NBA 2024, pick 6) gets the card line "3rd PG drafted, finished 6th", and his
  baseline is described as "what a PG taken at pick 6 scored".
- The SG bucket is always below `minPicksPerPosition` = 8. So every SG-only pick (Quentin
  Grimes #139 and Buddy Hield #164 in 2025, Hield #111 in 2024) has a null value. Those picks
  silently drop out of their team's draft value, and the card says "no baseline: fewer than
  8 SGs drafted". That's true only because the other SGs were all filed under PG.
- research.md R5 says "the label says 'by primary position'". No label says this, and the
  first-listed entry isn't a primary position anyway.

The buckets are at least consistent eligibility classes, so the arithmetic isn't wrong. The
labels are, and so is the SG drop. This is lesson #29's shape: one rule for both sports holds
for football (9 multi-position NFL players) and misleads in basketball.

**Fix:** for NBA, either bucket by something real, such as guard/forward/centre from the
eligibility set, or keep the bucket and label it honestly ("PG-eligible", "first listed
position"). Either way, don't strand SG-only players in a bucket that can never be fitted.
The cheap alternative: fold any position below `minPicksPerPosition` into its nearest
neighbour. Amend data-model's Position section visibly.

### B2. Medium, read: `NO_SCORED_WEEKS` says "No weeks have been scored yet" when weeks *were* scored but lack per-game data

`draftGrades.ts:12-13`, `CompletedDraftBoard.tsx` (`!grades.available` branch),
`DraftGradesService.java:179-180`.

The backend returns `NO_SCORED_WEEKS` when `finalWeeks ∩ sport_week_stats` is empty, and it
deliberately fills `weeksMissingGameData` when final weeks exist but have no per-game rows
(data-model check-order row 3). The web ignores that list on the unavailable path:
`reasonSentence('NO_SCORED_WEEKS')` always prints "No weeks have been scored yet, so there is
nothing to grade yet." (`legendText`, which does name the missing weeks, only runs when
`available`.)

Failure scenario: a league whose final weeks have no per-game data. One case is a season whose
per-game fetch failed. `LeagueRefreshService.refreshChain` ingests league points first and
throws on `weeksFailed > 0` after that, so league finality can land without
`sport_week_stats`. Another is a season that was `loaded_complete` before per-game ingest
covered it, the same never-refetched shape as the 017 review's R1. The response then has
`reason: NO_SCORED_WEEKS, weeksMissingGameData: [1..17]`, and the page tells a member of a
finished season that nothing has been scored. Locally every league season with a draft has
game weeks, except NBA 2026, whose draft is `pre_draft` (measured). So I couldn't reproduce
this live, and the evidence is reading the code.

**Fix:** when `weeksMissingGameData` is non-empty, use a different sentence, e.g. "Weeks {list}
are scored, but their game-by-game stats aren't in yet." Add a vitest for that branch.

### B3. Medium-low, partly measured: a final league week counts even when its per-game data is still partial

`DraftGradesService.java:173-176`.

`counted = finalWeeks ∩ {weeks with a sport_week_stats row}`. The row's `final` flag is
ignored. `sport_week_stats.final` is `schedule.isFinal(week, now)`, which means every game in
that week has been played (`PlayerGameIngestService.java:285`). A row exists as soon as the
week is fetched once. Measured right now:

```
nfl 2026 week 4  final = f  fetched 2026-10-06 01:25  (player_game rows present for week 4)
```

When a league's week-4 POINTS row turns final before the per-game week is refetched, week 4
counts with the games stored before it ended. That happens in the window inside a chain
refresh between history ingest and `refreshSportSeason`, and for good if the per-game fetch
fails, as in B2. Any player who played after the last fetch shows as "didn't play" that week:
production too low, `weeksPlayed` short, and the gap baked into the fit. That's exactly what
F7 set out to prevent. No league has week 4 final yet (leagues 3/9465 are final through week
2, league 4 through week 3), so nothing is wrong on screen today.

**Fix:** require `row.fin()` too, and report a final league week whose game data is present
but not final as missing (or as its own list). Every stored historical week is `final = t`
(measured: the only `f` row is NFL 2026 week 4), so this costs no past season anything.

### B4. Low, measured: positional finish ties are broken on unrounded doubles, so equal shown production ranks the later pick higher

`DraftGradesService.java:375-376`.

`positionFinish` sorts by raw `production`, then pick number. Football production is a sum of
decimal multipliers, so two players who both show 290.00 can differ in the 13th digit. The
data model's rule is "ties → lower pick number". Measured on :8085:

```
NFL 2025 (1254190894563729408): Ja'Marr Chase  #1  290.0  finish 5   |  George Pickens #48  290.0  finish 4
                                Drake London   #18 184.1  finish 23  |  Deebo Samuel  #57  184.1  finish 22
                                Bucky Irving   #23 127.7  finish 32  |  Cam Skattebo  #102 127.7  finish 31
NBA 2024: James Harden #15 636.29 finish 5  |  Trae Young #17 636.29 finish 4
NFL 2026 (1391509064357273600): Mike Evans #66 25.3 finish 20  |  Matthew Golden #119 25.3 finish 19
```

That's seven pairs across four of the seven drafts. The best/worst pick and steals/busts code
already compares on `round2` (`:408`, `:419`) for exactly this reason. The finish sort doesn't.
The unit test `positionalRanksOrderByPickNoAndByProductionWithTiesToTheLowerPick` uses exact
synthetic ties, so it can't see this.

**Fix:** `Comparator.comparingDouble((Acc a) -> round2(a.production)).reversed()...`. Add a test
with 0.1-multiplier sums, e.g. 0.1×3 vs 0.3.

### B5. Low, read: the unknown-week rule differs from data-model F10 for a roster with no `league_matchup` row

`DraftGradesService.java:264-281`, against data-model's "Counted for the drafting team".

The data model says `unknown(i,w)` holds when there is "no league_matchup row for (roster, w)".
The code only marks a week unknown when *no* roster has a matchup that week, or when the roster
had a game but no lineup data. `between()` filters `matchup_id is not null`, so a roster with no
row at all can't be told apart from a roster with a null-matchup bye. It's treated as "no game:
not counted, not unknown". The code's javadoc says so, but the data model wasn't amended. No
effect on current data: every stored week has a row for all 12/14 rosters (measured with
`count(*)` vs `count(matchup_id)` per league-week). It would matter for a partially ingested
schedule.

**Fix:** either read the rows including null matchup ids, and call a roster with no row
unknown, or add an "amended during build" note to data-model with the rule as built.

### B6. Low, read and measured: contract invariant 1 doesn't hold for an unavailable complete draft

`DraftGradesService.java:222-227`; contracts/api.md invariant 1.

Invariant 1 says `picks.length + excludedPicks` = the stored pick count, with no qualifier.
Every unavailable response hard-codes `excludedPicks: 0` and `picks: []`. So a complete
180-pick draft answering `NO_SCORED_WEEKS` (or `NOT_CONFIGURED`) gives 0 + 0 ≠ 180. The IT
only checks invariant 1 on available drafts, so nothing catches it. I measured the
`DRAFT_NOT_COMPLETE` bodies for the two `pre_draft` drafts. They have zero stored picks, so
they happen to satisfy it.

**Fix:** scope invariant 1 to `available = true` in the contract. That's a doc-only change,
and the cheaper one.

## Lower-severity notes

- **`averageTeamRawValue` is always 0 now (measured: 0.0 on all seven drafts).** OLS residuals
  sum to zero within each fitted position, and unfitted positions add nothing, so the raw team
  values already sum to 0. Centring (F4) is a no-op under the log fit. The field and invariant 5
  are harmless but vestigial. Say so in data-model's Team section rather than leaving F4's
  "NBA summed to −3,600" rationale standing as if it still applied.
- **The fitted slope is unconstrained, and with 11–15 K/DEF picks over 2–3 weeks it's noise.**
  Measured: positive slopes, where later picks are *expected* to score more, for NFL 2025 QB
  (b = +3.3), and for NFL 2026 K (+9.1, +0.5, +12.8), DEF (+17.5, +5.3, +4.2) and TE (+0.3). So
  the first kicker taken is graded against a lower bar than the last. That's not a code bug.
  It's the lesson #32 question one level down. Region means look balanced (first 12/14 picks:
  −46.7 to +0.9; last 12/14: −46.5 to +62.0). The 14-team drafts are within ±2.2 everywhere.
  NBA 2024's first-at-position picks average −106.8 (Wembanyama #1 −176.8, Dončić #3 −209.7),
  so NBA's top picks read as busts in that draft. That's real production, but worth one line
  in verification.
- **Invariant 8 also breaks on NBA 2024 (measured):** Trey Murphy #114, counted 410.21 >
  production 409.21. He played week 22 (one game, weekly value −1.0) and wasn't started. This
  is the amended note's subset case, but the note only names NFL. The IT asserts the
  inequality on NBA 2025 and NFL 2025. Don't add NBA 2024 to that assertion.
- **Traded picks (not present, measured 0):** teams are keyed by `draft_slot`, but
  counted-for-you is keyed by the pick's `manager_id`. A traded pick's value would land on the
  slot owner's team grade, while its counted-for-you belongs to the trader. Every completed
  draft has one manager per slot. The 14-team draft 1389361939561332737 is missing slot 7 from
  `slot_to_manager`, and it correctly falls back to the picks' manager (apocinki). Worth a
  sentence in data-model's "Known gap".
- **Copy:** PlayerCard prints "1 weeks started" and "1 weeks unknown" (no singular). The cell
  hover says "points against the players drafted around him". That's the superseded
  neighbours wording, missing "at his position" and "fitted". The steals/busts subtitle
  ("vs. players at his position drafted around that pick") is closer, but it's still a
  neighbour reading of a whole-position line. "Finished 2nd" is the rank among *drafted*
  players at that position, and the card doesn't say so.
- **"Couldn't load grades. Try again." isn't a control.** A retry does work: re-selecting the
  segment, or toggling the single chip, refetches, because `gradesRequested` is reset on
  failure. Make the sentence the button, or say "Select it again to retry".
- **data-model.md's Config section is stale.** It still says "validated (1 ≤ n ≤ 10)", and its
  YAML comment describes neighbours. Code and the paragraph above it say 3 ≤ m ≤ 30.
- **`.grades-controls` in styles.css is unused.**
- **Performance is fine (measured):** 0.05–0.26 s for NFL, 0.21–0.60 s for NBA 2024 (first call
  0.6 s, warm 0.21 s). `players.findAll(sport)` loads 4,387 NFL / 2,091 NBA rows per request.
  That's cheap, but `byIds` over the picks' ids would match data-model's "PlayerRepository (by
  ids)". There's no N+1: one `forPlayers` query, one breakdowns query, one matchups query.

## Checked and clean

- **Access control (measured):** no header → 404, stranger → 404, unknown draft id → 404,
  member → 200. CORS preflight with `x-sleeper-user` from :5173 → 200 with the header allowed.
  The AccessControlMvcIT addition covers blank-header and stranger.
- **JSON ↔ api.ts (measured):** DraftGrades 20/20, PickGrade 16/16, TeamGrade 8/8 keys. Names
  and nullability match the Java records and the contract. `sport` serialises lowercase,
  matching `Sport`.
- **Math (measured):** refitting OLS from the returned production and pick numbers matches
  every `slotBaseline` within 0.006 (production is rounded to 2 dp on the wire). Values sum to
  ~0 per fitted position. Team `draftValue` = Σ team values − average. `bestPickNo` and
  `worstPickNo` come from the team's own picks. `positionDrafted` is pick order. No baseline
  for positions under 8. ln(pickNo) has no zero-pick issue, since pick numbers start at 1.
- **Contract invariants 2–7 and 9 (measured)** on all seven drafts. Steals and busts are ≤ 5
  each and disjoint. `weeksPlayed` ≤ `weeksCounted`, and started + unknown ≤ `weeksCounted`.
- **Week sets (measured):** NBA 2024 counts 24 of 25 game weeks, and NBA 2025 counts 21 of 25.
  The NBA weeks after the league's end are excluded via `finalWeeks`. NFL 2025 counts 17 (league
  5) and 18 (league 9466), matching each league's stored weeks. NFL 2026 counts [1,2,3] for
  league 4 and [1,2] for leagues 3/9465, because their POINTS week 3 isn't final until their
  next refresh (data freshness, not this code).
- **Counted-for-you mapping (measured):** `unmappedPicks` is 0 and `excludedPicks` is 0
  everywhere, including both 14-team drafts. `weeksUnknownForYou` is 0 everywhere. Playoff
  weeks with 8 of 12 matchups correctly don't count for teams on a bye.
- **Null handling (read):** there's no `Map.of` with values in a response path. Health's
  `Map.of` has four non-null booleans and strings. A null position is graded on production
  only. Null or `{}` starters and points become unknown, never zero. A missing league row
  falls back to NFL, as `/board` does.
- **React state (read):** `CompletedDraftBoard` is keyed per `draftId` (`App.tsx:44`), so an
  in-flight grades fetch can't land on another draft. `gradesRequested` prevents a double fetch
  and resets on failure. The two value views are exclusive: grades take precedence in
  `DraftBoard`, the page only passes one, and there's a vitest for it. `NOT_CONFIGURED` maps to
  a sentence, and no reason sentence contains "ingest" (a vitest asserts it). The segmented
  control is a `role="group"` of `aria-pressed` buttons with an `aria-label`.
- **Tests:** 47 backend unit tests and 33 vitest pass, and `tsc -b` is clean. The ITs were not
  run by me.

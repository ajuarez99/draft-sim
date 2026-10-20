# Code review: spec 040, superlatives commissioner UX

**Reviewed:** the uncommitted working tree against HEAD `7736114` in the worktree
`040-superlatives-commissioner-ux`. That is `git diff` plus the untracked V38 migration,
`PlayerContributionsTest`, `CommissionerListPanel(.test).tsx` and `SuperlativeStandings.test.tsx`.
**Reviewer role:** bug hunt, not a style pass. No source files were modified. This file is the only
output.

## Verdict

**No blockers, and nothing HIGH.** There are two MEDIUM findings, both confirmed against real data:

- **M1:** the Players view doesn't add up to the team figure when a pickup scored negative points.
  This breaks SC-004 on a real league today.
- **M2:** FR-015 says Jabari lists every player with at least one add, but the code still requires two.

The rest are LOW: frontend robustness, an a11y idref, and two honesty edges in the Embiid rule. The
core of the change checks out:

- The weekly Embiid rule is right.
- The through-week is inclusive and validated before the CHECK constraint can fire.
- The UNETHICAL distinct count is consistent everywhere it's used.
- `playerContributions` survives `withUsernames` and `withStandings`.
- The V38 migration is valid.

Fix M1 before live verification. Decide M2 (a spec amendment is the cheaper option).

## What was executed

- **Backend tests:** `./gradlew.bat test --tests "*AbsenceCost*" --tests "*Unethical*"
  --tests "*PlayerContributions*" --tests "*SuperlativesController*" --tests "*SuperlativesStandings*"
  --tests "*LeagueConductRepository*" --tests "*SeasonSuperlatives*"`. BUILD SUCCESSFUL: 134 tests,
  **0 skipped**, 0 failures. Counts come from `build/test-results` XML, so the ITs really ran against
  5433.
- **Frontend tests:** `npx vitest run` on `CommissionerListPanel.test.tsx`,
  `SuperlativeStandings.test.tsx`, `Superlatives.test.tsx` and `LeagueHome.test.tsx`: 147 of 147
  passed. `npx tsc -b` was clean.
- **Live backend:** I ran `bootRun` from this worktree on 8080, then stopped it (PID killed, port
  confirmed free). The admin token was disabled. I made GETs only, with
  `X-Sleeper-User: 1122386008709910528`:
  - `/superlatives` and `/conduct-list` for NBA 2025 `1229352720222134272`, NBA 2026
    `1339351318115946496` and NFL 2025 `1254190892974084096`. All returned 200; `conduct-list` took
    22–42 ms.
  - The boot log shows no refresh or ingest activity during the run, so nothing was written to the
    shared DB.
  - I compared the responses with `baseline/` using a throwaway script in the session scratchpad.
- **Read-only psql (5433):** the V38 `flyway_schema_history` row and the constraint; conduct rows
  (there are none); null `roster_season.players` (all 12 NFL 2025 rosters are null, so the F4b fallback
  is live there); null array elements (0); dual-roster (player, week) pairs; negative started scores.

Live results:

- **NFL 2025 Embiid:** every roster is identical to the baseline (SC-003 holds).
- **NBA 2025 Embiid:** every roster changed, as intended. Roster 8 went from 3980.55 to 776.12; the
  full per-roster table is still owed in verification (SC-002).
- **NBA 2025:** Embiid and Waiver contributions sum to the standings on every roster, and every
  contribution's team has a username (`nouser=0`). NFL 2025's Embiid contributions also sum, but its
  Waiver contributions don't (M1).
- **Conduct-list candidates:** 331 for NBA 2025, 187 for NBA 2026 and 315 for NFL 2025, with no
  duplicate ids and no null positions. Every NBA 2026 candidate has a team (`roster_season.players` is
  populated). In NFL 2025 the teams come from the latest-week fallback.
- **Not executed:** any write (POST, PUT or DELETE) over HTTP, and a real-browser PUT with its CORS
  preflight. Both belong to live verification. No conduct entries exist locally, so the live
  `entries[].charges` shape was only exercised by `SuperlativesControllerIT`.

## Findings

### M1 (MEDIUM, CONFIRMED): the Players view drops negative contributions, so it doesn't sum to the team figure

- **Code:** `SeasonSuperlativesService.rankContributions` keeps only rows with `round2(value) > 0`
  (around `SeasonSuperlativesService.java:1643`). The team totals keep the negative rows:
  - `WaiverPickupAttribution.java:141` sums every contribution, negatives included.
  - `AbsenceCost.compute` sums every `PlayerCost`, including a negative `pointsPerGame`.
- **Failure:** NFL 2025 (`1254190892974084096`), WAIVER_WIRE_WARRIOR:
  - Roster 5's standings show **404.32**, but its contributions sum to **405.08**.
  - Roster 11's standings show **393.50**, but its contributions sum to **397.50**.
  - The differences are exactly the dropped rows. psql shows that roster 5 started Tyrod Taylor at
    −0.76 in week 14, and roster 11 started the Jacksonville D/ST at −4.0 in week 7.
  - This breaks spec acceptance US3-3 and SC-004 ("0 mismatches on the real leagues") on a real league
    today.
  - Embiid has the same hole for a regular-contributor K or D/ST with a negative mean. It is not
    observed on current data.
  - UNETHICAL can't be negative.
- **Why the tests pass:** `PlayerContributionsTest` only uses 0.0 and positive fixtures (for example
  `wpc("d", 4, "WAIVER", 0.0)`). `SuperlativesStandingsIT`'s sum check only runs on a fixture with
  positive values.
- **Fix:** drop only rows whose `round2(value) == 0`. Keep negative rows; they rank at the bottom by
  value descending. Add a `PlayerContributionsTest` case with a negative pickup that asserts the sum
  equals the standings. Optionally, give the frontend a sign or "cost points" treatment for negative
  figures.

### M2 (MEDIUM, CONFIRMED): Jabari is uncapped but still requires two adds, while FR-015 says one

- **Code:** `MostAddedPlayers.top` (`MostAddedPlayers.java:84`) still applies `MIN_ADDS_TO_NAME = 2`.
  The change only swapped the limit from `10` to `Integer.MAX_VALUE`
  (`SeasonSuperlativesService.java` `mostAddedSuperlative`).
- **Spec says otherwise:** FR-015 says "every player with at least one add", and `quickstart.md` step 4
  checks "`playerStandings` length == number of players with ≥ 1 add (no cap)".
- **Live:** NFL 2025 lists 47 players, all with 2 or 3 adds. NBA 2025 lists 182, and the lowest add
  count is 2. Single-add players never appear, so quickstart step 4 fails as written, or gets reported
  as passed by someone counting the wrong thing (lessons class 8).
- **Fix:** I'd amend the spec, not the code. The two-add floor is a deliberate 008/010 rule ("a single
  add isn't 'most added'"). The holders logic at `:837` relies on it, and the card's copy depends on it.
  Change FR-015 and quickstart step 4 to "≥ `MIN_ADDS_TO_NAME` (2) adds, uncapped", with a dated
  "amended after code review" note. If Allan really wants single adds listed, pass a separate floor of 1
  for the standings list only. Don't change the holders' floor.

### L1 (LOW, read only): the panel keeps the previous league's list after a league change, and `afterWrite` can apply a stale response

- **Code:** `CommissionerListPanel.tsx:107-120` refetches when `leagueSleeperId` changes, but it doesn't
  reset `list`, `editingId` or `adding`.
- **Stale list:** until the new response lands, the panel shows league A's entries, `canEdit` and season
  under league B's URL. A PUT or DELETE issued in that window sends league A's entry id to league B. The
  server's league pin turns that into a 404, which is safe but confusing. An Add posts to B while A's
  list is on screen.
- **Stale `afterWrite`:** `afterWrite` (`:122-127`) has no cancellation guard. A write that resolves
  after navigation calls `accept()` with a list fetched for the old id, which overwrites the new
  league's list and count.
- **Fix:** reset `list` to null on an id change. Keep a ref of the current id, and drop any
  `afterWrite` result whose id no longer matches.

### L2 (LOW, read only): if the refetch after a write fails, the write looks like it failed and the cards stay stale

- **Code:** `run()` (`CommissionerListPanel.tsx:129-141`) awaits `write()`, then `afterWrite()`, then
  `onChanged()`, all in one try block.
- **Failure:** if the list GET fails after a write succeeded:
  - The user sees an error.
  - The form stays open, because `setAdding(false)` and `setEditingId(null)` never run.
  - `onChanged` is skipped, so the UNETHICAL card stays stale.
  - Pressing Remove → Yes again returns a 404 ("not found") for an entry that is already gone.
- **Fix:** close the form and call `onChanged` once `write()` resolves. Report a refetch failure
  separately, for example "Saved, but the list couldn't be reloaded".

### L3 (LOW, read only): `aria-controls` points at an element that isn't rendered while the panel is closed

- **Code:** `CommissionerListButton` always sets `aria-controls={controlsId}`
  (`CommissionerListPanel.tsx:284`). The panel returns `null` when it's closed (`:143`), so the idref
  dangles until the panel opens.
- **Fix:** set `aria-controls` only when `open`, or render the section with `hidden`.

### L4 (LOW, read only): the unclassified-week coverage note now counts weeks that can't move the figure

- **Code:** `unclassifiedWeeksByRoster` (`SeasonSuperlativesService.absenceSuperlative`) is unchanged,
  as FR-004 requires. Under the weekly rule, though, an UNCLASSIFIED row can't change the figure in two
  cases:
  - a week in which the player played any game, which costs 0 whatever the row is;
  - a week already counted through a classified row, which costs one game's average either way.
- **Effect:** the note "N weeks couldn't be classified" now overstates the uncertainty. That's the
  conservative direction, but it's still a number that doesn't mean what a reader assumes (lessons
  class 5).
- **Today:** NBA has 0 UNCLASSIFIED rows; NFL 2026 has 8. NFL is one game per week, so all of those
  could matter. No visible effect yet.
- **Fix:** count only unclassified weeks with zero `player_game` rows and no classified absence row,
  or amend FR-004 to say the note is kept knowingly as an upper bound.

### L5 (LOW, SUSPECTED): mid-week, the in-progress NBA week can read as a whole week missed and then disappear

- **Mechanism:**
  - NBA's `last_scored_leg` tracks `leg` (`WeekFinality.java` notes), so the week being played is stored
    in `roster_week_points` and is inside `scoredWeeks`.
  - A regular who sat Monday and plays Thursday counts as "1 week missed" (one game's average) until
    Thursday's `player_game` row lands. The charge then drops to 0, so the Embiid standings can go down
    mid-week.
  - The money isn't worse than under the old per-game rule, which also charged that one game. The new
    label is the problem: "week missed" is a claim about a week that hasn't finished.
- **Not executed:** there's no NBA 2026 week data locally. Check this after the first live NBA week.
- **Fix:** exclude non-final weeks from counted weeks (`WeekFinality` already knows), or note that the
  current week is provisional.

### L6 (LOW, SUSPECTED, edge): candidates get no team when a roster has no `roster_season` row at all

- **Code:** `SeasonSuperlativesService.conductView`'s latest-week fallback only fills rosters listed in
  `rostersWithoutPlayers`. Those are `roster_season` rows whose `players` is null. A league-season with
  `players_points` but no `roster_season` rows shows every candidate as "not on a roster".
- **Not observed:** the three real leagues all have 12 `roster_season` rows.
- **Fix:** fall back for any roster id seen in the latest week's `players_points` that is missing from
  `holderByPlayer`'s source rows.

## Checked and found clean

- **AbsenceCost:**
  - Counted weeks are absence weeks ∩ `rosteredWeeks` minus played weeks, all bounded to
    `scoredWeeksFinal` by the caller.
  - `gamesMissed` counts only rows inside counted weeks. The `player_absence` natural key is one row
    per game, so there's no double count.
  - The regular-contributor rule is untouched.
  - The holder and empty-state rules (`max <= 0`) carry over.
  - NFL is identical to the baseline when measured.
- **SC-002's "no (player, week) counted for two rosters":** the code doesn't enforce it; it's the old
  membership rule. On today's data, NBA 2025 has 4 dual-roster (player, week) pairs and none of them is
  a zero-play absence, so it holds only empirically. Verification should re-measure it rather than
  assume it.
- **UNETHICAL:**
  - The through-week filter is inclusive on both ends, and null means the rest of the window.
  - `unethicalTotals` feeds the holders, the value and `conductStandings`.
  - `unethicalContributions` unions weeks per (roster, player), so its sum equals the total.
  - No place still sums `weeks().size()` across source rows, except a test that does it on purpose.
- **conductView:**
  - It uses the same `scoredWindow` and the same `players_points` membership rule as the award.
  - `playerWeeks` counts distinct (roster, week) pairs for one player; the entry is unique per player by
    upsert.
  - It loads the week breakdowns once and calls `players.byIds` once (no N+1).
  - Null `players` arrays, duplicate ids (`putIfAbsent`) and ids with no `player` row (skipped) are all
    handled.
- **Controller:**
  - Every map that can hold a null is a `LinkedHashMap`. `Map.of` appears only with constant
    non-null messages.
  - PUT runs visibility (404), then commissioner (403), then body and validation (400), then the
    league-pinned update (404). That order matches POST and DELETE.
  - through < from is a 400 before the CHECK constraint can fire.
  - POST's upsert sets `applies_through_week`, which clears it on a re-add (IT-covered).
  - The reason is trimmed, and the 140 limit counts UTF-16 units. That's stricter than the DB's
    `char_length` and never looser.
- **Wire vs `web/src/api.ts`:**
  - The `ConductList` keys (`season`, `canEdit`, `commissionerKnown`, `entries`, `candidates`) and the
    candidate keys match the live JSON exactly.
  - `ConductEntry`'s optional `charges`, `playerWeeks` and `alsoFlaggedBySleeperWeeks` match the
    controller. `alsoFlaggedBySleeperWeeks` isn't in `contracts/awards-api.md`, so the contract should
    gain it.
  - The `ABSENCE` detail `weeksMissed`/`gamesMissed` swap is mirrored, and no other consumer reads
    `weeksAffected`.
- **Superlative rebuilders:** `withUsernames` and `withStandings` both carry `playerContributions`.
  Usernames are filled in the live payload.
- **V38:** valid Postgres, applied on 5433 at 10:22, and the CHECK constraint is present. It's
  append-only, and no other branch or worktree has a V38.
  - The shared 5433 DB now carries V38. Flyway ignores applied migrations a checkout doesn't have by
    default (`*:future`), so a main checkout still boots against it.
- **Frontend:**
  - Hook order is stable across the early `return null`s.
  - The panel root and the trigger stop both click and keydown.
  - The combobox opens on mousedown, so blur doesn't eat the pick.
  - Week inputs are held as text and validated (empty, non-integer, < 1, through < from all block
    Save).
  - The toggle defaults to Players for Embiid and Waiver and to Teams for UNETHICAL. Payload order is
    never re-sorted.
  - The Jabari fold summary is correct for both group shapes (first group folded or not).

## Recommended order

1. **Before live verification:** fix M1 and add a negative-value test.
2. **Before live verification:** resolve M2, by amending the spec and quickstart (recommended) or by
   changing the code.
3. **Before live verification:** add `alsoFlaggedBySleeperWeeks` to `contracts/awards-api.md`.
4. **Optional:** fix L1–L3 in the same pass. They're small, confined to the panel and button, and each
   is a few lines.
5. **Follow-ups:** L4 and L5 are honesty edges. Either fix them or record them visibly in the spec as
   known and accepted.

## Fixes applied (2026-10-20)

M2 and L4-L6 were deliberately left to another pass.

- **M1 fixed.** `SeasonSuperlativesService.rankContributions` now drops only rows whose `round2(value)` is
  exactly 0. Negative rows stay, ranked last by value descending, so the Players view sums to the team figure.
  Guards: `PlayerContributionsTest.waiverNegativePickupStaysSoTheRowsStillSumToTheTeamFigure` (a -0.76 pickup,
  per-team sum equals the standings value) and `embiidNegativeMeanPlayerStaysSoTheRowsStillSumToTheTeamFigure`
  (a -4.0 per-game mean can reach Embiid via a regular-contributor K or D/ST). The helper
  `assertCompetitionRanksAndOrder` now asserts "not zero" instead of "positive". The frontend already renders a
  negative figure as "-0.76 points" (single minus, via `toFixed`); `SuperlativeStandings.test.tsx` pins that for
  `standingFigure`.
- **L1 fixed.** `CommissionerListPanel` resets list, form, confirm, saving and error state when
  `leagueSleeperId` changes. A `leagueRef` is compared in `run()` so a write, refresh or `onChanged` that
  resolves after navigation is a no-op. Guards: `CommissionerListPanel.test.tsx` "L1: drops the previous
  leagues list..." and "L1: a write that resolves after the league changed...".
- **L2 fixed.** `run()` now separates the write from the refresh. After a successful write the form and confirm
  close and `onChanged` still fires. A failed refresh shows "Saved, but the list couldn't refresh — reload the
  page." rather than the write error. A failed write still shows its error and keeps the form open. Guards:
  "L2: a failed refresh after a successful write..." and "L2: a failed write still shows the error...".
- **L3 fixed.** `CommissionerListButton` sets `aria-controls` only while `open` (the panel renders null when
  closed). Guard: `Superlatives.test.tsx` "L3: the trigger only points aria-controls at the panel once the panel
  exists".
- **Contract gap fixed.** `contracts/awards-api.md` GET conduct-list entry example and description now include
  `alsoFlaggedBySleeperWeeks` (int[]).

Verification: backend `cleanTest test` ran 2056 tests (1 failed, 1 skipped), the failure and the skip being the
known pre-existing `BoardRepositoryLatestBeforeIT` failure and the `PlayerStatsLeaderboardReadIT` self-skip.
`PlayerContributionsTest` is 12/12. Frontend `tsc -b` is clean, vitest is 2247/2247 across 132 files, and the
production build succeeds.

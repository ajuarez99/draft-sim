# Adversarial plan review: 010 superlatives full standings (T000)

**Reviewed**: 2026-09-29, against the worktree at `587fbc3` (origin/main). This pass read
`spec.md`, `plan.md`, `tasks.md` and `claude/superlatives-full-standings.md` cold, then
checked their claims against the code. **No code was built or run.** Nothing below comes from
executing the feature. "VERIFIED" means I read the code (file:line cited) or ran a read-only
query against the local DB (`localhost:5433`, db `draftsim`). "SUSPECTED" means I reasoned
about it but didn't confirm it.

Note on access: the local cluster is **not** trust-auth as the brief assumed. It needs the
`application.yml` default password (`draftsim`). Every query was a read-only `select` / `\d`.

## Summary

- **Two High findings.** Either one would ship a broken feature while the plan's own guard
  stayed green:
  - **R1.** The controller builds the JSON by hand, field by field. Adding `standings` and
    `playerStandings` to the record puts nothing on the wire, and no task touches the
    controller.
  - **R2.** `nameByRoster.keySet()` is not every roster. `teamMaps` skips a roster with no
    name, which is exactly an orphaned roster.
- **Six Medium findings:**
  - **R3.** T017's "guard on real data" doesn't exist yet. The IT's fixture has no rosters,
    scores or pairings, and no test calls the GET endpoint.
  - **R4.** For WAIVER and EMBIID, "absent means a real 0" can put non-winners at rank 1 when
    the builder's own max is ≤ 0.
  - **R5.** FR-008's "the card's own value line" exists for only 2 of the 12 kinds, and for
    luck it would print "-1.3 wins".
  - **R6.** FR-009 compares Sleeper `username` against `display_name`, which are different
    fields.
  - **R7.** Stopping propagation on click alone still lets keyboard Enter on "Games" open the
    modal, and `role="button"` around a `<details>` nests interactive content.
  - **R8.** T018 omits the modal card's own `stopPropagation`, and T020 builds the formatter
    in a component that doesn't render the modal.
- **The rest are Low:** stale line numbers, ambiguous wording, and edge-case notes.
- **Amendment 1 (the F1 correction) checks out.** So do the rounding and tie parity for 10
  of the 12 kinds, as long as T011 computes margins in `BigDecimal` (R9).

## Findings

### R1. High. VERIFIED. The new fields never reach the wire

- **What.** `GET /api/leagues/{id}/superlatives` doesn't serialize the `Superlative` record
  directly.
  - `SuperlativesController.superlativeRow` (`SuperlativesController.java:206-226`) copies
    the fields by hand into a `LinkedHashMap`: `kind`, `available`, `reason`, `early`,
    `value`, `unit`, `holders`, `emptyReason`, `detail`, `coverage` and `playerHolders`.
  - `body()` (`:182-204`) does the same for `Result`.
  - So after T002, `standings` and `playerStandings` exist on the record and are silently
    dropped from the JSON.
- **Consequences.**
  - The frontend would get `undefined`. T020's `s.standings.length` would then throw at
    runtime, or never make a card clickable.
  - The spec's Assumptions ("added to the existing payload") and plan.md's Technical Context
    both name only `SeasonSuperlativesService.java` and a helper. No task mentions the
    controller.
  - This is the same class of bug as AGENTS.md's hard rule on hand-maintained wire types,
    one layer further down.
- **Where.** plan.md "Technical Context" and "Payload shape"; tasks T002/T003; spec
  Assumptions.
- **Recommended change.**
  - Add a task after T003: in `SuperlativesController.superlativeRow`, add `standings`
    (through a new `standingRow`, which reuses `holderRow` for `team`) and `playerStandings`
    (through a `playerStandingRow`).
  - Both must be built as a `LinkedHashMap`, **never `Map.of`**. `rank`, `value`, `note`,
    `missingReason`, `team.managerId`, `team.avatarId`, `position` and `team` are all
    legitimately null (AGENTS.md hard rule).
  - T017 should assert through the controller's `ResponseEntity` body, not the service's
    `Result`. Otherwise it can't catch this.

### R2. High. VERIFIED. `nameByRoster.keySet()` is not "every roster in the league-season"

- **What.** tasks.md, before T010, calls this the roster universe and states it as fact.
  - `teamMaps` (`SeasonSuperlativesService.java:1216-1222`) puts into `managerByRoster` and
    `avatarByRoster` for **every** `roster_season` row. It puts into `nameByRoster` only
    `if (name != null && !name.isBlank())` (`:1221`).
  - `name` falls back to `s.managerName()` = `manager.display_name`, from a `left join`
    (`RosterSeasonRepository.java:86-90`).
  - So an **orphaned roster** (`roster_season.manager_id` is null, which V5 allows
    explicitly: `V5__league_history_and_power_rankings.sql:5-7,11`) has no entry in
    `nameByRoster`.
  - Sleeper produces orphans when someone leaves mid-season (`RosterWeekPointsRepository.java:146-147`).
- **Consequences.**
  - Every standing list would drop that roster.
  - If the orphan held a record (for example the lowest week), rank 1 would lose a holder.
  - The spec's FR-001 would be violated.
  - It's the "claim about the code stated as fact but never checked" class.
- **Why the plan's guard won't catch it.** The local DB has **0 orphans** in every league
  (query: `roster_season` with `manager_id is null` returns 0 for leagues 3, 4, 5, 210, 211,
  9465 and 9466). T017 against local data would pass while the bug ships.
- **Recommended change.**
  - Define the universe as the `roster_season` roster ids. Use `managerByRoster.keySet()`,
    which `teamMaps` fills for every row, or better, have `teamMaps` return an explicit
    `Set<Integer> rosterIds` so the rule has a name.
  - `holder()` already falls back to `"Roster N"` for the name (`:1194`), so rendering an
    orphan needs no other change.
  - Add a unit test with an orphan roster (null manager, null name) for at least one kind.
    The T017 fixture (see R3) should include one.

### R3. Medium. VERIFIED. T017's "regression guard on real data" has no real data and no GET call to extend

- **What.** plan.md amendment 1 and FR-004 rely on T017 to catch the two populations drifting
  apart "on real data". But `SuperlativesControllerIT`:
  - only exercises the conduct-list endpoints (`:120-273`). No test calls
    `controller.superlatives(...)`;
  - seeds a fixture league with **no** `roster_season`, `roster_week_points` or
    `league_matchup` rows (`:69-97`);
  - has a fixture `total_rosters` of 10 but zero roster rows.

  T017's instructions ("from the IT's own `select count(*) from roster_season`… against the
  resolved league row") are silent on where the rows come from. A builder must choose either
  to seed a synthetic season, which isn't "real data", or to point at the local leagues
  (`1254190892974084096`, `1229352720222134272`), which exist only on this machine and would
  skip or fail on a fresh DB.
- **Recommended change.** Say which one explicitly.
  - Suggested: seed a small synthetic season in the IT, with 4 rosters, one of them orphaned
    (R2), a tied top week, a tied game, and a roster with no wins.
  - Also add an **assumption-gated** check against the named local 2025 leagues, which calls
    `Assumptions.assumeTrue` when they're absent.
  - Whichever is chosen, T029 must record whether the real-data check ran or skipped.
  - Also: the GET needs a visible league, so use `TestAdmin.asAdmin()` or a member's
    `X-Sleeper-User` (`SuperlativesController.java:55-58`). Name which.

### R4. Medium. VERIFIED (code), SUSPECTED (occurrence). "Absent is a real 0" breaks rank-1 = holders when the builder's max is ≤ 0

- **What.** Two kinds pick winners from **only the rosters present in the map**, and the
  standings would add every absent roster at 0:
  - WAIVER picks from `byRoster.values()` (`SeasonSuperlativesService.java:644-650`);
  - JOEL_EMBIID does the same (`:877-882`).

  If the map's max is ≤ 0, the absent rosters tie or beat the card's holders:
  - **Waiver.** `WaiverPickupAttribution.attribute` sums raw `players_points`
    (`WaiverPickupAttribution.java:121-127,141`), which can be negative. A streamed DEF or K
    started off waivers can score below 0, and a roster whose only started pickup scored
    0.00 is present with total 0. Early in a season, "every present roster ≤ 0" is plausible.
    The card then names a −1.00 "Waiver Wire Warrior", and the modal ranks eleven 0.00 teams
    above him.
  - **Embiid.** A regular contributor with a missed game and `pointsPerGame` 0.0
    (`AbsenceCost.java:94-97`) puts a roster in the map at 0.00. Absent rosters would tie it
    at rank 1 without being holders.
  - CLOSE_WINS, CLOSE_LOSSES and UNETHICAL are safe: their counts are ≥ 1 whenever they're
    in the map (`:380`, `:1113`).
- **Recommended change.** The spec must pick a rule; the builder can't guess.
  - Option (a): the builders select winners over the full universe with absent = 0. That's
    a card behaviour change, and a max of 0 would become "nobody yet".
  - Option (b): the standings keep absent rows at 0, and the invariant is stated as
    `holders ⊆ rank-1` for WAIVER and EMBIID when `max ≤ 0`.
  - Recommendation: (a) for WAIVER, where a winner at ≤ 0 is meaningless, and treat a 0 max
    as the empty state for EMBIID.
  - Either way, add a unit test in T009 with a negative-total or zero-total map.

### R5. Medium. VERIFIED. FR-008 / amendment 5 cite a "card's own value line" that 10 of the 12 team kinds don't render

- **What.** The card renders its shared value line only when
  `!isSingleEventKind && !isReadingKind` (`Superlatives.tsx:314-323`). That's only
  CLOSE_WINS and CLOSE_LOSSES.
  - HIGHEST_WEEK, LOWEST_WEEK, BIGGEST_BLOWOUT and CLOSEST_GAME print
    `${points.toFixed(2)} points · week N` or `… points over X · week N` from
    `holderDetailLines` (`:350-359`).
  - The reading kinds (LUCKIEST, UNLUCKIEST, BENCH, WAIVER, EMBIID, UNETHICAL) print their
    own sentences (`:368-438`).
- **The concrete regression is UNLUCKIEST.** `formatValue(v, 'WINS')` is `${value} wins`
  (`:473`), with no fixed decimals and a raw sign.
  - The modal would print "-1.3 wins" (or "-2 wins").
  - The card says "1.30 fewer wins than their scores earned" (backend `luckReading`,
    `SeasonSuperlativesService.java:499-502`).
  - So "the modal and the card can't disagree" is false as written.
  - UNETHICAL would print "3 games" where the card says nothing numeric per holder. That's
    acceptable, but it isn't "the same as the card".
- **Recommended change.** Amend FR-008 and T020 to define the modal's figure per kind,
  explicitly:
  - LUCKIEST / UNLUCKIEST: a signed 2-decimal figure matching the reading, for example
    `+2.40 wins vs expected` / `−1.30 wins vs expected`, or reuse the reading wording;
  - POINTS kinds: `toFixed(2) points`;
  - CLOSE_*: `formatCloseGameCount`;
  - UNETHICAL: `N games`.

  Extract it into one exported pure function (for example `standingValueText(s, v)`) that
  the card and the modal both call, and unit-test UNLUCKIEST's negative value.

### R6. Medium. VERIFIED. FR-009 compares two different Sleeper fields

- **What.** T020 highlights the row where `useUser().username === team.username`. The two
  aren't the same Sleeper field:
  - `BkUser.username` is Sleeper's **`username`** (the lowercase handle):
    `SleeperUserController.java:54`, `user.ts:12`.
  - `Holder.username` is filled by `withUsernames` from `StandingRow.managerName()`
    (`SeasonSuperlativesService.java:299-302`), which is `manager.display_name`
    (`RosterSeasonRepository.java:86`). That's Sleeper's **`display_name`**, stored by ingest
    (`LeagueIngestService.java:152`).
  - They routinely differ, at least in case, so the highlight would silently never match.
    FR-009's "nothing matches → nothing highlighted" makes the failure invisible.
- **The established pattern.** The repo decides "me" server-side, from Sleeper's `owner_id`
  against the caller's `X-Sleeper-User` (`LeagueAnalysisService.java:122-125`, `isMe`).
- **Recommended change.** Pick one:
  - (a) Add `isMe` to `Standing`, computed in the controller from `X-Sleeper-User`. This
    needs the roster's owner sleeper id, which `manager.sleeper_user_id` has.
  - (b) Minimum fix: compare `user.displayName` to `team.username`, case-insensitively, and
    document it as a display-name match.

  (a) matches the rest of the app. Also update T021's test so the fixture uses the right
  field.

### R7. Medium. VERIFIED (code), SUSPECTED (runtime). Clickable `<article>` versus the `<details>` inside it

- **What.**
  - T020 stops **click** propagation on `<details>` but puts an Enter/Space handler on the
    article. `keydown` on the focused `<summary>` bubbles to the article's `onKeyDown`, so
    pressing Enter on "Games" would toggle the list **and** open the modal. This follows
    from DOM bubbling; it wasn't executed.
  - Space on the article needs `preventDefault`, or the page scrolls.
  - `role="button"` makes the children presentational to assistive tech, and a
    `<summary>` inside a button is nested interactive content. Screen-reader users may lose
    "Games" and "Swing weeks" entirely.
  - The article's accessible name becomes the entire card text.
- **Checked.** Existing tests query buttons **by name** only (`Superlatives.test.tsx:986,
  989, 1022, 1041, 1079, 1082, 1090`), and none query `role="article"`. So adding
  `role="button"` doesn't break an existing query. The conduct list sits outside the cards
  (`Superlatives.tsx:145-155`), so it isn't a nested child.
- **Recommended change.**
  - Keep the whole-card mouse click. Put keyboard and AT access on a real
    `<button className="sl-standings-open">` in the card header ("See all N teams"), and
    don't use `role="button"` on the article.
  - Or, if the article stays the button, stop propagation of `keydown` as well as `click`
    on `<details>`, and add a T021 case: "Enter on the Games summary does not open a
    dialog".

### R8. Medium. VERIFIED. Two T018/T020 instructions a builder would have to guess at

1. **The modal card's own `stopPropagation`.** T018 lists the `StartMockModal` pattern
   (backdrop `onClick={onClose}`, `role="dialog"`, Escape) but omits the inner card's
   `onClick={(ev) => ev.stopPropagation()}` (`StartMockModal.tsx:104`). Without it, any
   click inside the modal closes it. Add it to the bullet list, and to T021 as "clicking a
   row doesn't close the dialog".
2. **Where the formatter lives.** T020 says to render the modal in `Superlatives` (page
   state `openKind`), but to "build [formatValue] once in `SuperlativeCard`". The card
   doesn't render the modal, so a builder has to lift a function through state or
   duplicate the branch, which is the drift FR-008 is trying to prevent. Resolve it with
   R5's single exported pure function, called by both.

Related (Low): the phone sheet. `PowerRankings.tsx` found that a fixed backdrop didn't cover
the rail on a real phone, and fixed it with `body.bk-modal-fullscreen`
(`PowerRankings.tsx:405-420`, `styles.css:2917`). T019's 375px sheet should reuse that class
rather than rediscover the bug.

### R9. Low. VERIFIED. T011 must compute margins in `BigDecimal`, not in doubles

- **What.** The card's margins are `BigDecimal` subtraction (`LeagueRecordService.java:309`),
  compared with `compareTo` (`SeasonSuperlativesService.java:429`). `closeGames` does the
  same (`:351`).
- If T011 computes `aPoints.doubleValue() - bPoints.doubleValue()`, float error can split a
  tie the card treats as equal. For example, 100.10 − 90.05 isn't exactly 10.05 as a double.
  That would break ranks 1, 1, 3 and rank-1 = holders.
- **Recommended change.** Add one line to T011: "margin =
  `aPoints.subtract(bPoints).abs().doubleValue()`, exactly as `closeGames` does."

### R10. Low. VERIFIED. Amendment 1's "no extra filter" is slightly off, but harmless on current data

- **What.** `breakdownsFor` filters `league_id = ? and season = ?`
  (`RosterWeekPointsRepository.java:133`). `extremes` filters on `league_id` and week only
  (`:183`).
- They agree only while `roster_week_points.season = league.season`. The upsert rewrites
  `season` on conflict (`:42`).
- **Measured.** 0 mismatched rows across all leagues, and 0 `roster_week_points` rosters
  missing from `roster_season` (read-only queries).
- **Recommended change.** Reword amendment 1's bullet to "the same rows, plus a `season =`
  filter that removes nothing today (0 mismatches measured 2026-09-29)". T017 remains the
  guard.

### R11. Low. SUSPECTED. Rows whose roster isn't in the universe

- **What.** `extremes` and `pairedWithScores` use `left join roster_season`. A score or
  pairing row for a roster id with no `roster_season` row can still become a card holder,
  but it isn't in the universe.
- **Measured.** 0 such rosters locally.
- **Recommended change.** T007, T008 and T010 should state what happens to a data row whose
  roster isn't in `rosterIds`. Suggested: it is still ranked, because the card counted it,
  and it's added to the universe. The alternative is to drop it and let T017 fail loudly.

### R12. Low. VERIFIED. The count kinds' "a roster with no games at all is a real 0"

- **What.** The spec's edge case makes CLOSE_WINS, CLOSE_LOSSES, conduct and waiver a real 0
  for a roster with **no games or scored weeks at all**. That is the "absent means 0" rule
  on a roster that wasn't measured.
- The only pairing caveat is league-level (`pairingCoverage`, `:257-259`), and it applies to
  all rosters equally. It doesn't cover one roster missing from every game.
- **Measured.** Every 2025 roster appears in `roster_week_points` / `league_matchup` locally
  (12 of 12).
- **Recommended change.** A roster with no `roster_week_points` rows (and, for the close-game
  kinds, no paired game) gets `hasValue=false` with "no scored weeks" / "no games paired".
  Keep absent = 0 only for rosters that were measured.

### R13. Low. SUSPECTED. EMBIID and WAIVER per-row undercounts that aren't the unclassified kind

- **Embiid.** `neverWalked` is reported only league-wide (`:866-868`). A roster whose
  regulars were never walked by the per-game backfill shows a confident 0.
- **Waiver.** A roster-week with empty starters is excluded (`:623-627`). If all of one
  roster's weeks were excluded, it shows 0.
- **Measured.** No such rows locally: 0 roster-weeks without starters in leagues 4, 5, 211
  and 9466.
- **Recommended change.** Extend FR-010's per-row note to "N rostered players have no
  game-by-game records" (Embiid) and "N weeks without stored starters" (Waiver), counted per
  roster. Alternatively, record in the spec that these are accepted as league-level only.

### R14. Low. VERIFIED. Smaller ambiguities

- **Luck signatures.**
  - `addLuckSuperlatives(built, sleeperLeagueId, bound, throughWeek, early)`
    (`SeasonSuperlativesService.java:457`) doesn't receive the name, avatar or manager maps
    or the roster ids. T013 must thread them in.
  - `luckSuperlative`'s static signature is called by `SeasonSuperlativesLuckTest.java:37-56`.
  - Say whether to attach standings in `addLuckSuperlatives` (keeping `luckSuperlative`'s
    signature) or to update that test.
- **T013's note format.** `"{actualWins} actual vs {expectedWins} expected"`: `actualWins`
  is an unrounded double (`ExpectedWinsService.java:290`), so it prints `7.0` or `5.5`.
  Specify the formatting.
- **T011 CLOSEST_GAME note for a 0-margin tie.** With the `>=` rule, side A "won vs". Say
  "tied with {opponent}" when the margin is 0.
- **T023/T022.** Say whether `top()` applies the `MIN_ADDS_TO_NAME` floor itself or the
  caller does. T022 tests it on `top()`.
- **T020 vs T025.** The card's clickability condition must be
  `standings.length > 0 || playerStandings.length > 0`. T020 alone says `standings`.
- **T017** skips JABARI_SMITH_JR entirely. Add "rank-1 `playerStandings` ids equal
  `playerHolders` ids".
- **Stale line numbers.** `api.ts` has moved +14 since `77151f6`:
  `SuperlativePlayerHolder` is at `:1953` (T006 says `:1939`), and `Superlative` is at
  `:2059` (T006 says `:2045`). `SeasonSuperlativesService.java` and `Superlatives.tsx` did
  not change between `77151f6` and `587fbc3`.
- **The frontend test mocks all of `../api`** (`Superlatives.test.tsx:16-21`). If the new
  modal imports any runtime value from `../api`, it's `undefined` under test. Keep the modal
  to type-only imports from `api.ts`.

## Checked and fine

- **Amendment 1 (the F1 correction) is right.**
  - `scoredWeeks` is the stored weeks `< playoffWeekStart`, or all stored weeks
    (`:204-211`).
  - The bound is `max(scoredWeeks)` (`:226, 230`), so every stored week ≤ bound is in
    `scoredWeeks`.
  - `games` is the same `pairedWithScores(…, bound)` call that `LeagueRecordService.margins`
    makes (`LeagueRecordService.java:289`), with a filter that removes nothing.
- **Rounding parity for the rank-1 check holds for these kinds:**
  - Week scores are `numeric(8,2)` (`V5:35`), so `round2(double)` is lossless.
  - Bench is already `round2`'d in `aggregateBench` (`:532`), and the winner is chosen on
    that value.
  - Waiver totals are `round2`'d in `attribute` (`WaiverPickupAttribution.java:142`).
  - Embiid totals are `round2`'d in `AbsenceCost.compute` (`AbsenceCost.java:107`).
  - Luck `winsAboveExpected` is `round2`'d in the `TeamRow` (`ExpectedWinsService.java:290`),
    and `luckSuperlative` compares that value with `Double.compare`.
  - The conduct and close-game counts are ints.
  - Margins are exact only with R9.
- **Tie comparison.** Each builder uses `==` / `Double.compare` / `compareTo` on the same
  values the standings would use. Competition ranks on the same keys give rank-1 = holders
  for HIGHEST_WEEK, LOWEST_WEEK, BIGGEST_BLOWOUT, CLOSE_WINS, CLOSE_LOSSES, LUCKIEST,
  UNLUCKIEST, BENCH and UNETHICAL. WAIVER and EMBIID hold too, except in R4's max ≤ 0 case.
- **CLOSEST_GAME.** Holders are winners only (`:431`), with `aWon` as `>=`
  (`LeagueRecordService.java:308`). Amendment 2's `holders ⊆ rank-1` is correct.
- **BIGGEST_BLOWOUT.** The winners of the max-margin games equal the rosters whose best
  winning margin is the max. Rank-1 = holders holds, given the same `>=` rule in T011.
- **Luck roster set and window.** `ExpectedWinsService` reads the same resolved league
  (through the same resolver), the same bound, and `pairedWithScores` filtered to
  `season == league.season` (`ExpectedWinsService.java:245-251`). Its roster set is the
  rosters in those games (`:280`). T013's "no expected-wins row" `hasValue=false` covers
  the rest.
- **Holder names for luck.** `luckSuperlative` builds its holders from `TeamRow`, not
  `holder()`. `ExpectedWinsService` uses the identical name fallback chain
  (`ExpectedWinsService.java:266-273` vs `SeasonSuperlativesService.java:1216-1222`), so the
  modal and the card name a luck team alike.
- **Bench absent → `hasValue=false`** is right: `aggregateBench` includes only rosters with
  ≥ 1 valid week (`:522-534`).
- **Amendment 3** is right. `withUsernames` rebuilds the `Superlative` (`:1198-1205`), and
  nothing else in `main` rebuilds it. The only other constructors are in
  `SeasonSuperlativesLuckTest` and `SeasonSuperlativesMostAddedTest`, through the static
  builders.
- **Amendment 4's wording** matches `unclassifiedCoverage` (`:914-921`), minus its
  `"roster N: "` prefix.
- **`MostAddedPlayers.rank`'s filter loop** is at `:53-59`, as T023 says.
  `mostAddedSuperlative`'s signature is unchanged by T024, so `SeasonSuperlativesMostAddedTest`
  still compiles.
- **`useUser()`** needs no provider (module state plus `localStorage`, `user.ts:67-77`), so
  adding it to `Superlatives` doesn't break the existing tests, which don't wrap in one.
  Tests can call `setUser` instead of mocking.
- **Existing `Superlatives.test.tsx` queries** are by button name, text or `closest(...)`.
  Nothing queries the article role, so `role="button"` alone breaks no existing assertion.
  T006's fixture update is still required for `tsc`.
- **The other line anchors** in tasks.md for `SeasonSuperlativesService.java` and
  `Superlatives.tsx` match the current tree.
- **Local data sanity.** The 2025 leagues (ids 5, 211 and 9466) each have 12
  `roster_season` rows, and all 12 appear in scores and pairings. "Ball Knowers" NBA 2026
  (id 210) has 0 scored rows, so T030's fallback-season check should exercise the walk-back
  (assuming the resolver walks back on no scored weeks; not re-checked here).

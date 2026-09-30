# Code review (T030): spec 010, superlatives full standings

**Reviewed:** commit `cfebb71` on `010-superlatives-full-standings`. The change was committed while
this review was running, so the review covers that commit rather than an uncommitted diff. Docs were
skipped.
**Reviewer role:** bug hunt, not a style pass. No source files were modified.

## What was executed

- **Backend tests:** `./gradlew test --tests '*Superlative*' --tests '*MostAdded*'` passed.
  - `SuperlativesStandingsIT` ran 7 tests with 0 skipped, against local Postgres on 5433.
  - `SuperlativesControllerIT`: 10 tests.
  - `SeasonSuperlativesStandingsRecordsTest`: 13 tests.
  - `SeasonSuperlativesStandingsTotalsTest`: 15 tests.
  - `SuperlativeStandingsTest`: 9 tests.
  - `MostAddedPlayersTopTest`: 10 tests.
- **Frontend tests:** `npx vitest run src/pages/Superlatives.test.tsx` passed 53 of 53.
- **Type check:** `npx tsc -b` was clean.
- **Read-only psql against real local data:**
  - 0 `roster_week_points`, `league_matchup` or `league_transaction` rows have a roster missing from
    `roster_season`, so the R11 skip never hides a real winner today.
  - 0 roster_season rows have a null manager.
  - 0 week-points rows have a season that disagrees with their league.
  - There is one real 0-margin game: NBA 2024 (`1141438340626231296`), week 18, rosters 1 and 5,
    252.50 each.
- **Not executed:** a rank-1 = holders comparison of the real API response on real leagues. No server
  was running from this worktree. Refresh-on-visit (spec 009) would write to the shared DB, so I did not
  start one. That check belongs to T031.

## Findings

### B1 (Medium, CONFIRMED): UNETHICAL's modal labels player-weeks as "games"

- **Code:**
  - `conductStandings` ranks `totalByRoster`: the sum of `weeks().size()` over each roster's
    qualifying rows, which is **player-weeks** (`SeasonSuperlativesService.java:1237`, `:1474`).
  - The modal formats that through `standingFigure`, then `formatValue(value, 'GAMES')`, which prints
    `"N games"` (`web/src/pages/Superlatives.tsx:510-521`).
- **Why it's new:** the card never showed this total. `UNETHICAL` is in `READING_KINDS`, so it has no
  value line and its holder lines list players and weeks. The modal is the first place the number
  appears, and it arrives mislabelled.
- **Failure scenario:**
  - One suspended player on an NBA roster for 2 fantasy weeks reads **"2 games"**. That player really
    missed about 6–8 NBA games.
  - Two players suspended in the same week read "2 games" for one week.
  - The reader takes the number as games missed. This is the "label the axis" failure from memory.
- **Fix:** add an `UNETHICAL` branch to `standingFigure`, e.g. `"N player-week(s)"` or
  `"N flagged week(s)"`. Leave the wire `unit` alone. Add a Vitest assertion on the modal text.

### B2 (Medium, CONFIRMED): the IT's "every kind carries standings" guard covers only 8 of 12 team kinds

- **What the plan says:** the Risks section says the IT guards the defaulted-constructor trap by
  asserting `standings.size() == rosterCount` for **every available kind with holders**.
- **What the seed actually produces:** no `players_points`, no `starters`, no conduct entries, and
  transactions only in the Jabari test.
  - MOST_BENCH_POINTS is empty: no usable breakdown.
  - WAIVER_WIRE_WARRIOR is unavailable. In the Jabari test it has no starters, so it's empty.
  - JOEL_EMBIID is empty: "nobody's been bitten".
  - UNETHICAL is empty.
- **So the loop only reaches** HIGHEST/LOWEST_WEEK, BIGGEST_BLOWOUT, CLOSEST_GAME, CLOSE_WINS/LOSSES
  and LUCKIEST/UNLUCKIEST. `assertTrue(checked >= 6)` (`SuperlativesStandingsIT.java:207`) wouldn't
  even notice if luck dropped out.
- **What's left unguarded:**
  - `benchSuperlative` (`:661-663`) and `unethicalSuperlative` (`:1258-1260`) are private and are
    called by no test. If either went back to the 10-argument constructor, `standings` would silently
    become `[]`, the card would stop being openable, and the whole suite would stay green. That's the
    "optional params that encode rules" class the plan names.
  - `waiverWinners` and `absenceWinners` are covered by `SeasonSuperlativesStandingsTotalsTest`.
- **Fix:**
  - Assert the exact set of kinds checked, not `>= 6`.
  - Either extend the seed so bench and unethical have holders (breakdown JSON plus `starters`, and one
    `league_conduct` entry), or record in the plan that T031's live check is the only guard for those
    two builders.

### B3 (Low-Medium, CONFIRMED from code and DOM semantics): selecting text on a card opens the modal

- **Cause:** `<article onClick={openable ? () => setOpen(true) : undefined}>`
  (`Superlatives.tsx:206`) has no guard. A mouse-down and drag across card text, for example to copy
  a team or player name, ends with a `click` on the common ancestor, and the modal opens.
- **The reverse case:** a drag that starts inside the modal card and is released on the backdrop
  fires `click` on the backdrop, which closes the modal (`SuperlativeStandingsModal.tsx:61-64`). A user
  selecting a row's text and overshooting loses the modal.
- **Fix:**
  - In the card handler, return early when `window.getSelection()?.toString()` is non-empty.
  - Close on the backdrop only when `e.target === e.currentTarget` and the gesture started on the
    backdrop: track `onMouseDown` target.

### B4 (Low, CONFIRMED): JOEL_EMBIID's modal figure drops "estimated"

- **Card:** says `"N estimated points lost"` and `"~X per game (estimated)"`.
- **Modal:** every row prints `formatValue(v, 'POINTS')`, which is `"12.40 points"`, with no
  qualifier. Every Embiid figure is an estimate (`AbsenceDetail.estimated` is always `true`), so the
  modal states an estimate with the confidence of a measurement. AGENTS.md: "Label a guess as a
  guess".
- **Fix:** `standingFigure` returns `"~12.40 est. points lost"` (or similar) for `JOEL_EMBIID`.

### B5 (Low, CONFIRMED, not reachable on today's data): BIGGEST_BLOWOUT treats a 0-margin tie as a win for one side only

- **Code:** `marginStandings` blowout mode uses `aWon = cmp >= 0` (`SeasonSuperlativesService.java:1321`,
  `:1329`). In a tied game, the lower roster id (side A) gets a "win" with value 0.00 and note
  `"vs X · week N"`. Side B gets nothing from that game.
- **Scenario:** a roster whose only non-loss is a tie shows **"0.00 points, vs X"** at a real rank.
  Its tie partner, in the same position, shows "no wins yet" unranked. Which one gets the row depends
  only on roster id.
- **Why it's still consistent:** it matches `LeagueRecordService.margin` (`>=`), so rank 1 = holders
  holds.
- **Real data:** the one real tie (NBA 2024, week 18) involves rosters with 10 and 14 real wins, so
  it's invisible today.
- **Fix:** skip `cmp == 0` games in blowout mode. If the card must stay aligned in the "every game was
  a tie" degenerate case, filter ties in `marginSuperlative`'s blowout rows too, or accept the one-line
  divergence.

### B6 (Low, CONFIRMED, pre-existing and widened by R4): Embiid's empty states drop the unclassified-week caveat

- **Code:** both empty paths use `emptyCoverage`, which carries league-level reasons only
  (`SeasonSuperlativesService.java:989-1002`).
  - The old `byRoster.isEmpty()` path already did this.
  - The new `max <= 0` path (amendment 9) does too.
- **Scenario:** no priced absence, but some rosters' regular contributors have UNCLASSIFIED weeks. The
  card flatly says **"no absence has cost anyone points yet"**, although those weeks are exactly
  "unknown", not "zero".
  - Before amendment 9, the `max <= 0` case went through the winner path, which merged the
    top rosters' unclassified reasons into coverage.
  - Standings are `[]` in the empty state, so the modal can't be opened either, and its per-row
    unclassified notes (amendment 4) can't be seen.
- **Fix:** when `unclassifiedWeeksByRoster` is non-empty, add a league-level reason such as "N rosters
  have weeks that couldn't be classified" to `emptyCoverage`.

### B7 (Low, observation, not a correctness bug): Jabari's "top 10" routinely runs to 20+ rows

- **Rule:** `MostAddedPlayers.top` keeps every player tied at the cut (by design, FR).
- **Approximate real counts:** raw adds per player, ignoring the week window and the DEF filter.
  - NFL 2025 `1254190892974084096`: 2 players at 5 adds, 6 at 4, 16 at 3. The cut lands on 3 adds, so
    **24 rows**.
  - NFL 2025 `1262506916429430784`: about 21 rows.
  - NBA 2024: about 20 rows.
- **Verdict:** correct, but the list is not what "top 10" suggests. Keep it, and consider saying so in
  the modal ("ties at the cutoff are all shown"). No code change is required for correctness.

## Should the modal use a portal? (hunt item 7)

**Not required for correctness today. Recommended as cheap hardening.**

Checked:

- **Containing block:** no ancestor of the card (`.content`, `.sl-cards`, `.sl-card`) sets
  `transform`, `filter`, `contain`, `will-change` or `backdrop-filter`. So `position: fixed` on
  `.modal-backdrop` resolves to the viewport. The CSS comment at `styles.css:4700-4705` deliberately
  avoids a hover transform for this reason.
- **Stacking:** no ancestor creates a stacking context with a z-index. The modal inherits the same
  situation as `PlayerCard`, `PlayerPicker`, `StartMockModal` and PowerRankings' ballot, which all
  render inline. The repo's `createPortal` uses are for header and rail slots, not modals.
- **CSS inheritance:** `.sl-card` sets no inherited properties (only border, padding, background and
  min-width). There are no `.sl-card li`, `.sl-card h3` or `article …` descendant selectors that
  would reach the modal's content.
- **Event bubbling:**
  - React bubbles synthetic events through the component tree, and a portal would bubble to the
    article all the same. So a portal would **not** remove the need for the `stopPropagation` calls
    on `.modal-card` and on the backdrop. Both are present and correct.
  - The article has only `onClick`, so no other event types matter today.
- **Side effects of rendering inside the card:**
  - `.sl-card-openable:hover` stays active while the pointer is over the modal, because the modal is
    a DOM descendant. That's cosmetic, under the dim.
  - Any future handler on the article (e.g. `onKeyDown`) would silently receive modal events.
  - A portal to `document.body` removes the DOM-descendant effects, not the React bubbling.
- **Escape:**
  - Document-level listeners also exist in the rail (`LeagueRailSection.tsx:258`, `Rail.tsx:99`, both
    gated on their popover being open) and in AppShell's Ctrl+K (`AppShell.tsx:162`).
  - Ctrl+K while the modal is open opens JumpTo on top of it. Escape then closes both at once.
  - Minor. It's the same as every other modal in the app.
- **Focus:** focus goes to Close. On unmount it returns to the element that was active at open.
  - Via "See all" that's the button.
  - Via a card-body click it's `body`, which is harmless.
  - StrictMode's double effect run was traced and ends on the right element.
  - There's no focus trap: Tab can leave to the page behind. That's the same as the other modals.
- **Nested interactive elements:**
  - The card has only `<details>`, which stops `click` and `keydown`. A browser's synthetic click on
    Enter or Space on `<summary>` is stopped there, so it doesn't open the modal.
  - `Avatar` and `PersonName` are non-interactive: no button, link or `onClick`.
  - "See all" stops propagation.

## Checked and fine

**Rank 1 vs holders, per kind** (sources and rounding compared):

| Kind | What was compared | Result |
|---|---|---|
| HIGHEST_WEEK / LOWEST_WEEK | Winner is BigDecimal `starters_points` via `extremes()`. Standings use `round2(getDouble)` from `breakdownsFor`. | The column is `numeric(8,2)` (V5), so `round2` is the identity. The row sets are equal (bound = max scored week, and the season filter is 0 rows apart on real data). Directions are `rank(..., !highest)`: LOWEST ascending, HIGHEST descending. Correct. |
| BIGGEST_BLOWOUT / CLOSEST_GAME | Standings use the same `pairedWithScores(bound)` rows, the same `>=` winner rule and BigDecimal subtraction. | CLOSEST includes both sides of a tie ("tied with"), so holders ⊆ rank 1 per amendment 2. The IT asserts both. |
| CLOSE_WINS / CLOSE_LOSSES | Same `closeGames` map. | A roster in no paired game is unmeasured (R12). Max ≥ 1 whenever there are holders. |
| LUCKIEST / UNLUCKIEST | Winner and standings both use `TeamRow.winsAboveExpected`. | That value is already `round2`'d in `ExpectedWinsService:290`, so neither side rounds differently. LUCKIEST is descending, UNLUCKIEST ascending. |
| MOST_BENCH_POINTS | `aggregateBench`'s totals. | Already `round2`'d, and re-rounding is idempotent. |
| WAIVER / JOEL_EMBIID | `RosterTotal.totalPoints` and `RosterCost.totalPointsLost`. | Both already `round2`'d inside `WaiverPickupAttribution:142` and `AbsenceCost:107`. So there is no float-noise split between the holders' unrounded `Double.compare` and the standings' rounded ties. Absent rosters are 0, and max > 0 guarantees they rank below the holder. |
| UNETHICAL | Integer counts. | Consistent. |

**Other checks:**

- **-0.0:** unreachable.
  - `round2` goes through `Math.round`, which returns a `long`, so it never yields -0.0.
  - `BigDecimal.doubleValue()` of zero is +0.0.
  - `(double) int` is never negative zero.
  - `DoubleStream.max`/`min` in the luck winner and the `Double.compare` ties agree on the values
    that actually occur.
- **`SuperlativeStandings.rank`:**
  - Competition ranks (1, 1, 3) are correct.
  - The null-value rows go last, sorted by roster id, in both directions.
  - It throws on a null value with no reason. Every caller supplies one.
- **R4 empty-state:**
  - No previously-correct winner goes empty except the intended max ≤ 0 case.
  - JOEL's `byRoster.isEmpty()` path and its `emptyCoverage` are byte-for-byte the old behaviour, now
    shared with the new path (see B6 for the caveat gap).
- **Constructors:** every `new Superlative(` that carries holders passes standings: lines 449, 473,
  497, 661, 745, 849, 1025 and 1258. Luck at 564 is wrapped by `withStandings` at 530-533. The short
  constructors are used only for empty or unavailable kinds.
- **`withUsernames`:** fills standings rows' team usernames and passes `playerHolders` and
  `playerStandings` through.
- **Controller:**
  - `standingRow` and `playerStandingRow` are `LinkedHashMap`s, so they don't hit the null trap.
  - Field names match `api.ts`'s `SuperlativeStanding`, `SuperlativePlayerStanding` and
    `SuperlativeHolder` field for field.
  - The IT asserts that every key is present on the wire.
- **`MostAddedPlayers`:**
  - `rank()` is behaviour-identical. `ranked()` uses `counted.size()`, which equals the old `max` for
    top-tier players.
  - `top()` shares `countedByPlayer`, applies `MIN_ADDS_TO_NAME`, and keeps ties at the cut without
    overshooting a distinct tier.
  - Its rank-1 tier equals `playerHolders` (same sort by playerId).
- **IT (item 8):**
  - The orphan (roster 4, null manager, so no name) really is outside `nameByRoster`. A regression to
    `nameByRoster.keySet()` would fail the `size == 4` and `Set.of(1,2,3,4)` assertions on every
    checked kind.
  - Cleanup touches only its own `sleeper_id`, `sleeper_user_id` and player ids. `league_transaction`,
    like the other child tables, cascades from `league` (V19).
  - Gap: see B2.
- **Frontend:**
  - The modal renders payload order and never re-sorts.
  - Keys are unique: one row per roster and per player.
  - `openable` is false for unavailable and empty cards.
  - The luck sign renders "+0.00" for 0.
  - The phone full-screen rule is scoped by `:has(.sl-standings-modal)`, and the rail-hiding rule
    stays phone-only.

# Spec 048: bug-hunting code review

Reviewer: a separate pass (Opus), 2026-10-26. Scope: the uncommitted diff on branch `048-weekly-high-score-ledger`
in worktree `.claude/worktrees/048-weekly-high-score-ledger` (against main ec72031), untracked files included. This
is not a style pass. Evidence labels:
- **VERIFIED**: I ran it (gradle on the shared 5433 DB, vitest, `tsc -b`, read-only SQL via `docker exec draftsim-pg psql`).
- **READ**: reasoned from the code.
- **SUSPECT**: plausible, but not reproduced.

**Result: one B-level defect (the live line can crown a 0.0 "leader"), three should-fix items, six nits.** None of
them changes what the feature *is*. The money arithmetic (zero-sum, tie cents, payers, both settle-up lists) held
up under everything I threw at it.

## Bugs (wrong output / crash / leak)

### B1. The live line names an every-roster 0.0 "leader" once any player game is stored (VERIFIED by the suite's own test)
- **Where:** `WeeklyPotService.liveLeader` (`engine/WeeklyPotService.java:226-242`). It gates on
  `LiveWeekSource.hasStarted(live, playerGamesExist)` (`LiveWeekSource.java:168-170`), which is
  `any roster > 0 || stored player games for that week`.
- **The rule it breaks:**
  - contracts/weekly-pot-api.md says `liveLeader` is null when "the week hasn't started (no roster above 0)".
  - plan A2 says "never a 12-way 0.0 tie".
  - The shared rule is the right *week-started* rule for spec 045's weekly report. It is the wrong *leader* rule.
    The week can have started while every roster in this league is still at 0.0. Two ways that happens:
    - an NBA Monday when the first games of the week involve none of the league's starters;
    - an NFL Thursday game with no starters from this league.
- **Scenario:** week 8 is live, player games for week 8 are stored, and all 12 live rows read 0.0. The page prints
  "Leading week 8 — not final: A & B & C & … & L, 0.0" under a pulsing dot. The same thing happens if one roster
  is negative (a D/ST or a kicker) and the rest are at 0: an 11-way "lead" at 0.0.
- **Evidence:** VERIFIED. `WeeklyPotServiceTest.storedPlayerGamesAloneCountAsStarted` sets four live rows at 0.0
  with a stored player game. It asserts `liveLeader().rosterIds() == [1, 2, 3, 4]`, and it passes. So the test pins
  the contract violation instead of catching it (lessons #33's shape: a test named for a rule that asserts the wrong
  thing).
- **Fix:**
  - Keep `hasStarted` as the gate. Also return null when `max <= 0`; the leader's score must be above zero.
  - Flip that test to assert `assertNull(liveLeader())`. Add a case where one roster is negative and the rest are
    at 0.0 (expect null), and one where one roster has 0.1 (expect that roster alone).
  - Leave `WeeklyReportService` alone: its use of the rule is correct.

## Should-fix

### S1. "Through week N" / "as of week N" understates which weeks the balances include (VERIFIED from the service test, page READ)
- **Where:**
  - `WeeklyPotService.view` (`:172-173`): `throughWeek` is the end of the unbroken DECIDED/NO_SCORES run.
  - Every later DECIDED week still goes into `decided` (`:150-153`) and so into balances and settle-up.
  - `WeeklyPot.tsx:107` prints `Through week ${throughWeek}.`, or "No week is decided yet." at 0.
  - `WeeklyPot.tsx:238` prints `(as of week ${throughWeek})`, but only when `throughWeek > 0`.
- **Scenario (A):** week 2 is stored but NOT_FINAL (e.g. a re-fetch after a stat correction), and weeks 3–5 are
  final.
  - VERIFIED: `aStoredButNotFinalWeekMovesNoMoneyAndCapsThroughWeek` asserts `throughWeek == 1`, while roster 4's
    net is `3 * 300` from weeks 1, 3 and 5.
  - The page then reads "Through week 1." and "Settle up (as of week 1)", but the amounts include week 5's money.
    That's memory's "count and label, one source" class: the label and the number come from different rules.
- **Scenario (B):** week 1 is MISSING (never ingested) and weeks 2–6 are DECIDED.
  - `throughWeek == 0`, so the header says "No week is decided yet."
  - The settle-up heading has no "as of" qualifier at all, though FR-009 / US2 scenario 3 want it.
  - Yet standings and settle-up show five weeks of money.
- **Note:** R6 ("through week N counts only an unbroken run") is a deliberate rule, and I'm not asking to change
  it. The issue is that the page uses it as the label for figures computed under a different rule.
- **Fix:** pick one of these:
  - (a) add `latestDecidedWeek` to the payload (and `api.ts`). Word the header "Includes decided weeks through N",
    and show "Week K isn't final yet" when `throughWeek < latestDecidedWeek`. Show "as of" whenever
    `!seasonFinal`, not only when `throughWeek > 0`.
  - (b) only feed weeks `≤ throughWeek` into the ledger, so the label is true. That's simpler, but a single stuck
    week then freezes the whole sheet.

  (a) is the honest one. Either way, add a service test for scenario B.

### S2. A league whose Sleeper `start_week` is above 1 never reaches "Final" and stays at "through week 0" (SUSPECT)
- **Where:**
  - The week walk always starts at week 1 (`WeeklyPotService.java:140`).
  - `seasonFinal` loops `1..boundWeek` (`:177`).
  - The page prints "Regular season: weeks 1–N" (`WeeklyPot.tsx:109`).
  - Nothing in the backend reads `settings_json->>'start_week'` (grep: zero hits).
- **Scenario:** a Sleeper league that starts its season in week 2 or later. If ingest stores no rows for the weeks
  before `start_week`, then:
  - those weeks are MISSING forever;
  - `throughWeek` stays 0 all season;
  - `seasonFinal` is never true;
  - the settle-up never stops saying "as of";
  - the week list carries permanent "Not loaded yet" rows.
- **Evidence:** VERIFIED that every local league has `start_week = 1` and min stored week 1, so this isn't visible
  here. Not checked: what Sleeper returns for `/matchups/{w}` when `w < start_week`. If it returns all-zero rows,
  ingest stores NO_SCORES, which counts as "moves", and this is harmless.
- **Fix:** start the walk (and the "weeks X–N" text) at `max(1, start_week)` when it's recorded. Or confirm during
  live verification against a prod league row that every league has `start_week = 1` and that pre-start weeks
  store zeros, and record that in research.md.

### S3. In a tie week the margin column calls 3rd place "2nd" (READ)
- **Where:** `WeeklyPotLedger.compute` sets `runnerUpPoints` to the best *non-winner*. `WeeklyPot.tsx:202-212`
  renders `+${winning − runnerUp} on 2nd`.
- **Scenario:** week 3 has a 2-way tie at 120 and the next best is 60. The row reads "Bravo & Charlie Tie 120.00
  +60.00 on 2nd". Second place was 120 (the co-winner), so the 60-point margin is over the 3rd-place team. FR-007
  asks for "the runner-up's score (margin)". For a tie week the honest margin to second is 0. If the gap to the
  first payer is what's wanted, it needs a different label.
- **Fix:** for `winners.length > 1`, label it "+60.00 on the rest" (or "on 3rd" for a 2-way tie). Or omit the
  margin and let the Tie badge carry it.

## Nits

### N1. The week list grows and shrinks with the live cache (READ)
- `lastWeek` includes `liveWeek` (`WeeklyPotService.java:134-136`), and `liveWeek` comes from `peek`.
- On a cold or expired cache, week 8 (live, not yet stored) is absent. On a warm one it's listed as "Not loaded
  yet", directly under a live line that says "Leading week 8".
- Two loads a minute apart show a different number of week rows.
- **Fix:** don't extend the walk past `latestStored` from the live read; the live line already covers that week.
  Or give a MISSING week equal to `liveLeader.week` the status words "In progress".

### N2. "The shortcut uses fewer payments" (READ)
- `WeeklyPot.tsx:241`. The guarantee is "no more than" (plan P3/A1). In the IT's own NFL fixture the pairwise view
  has 4 debts and the shortcut 2, but a 2-roster league has 1 and 1.
- "uses no more payments, usually fewer" matches the spec's wording.

### N3. Ranks ignore ties (READ)
- `WeeklyPot.tsx:160` prints `i + 1`. Two managers at +$3.00 with the same weeks won get ranks 4 and 5, ordered by
  roster id. That's lessons #28's "rank-based display can manufacture certainty".
- **Fix:** use competition ranking ("4, 4, 6") off `netCents` alone.

### N4. "Every pair" opens every group by default (READ)
- `WeeklyPot.tsx:269` sets `<details … open>`. A15 asked for "grouped by payer, collapsible" because of the
  up-to-91-row case on a phone. Every group open is the long scroll A15 wanted to avoid.
- **Fix:** collapse groups by default on narrow viewports, or open only the viewer's own group (`isMe`).

### N5. A roster missing from `roster_season` is labelled "had no manager this season" (READ)
- `WeeklyPotService.java:197-201`: `noManager = managerId == null`. That's also true for a roster id that has
  score rows but no `roster_season` row (a data gap, not an orphan), and the page tells the reader something false.
  Not observed locally: VERIFIED that every stored league-week has exactly `roster_season`'s count of rows.
- **Fix:** set `noManager` only when a standing row exists with a null `managerId`.

### N6. Weak assertion in `WeeklyPotLedgerTest.aMultiWeekSeasonClearsBothWaysAndTheShortcutIsNoLonger` (READ)
- Line 157 is `shortcut < pairwise || pairwise <= 5`, which is close to always true. The real guarantee is checked
  by `assertClears` (`<=`) and by the 300-season property loop, so nothing is uncovered.
- **Fix:** assert the exact sizes for this literal fixture.

## Checked, no defect

- **Money arithmetic (`WeeklyPotLedger`):**
  - Zero-sum is exact. Every payer adds exactly `stakeCents` to `paid`, and the k shares (`base + 1` for the first
    `stake % k`) sum to `stakeCents`.
  - Co-winners neither pay nor owe each other. Winners are compared with `compareTo` (150.1 == 150.10).
  - Negatives are payers. `potCents = payers × stake` (long arithmetic).
  - The pairwise view nets each unordered pair. Each roster's net equals the sum of its incident pairwise debts, so
    each connected component sums to zero and greedy terminates within it.
  - Each greedy step zeroes at least one side, so a (debtor, creditor) pair can't repeat. That means the React key
    `${from}-${to}` in the shortcut list is unique.
  - Union-find: `parent.get(root) != root` unboxes (Integer vs int), so no identity-comparison bug.
- **Ordering tests (lesson #1):** `theHigherScorerWinsNotTheLowerOne` swaps two scores and asserts that the winner
  swaps, so a sign inversion would fail. The 3-way tie asserts the extra cent goes to the *lowest* roster id at 100¢
  (34/33/33) and 200¢ (67/67/66).
- **Real data:** VERIFIED on 5433:
  - no top-score ties in any regular-season week;
  - no all-zero or `max ≤ 0` weeks;
  - no league-week where the row count differs from `roster_season`;
  - no null `starters_points`.

  So the payer set is "every roster" on real data.
- **Regular-season bound and finality:**
  - Finality: `allScores(…, regularSeasonBound(row))` plus `ScoredWeeks.isFinal`, the same two rules Expected wins
    uses. The walk is capped at `min(boundWeek, …)`.
  - The IT's week-4 playoff row (roster 4 scores 999) is excluded. If the bound were dropped, `weeks.size()` would
    be 4 and roster 4's net would change, so the IT can catch it.
  - `seasonFinal` and `throughWeek` are consistent: `seasonFinal ⇒ throughWeek == boundWeek`.
- **JSON contract vs `api.ts`:** I diffed field for field:
  - `On` (17 fields) and `Off` (6 fields);
  - `Week` (7), `Balance` (10), `Debt` (3) and `LiveLeader` (3).

  All names match, including `isMe`/`noManager`. The IT asserts `isMe` and `noManager` by key. The union is
  discriminated on `enabled`. Explicit nulls are VERIFIED by the IT: `liveLeader`, `regularSeasonThroughWeek`,
  `managerId`, `avatarId`, `winningPoints` and `runnerUpPoints` are present as `null`. The off body has no
  `balances/weeks/pairwise/shortcut/liveLeader/tieCentRule` keys. Sport serializes as `"nfl"`/`"nba"` (`@JsonValue`).
- **`Map.of`:** none on a response path. The only `Map.of()` is the empty-default inside `pairwise()`. The 400/403
  bodies are `LinkedHashMap`.
- **JDBC (bug class 3):** `upsert` binds a null `Long updatedBy`. VERIFIED by `aNullUpdatedByBindsAndStoresAsNull`
  against real Postgres. `updated_by` equals the commissioner's manager id after a PUT (VERIFIED in the IT).
- **PUT validation:** the order is 404 → 403 → 400, and nothing is written before validation passes. VERIFIED by
  the IT:
  - missing `enabled` or `stakeCents` is 400;
  - `0`, `-5`, `100001`, `150.7`, `100.0`, `"100"`, `"yes"` and `99999999999` are 400;
  - an empty body is 400;
  - the stake is unchanged afterwards.

  By reading: `1e2` (DoubleNode), a JSON `null`, an array or a string body all reach `bad()`. The V41 `check`
  mirrors the 1..100000 bound.
- **403 / 404 / 422:**
  - VERIFIED: no identity, a stranger or an unknown league gets 404 on GET and PUT.
  - VERIFIED: a non-commissioner gets 403 with `commissionerKnown: true` and the message. With no commissioner
    recorded, the message is the "No commissioner is recorded" text.
  - VERIFIED: an ESPN league gets 422 `PLATFORM_NOT_SUPPORTED` with the FIXED text on both `/weekly-pot` and
    `/weekly-pot/settings`. `pageKeyOf` matches the first segment.
  - The front-end gate key `weeklyPot` equals the backend key.
- **`WeeklyReportService` refactor:** it's behaviour-identical. The expression is the same, and the player-games
  read is still lazy behind `||` via `BooleanSupplier`. VERIFIED: `WeeklyReportServiceLiveWeekTest` (17) and
  `WeeklyReportServiceTest` (12) pass.
- **Live read:** it uses `peek` (it never blocks), and only `FAILED` sets `liveUnavailable`. The line is suppressed
  in these cases, each covered by a unit test:
  - the week is beyond the bound;
  - the week is already final;
  - there are no rows;
  - the cache is cold.
- **Naming and `isMe`:** via `RosterOwners.ownerNames` + `ManagerRepository.caller`, never username. The web test
  asserts that the highlight comes from `isMe` only.
- **CORS (class 6):** `WebConfig` allows `PUT` on `/api/**`.
- **Migration:** V41 is additive, and it's the next number after V40 on main. It was already applied to the shared
  5433 DB (`flyway_schema_history` installed 2026-10-26 00:01) by an earlier IT run. That's harmless: other branches
  treat it as `future`. Nothing destructive happens (lessons #22).
- **Frontend state:**
  - `KeyedByLeague` remounts the page per league id, and the effect also clears `data`/error, so there's no stale
    league data on a switch.
  - `CommissionerPotPanel` is keyed `${enabled}-${stakeCents}`, so it resets to server truth after a save that
    changes either value.
  - Dollars→cents uses a 2-decimal regex plus `Math.round`. VERIFIED by vitest: `0.29`→29, `1.5`→150, `1.234`
    rejected, `1000.01` rejected.
  - The off state renders no `$` for a member (asserted on `textContent`).
- **Phone layout (READ):**
  - At ≤600px the standings drop to rank / name (+ wins inline) / net.
  - The week grid's explicit `grid-column`/`grid-row` placements resolve without overlap. Muted rows inherit the
    3-column template harmlessly.
  - The table is inside an `overflow-x: auto` wrapper, and long names wrap (`overflow-wrap: anywhere`).
  - Every CSS variable used (`--up`, `--down`, `--teal`, `--crimson`, `--volt`, `--fitted`, `--row-hover`) is
    defined. Tints use `in oklab` (lessons #51).
- **Test runs (VERIFIED 2026-10-26, Postgres 5433), all 0 skipped:**
  - `WeeklyPotControllerIT` 11, `WeeklyPotLedgerTest` 9, `WeeklyPotServiceTest` 14.
  - `LeagueAvailabilityIT` 10, `EspnPlatformGuardIT` 4.
  - The `WeeklyReport*` suites: 14 + 3 + 17 + 12.
  - vitest: `WeeklyPot.test.tsx`, `destinations.test.ts` and `LeagueRailSection.test.tsx` give 111 passed.
  - `npx tsc -b` is clean.
  - Not run: the full backend suite or `npm run build` (T022 is still open in tasks.md).

## Fix pass (2026-10-26)

- **B1: FIXED.** `liveLeader` keeps the `hasStarted` gate and now also returns null when the top live score is <= 0. `storedPlayerGamesAloneCountAsStarted` flipped to assert no leader (renamed); added negative-roster-with-rest-at-0 (no leader) and one-roster-at-0.5 (leads alone) cases.
- **S1: FIXED.** `latestDecidedWeek` added to the On record, JSON contract (amended note), and `api.ts`. Header reads "Final." / "Includes weeks through N; week(s) ... not settled yet." / "Through week N." / "No week is decided yet."; the settle-up heading shows "(as of week N)" whenever not final and N > 0. Service test (gap case) and vitest (gap case) added.
- **S2: DEFERRED.** `start_week` isn't stored by ingest; every local league starts week 1; unmeasured what Sleeper returns before `start_week`.
- **S3: FIXED.** Tie weeks show "tied"; otherwise "+X over 2nd".
- **N1: FIXED.** The week walk uses `snapshot.latestStored()` only (capped by the regular-season bound); the live week appears only in the live line. Added `theLiveCacheNeverChangesTheWeekList`.
- **N2: FIXED.** Copy now "The shortcut never needs more payments".
- **N3: FIXED.** Standings use competition ranking off `netCents` (1, 2, 2, 4).
- **N4: FIXED.** "Every pair" groups render open only when there are 4 or fewer payer groups.
- **N5: DEFERRED.** Cosmetic.
- **N6: FIXED.** Replaced the `||` assertion with exact sizes measured for the literal fixture: pairwise 11, shortcut 4.

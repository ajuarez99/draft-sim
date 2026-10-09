# Code review: spec 023, player stats in the draft room

Bug-hunting review of `git diff 022-player-stat-analysis` plus the untracked files, in the
`draft-sim-023` worktree (branch `023-draft-room-player-stats`), 2026-10-09. I changed no source
file, made no commit and didn't run gradle.

**What I ran:**
- The five spec-023 frontend test files (`AvailabilityPanel`, `StatPickerModal`,
  `draftRoomStats`, `statChoice`, `statLeaderboard`): **100/100 passed**.
- Two throwaway component tests in the session scratchpad, run against this worktree's real
  `AvailabilityPanel` through a scratch vitest config. They're not committed. Findings marked
  **verified (ran)** below come from these.

The rest was found by reading. I didn't re-report anything `verification.md` already records as
fixed. The fixes it describes (the pool source, the phone sheet CSS, the 1-second re-render, the
stale toggle closure) look correct as written.

**Severity counts: 0 blockers, 8 should-fix, 10 nits.**

---

## Should-fix

### S1. A window switch during a slow first fetch leaves Stats on "Loading stats…" for good. **Verified (ran).**
`web/src/components/AvailabilityPanel.tsx:306-314`

- **Scenario:** Open Stats. The SEASON request is in flight, and the first call was measured at
  1.24 s cold. Click **Last 10**. The effect sets `askedKey = 'L1|LAST_10'` and fires a second
  request. LAST_10 resolves first, then SEASON resolves and runs
  `setStats({ key: 'L1|SEASON', … })`, which overwrites the LAST_10 board.
- **Result:** `statsNow` is null for the current key, so the view shows "Loading stats…". Because
  `askedKey` already equals the current key, it never asks again.
- **Measured** with a deferred SEASON promise: `LOADING SHOWN AFTER RACE: true, TABLE: false,
  calls 2`.
- The only way out is to switch to another window and back.
- **Fix:** Drop a stale response, for example
  `then(board => setStats(s => askedKey.current === statsKey ? { key: statsKey, board } : s))`.
  Better, keep a `Map<key, board>` cache, so switching back to Season doesn't refetch 327 KB gzipped.

### S2. The tier list's empty-state message renders under the Stats table. **Verified (ran).**
`AvailabilityPanel.tsx:611-619`

`{rows.length === 0 && …}` isn't gated on `!inStats`, and `rows` is the tier list's rows, which
are filtered to survival above 1% and capped at 60. So whenever the tier list is empty, Stats
shows a sentence that contradicts the populated table under it:

- **In the live room before the first projection**, `availability` is `NO_AVAILABILITY` (empty),
  so `rows` is empty. With `started={result != null}` false, the message reads "Your realistic
  options show up here once the draft starts." under 400 stats rows.
- **With a position filter that leaves no tier rows above 1%**, it reads "No players survive to
  these picks in any run."
- **Measured:** with `availability={[]}` and a one-player `statsPool`, the table renders and the
  text "No players survive to these picks in any run." is present.

**Fix:** Gate it with `!inStats &&`, and give the Stats table its own empty state (see N6).

### S3. Before the first projection, the live room says "You have no picks left." **Verified (ran) at component level.**
`AvailabilityPanel.tsx:355-358`, fed by `LiveDraftView.tsx:346-349`

- **Scenario:** `upcomingMyPicks` is `result ? … : []`. With the seat known and no projection
  yet, `showSurvival` is true (`availability` is `[]`, not undefined) and `myPicks.length === 0`.
  So the "Likely there" toggle is disabled with the reason "You have no picks left."
- **Result:** This is false. The member has every pick left. It's a wrong statement about the
  user, which runs against the project's honesty rule.
- **Measured** with `myPicks={[]}` and `availability={[]}`: the text is present.
- **Fix:**
  - Pass `availability={result?.availability}`, which is undefined until there is a projection.
    That needs care, because it changes `showSurvival` for the tier list too.
  - Or add a reason for the projection-pending state ("Appears once the projection is ready."),
    distinct from no picks left.
- The tier list's "No picks left" depth label (line 415) has the same pre-projection wording, and
  that one predates 023.

### S4. While `/pool` loads, the live Stats view silently shows a different universe. **Verified (ran).**
`AvailabilityPanel.tsx:327` and `LiveDraftView.tsx:599-620`

- **Scenario:** `statsPool` stays `undefined` until `getDraftPool` resolves, and failure is a
  separate flag. Meanwhile the panel falls back to `players ?? availability.map(r => r.player)`.
  That is exactly the "simulation-surfaced subset" that verification finding 1 says is not the
  undrafted pool.
- **Result:**
  - Before a projection, the table is empty with only its header. **Measured: 0 rows and no
    loading text.**
  - After a projection, it shows a few dozen players and then jumps to around 400.
  - Either way there's no "loading" signal, and the count is wrong for a moment.
- **Fix:** Tell the panel the pool is pending: pass `statsPool={statsPool}` with `null` meaning
  loading, or add a `statsPoolLoading` prop. Render "Loading players…" instead of falling back to
  `availability` when the room is one that supplies a pool. Keep the `players` fallback for mocks.

### S5. The mock room loses sort, window, mode, filters and the "likely" toggle every turn, and refetches the leaderboard. Found by reading.
`MockDraftView.tsx:180` (`{state.isUsersTurn && !complete && <AvailabilityPanel …/>}`) and
`AvailabilityPanel.tsx:285-305`

- **Scenario:** The panel unmounts between your turns. Only `view` (sessionStorage) and the
  columns (localStorage) survive. These are component state or refs, so they reset to their
  defaults on every remount: `statSort`, `statWindow`, `statMode`, `filter`, `likelyOnly` and
  `askedKey`.
- **Result:**
  - FR-004 says "the member's sort, filters and chosen stats MUST survive each update", and
    FR-018 says mocks get "the same stats view". Sort by USG, make a pick, and on your next turn
    it's back to FP/G, Season, Per game, ALL.
  - Each turn also refetches the full leaderboard (1.46 MB raw, 327 KB gzipped, measured in
    verification). That contradicts the plan's "fetched once per window, never per pick" for mocks.
  - The US3 check only confirmed that "Stats was still selected".
- **Fix (either):**
  - Keep the sheet mounted in mocks and hide it when it isn't your turn.
  - Or lift the stats view state and the fetched board into MockDraftView, or into a small
    module-level or sessionStorage cache keyed by league.

### S6. On the pre-draft simulation page (`/drafts/:id`, DraftView), an NBA draft shows a disabled Stats with a false reason. Found by reading, and confirmed by the existing test's copy.
`web/src/pages/DraftView.tsx:585-593` and `AvailabilityPanel.tsx:281, 475-480`

- **Scenario:** DraftView renders `AvailabilityPanel` with `sport="nba"` and no `sleeperLeagueId`.
  `statsOffered` depends only on `sport === 'nba'`, so the switch appears. `sleeperLeagueId` is
  `undefined`, so it says "Stats aren't available on this server yet."
- **Result:** On a current backend, that sentence is false. The server has the field, and
  DraftView just doesn't pass it. The project's honesty rule makes a wrong reason worse than no
  control.
- **Fix:** Pick one:
  - Make the switch opt-in, with a prop such as `statsView`, or by offering it only when
    `sleeperLeagueId !== undefined || statsPool` is given.
  - Or wire DraftView properly: `seats.sleeperLeagueId` plus its own `/pool` fetch, as in
    LiveDraftView. Passing only the league id would bring back the wrong-universe bug.

### S7. On a phone, the jump-to FAB sits on top of the collapsed "▾ Players" pill. Suspected (from the CSS geometry, not measured).
`styles.css:1537-1540` (new) against `styles.css:1120-1123` and `3712-3714`

- **Geometry at ≤700 px:**
  - The collapsed sheet is `position: fixed; bottom: 12px; right: 8px; left: auto` at z 43. The
    collapsed rule's `left: auto` wins on specificity (0,2,0 over 0,1,0). That makes it a pill
    about 90×30 px, spanning right 8–~100 px and bottom 12–~42 px.
  - `.jumpto-fab` (shown at ≤860 px, on every page through AppShell) is `fixed; right: 16px;
    bottom: 16px; 44×44; z-index: 45`.
- **Result:** The FAB covers most of the pill's toggle button. A tap there opens the jump-to
  palette instead of the list. Only the pill's left ~40 px still reaches the button.
  - In DraftView the sheet starts collapsed, so that's the first state a phone user sees.
  - The open sheet also loses its bottom-right corner (the last row's right cells and the
    horizontal scrollbar's end) under the FAB. The pick card already accepts that.
- **Before 023**, the sheet sat inside a 0-px stage and was broken anyway, so this is a new
  failure rather than a regression of something that worked.
- **Fix:**
  - At ≤700 px, give the collapsed pill `right: 72px` (clear of the FAB's 16 + 44 px plus a gap),
    or put it bottom-left.
  - And/or give the open sheet `bottom: 72px`, or reserve FAB space with `padding-bottom`.
  - Measure with `elementFromPoint` on the pill's centre at 375 px.

### S8. `StatRow`'s `memo` never applies on a pick, so every row re-renders. **Verified (ran).**
`AvailabilityPanel.tsx:319-336`, `draftRoomStats.ts:215-224`, `DraftStatsTable.tsx:59-60`

- **Scenario:** `joinPoolStats` builds a fresh `DraftStatRow` object for every player whenever
  `statRows` recomputes. That happens on every landed pick (`pickedPlayerIds`), on every
  resimulation (`availability`), and on every `openSlots` change. `StatRow` compares `r` by
  reference, so it misses every time.
- **Measured:** 10 players by 12 default columns, then one unrelated pick. **108 `Cell` renders**,
  which is every remaining cell.
- **Result:** The live room's 400 × 12 table is about 4,800 cells re-rendered per pick, plus once
  more when the resimulation lands. The 1-second idle fix (SC-006) still holds. But the comment
  at `DraftStatsTable.tsx:59` ("re-renders only the rows whose inputs changed") is false, and
  verification lists the per-pick cost as "not separately measured".
- **Fix:**
  - Reuse row objects across recomputes: keep a `useRef<Map<playerId, DraftStatRow>>` and return
    the previous object when `stats`, `survivalNext` and `fillsSlot` are unchanged.
  - Or give `StatRow` a custom `areEqual` that compares those fields.
  - Then measure the per-pick long-task cost, or correct the comment.

---

## Nits

- **N1. Zero-weight keys mark scoring as "changed". Suspected.**
  `PlayerStatsService.java:454-455`.
  - `Map.equals` treats a key present with weight `0` as different from the key being absent. But
    `GameScoringService.contributions` iterates the scoring keys, so an absent key and a 0 key
    score identically.
  - If Sleeper adds a new zero-weighted stat key to the 2026 settings, which it does across
    seasons, `scoringMatchesRequested` is `false`. The room then wrongly says "This league's
    scoring has changed since then."
  - Representation is safe: `scoringOf` normalises through `Number.doubleValue()`, so `1` and
    `1.0` compare equal, and `LinkedHashMap.equals` ignores order.
  - **Fix:** Compare after dropping entries whose value is `0.0`. A test for "differs only by a
    zero key gives true" would pin it.

- **N2. An empty scoring map compares as "changed". Suspected.** Same lines. A requested league
  whose `scoring_json` is `{}` (or not yet ingested) gets `scoringOf` = `{}`, which is not equal
  to a real map, so the flag reads `false`. That isn't "changed", it's "unknown". **Fix:** Return
  `null` when either map is empty. The same block also issues a third `scoringOf` query, but
  `scoring` from line 381 is already in hand.

- **N3. Wrong copy in the live room when the league is missing.** `AvailabilityPanel.tsx:477-479`.
  A live room whose league row is missing gets `sleeperLeagueId: null`, which shows the mock's
  sentence: "start the mock from a league…". That's rare, but the wording is wrong for a live room.

- **N4. The name sort uses a different name than the one displayed.** `draftRoomStats.ts:233-235`.
  The sort uses the leaderboard's `stats.name`, while the row shows the pool's `p.name`. When
  Sleeper's two spellings differ (diacritics, "Jr."), the order looks off by one place.

- **N5. The depth chips stay visible in Stats.** `AvailabilityPanel.tsx:412-433`. The "Next 1…6"
  chips still show in Stats view (`showSurvival`) but do nothing there, because Stats uses only
  the next pick. Hide them when `inStats`.

- **N6. An empty Stats table shows no message.** When the position filter plus "likely only"
  leaves zero rows, Stats shows a bare header. Add "No players match." alongside the S2 fix.

- **N7. A failed load is never retried.**
  - The `statsPoolFailed` state in `LiveDraftView.tsx:603-620` never retries.
  - A failed leaderboard fetch for a key also never retries, because `askedKey` stays set. Only
    leaving and coming back to the window or room recovers.
  - The pool is also fetched for every NBA live room even if Stats is never opened (one extra
    400-row request). That's acceptable, but worth a comment.

- **N8. The table header scrolls away in Stats.** `styles.css`, `.avail-scroll .ds-table thead th
  { position: static; }`. The header row scrolls out of view over 400 rows, so a member sorted by
  column 9 loses which column is which. This is a deliberate trade-off, recorded here only as a
  usability cost.

- **N9. Modal accessibility: focus is lost at the list's edges.** `StatPickerModal.tsx:84-85`.
  Pressing "Move X up" until X reaches the top disables the focused button. Chrome then drops
  focus to `<body>`, and the keyboard user is thrown out of the dialog, which has no focus trap
  (a known gap). Use `aria-disabled` plus a no-op instead of `disabled`, or move focus to the
  sibling button. Focus restore on close is correct: `opener` is captured on mount and the
  effect has `[]` deps.

- **N10. Escape may also close something underneath. Suspected.** `StatPickerModal.tsx:26-32`.
  The modal listens on `document` and never stops propagation. Other components bind Escape on
  `window`, PlayerCard and PlayerPicker among them. Nothing else should be open under this modal
  today, so this is latent. Separately, `onClose` is a new arrow on every panel render, so the
  listener is re-attached once a second in the live room. That's harmless.

---

## Checked and found clean

1. **Join and identity.** Leaderboard rows are joined on `PlayerRef.sleeperId` against
   `LeaderboardRow.sleeperPlayerId` (`draftRoomStats.ts:214-216`). Survival and picked status
   are keyed by `PlayerRef.id`, which comes from the same `SimulationResult.PlayerRef` in `/pool`
   and in `availability`. React keys are `player.id`. I found no path that duplicates or drops a
   player.
   - `/pool` doesn't filter drafted players (`LeagueController.java:100-120`), so a player
     whose pick is undone reappears (FR-004).
   - The backend builds a row for every player with at least one season game, whatever the
     window, so "No NBA games in {season}" is correct for Last 10 and Last 5 too.
2. **Refetch and reset on a pick (live room).** No refetch: the effect deps are
   window and league only. Sort, columns, window, mode and the "likely" toggle live in panel state
   that a pick doesn't touch. Every prop of `DraftStatsTable` is stable across the room's clock
   re-render:
   - `statRows` deps: `takenPlayerIds`, `myOpenSlots` and `result.availability` are all memoised
     in LiveDraftView;
   - `nextMyPick` is a primitive;
   - the callbacks are `useCallback`'d or `setState`;
   - `nextPickLabel` and `likelyUnavailableReason` are strings.

   The exceptions are S5 (mocks) and S8 (per-pick row memo).
3. **Stale closures.** `chooseStats` uses a ref, the move and toggle updaters are by id,
   `onStatSort` uses a functional update, and window, mode and "likely" are plain setters. Reset
   goes through `chooseStats`. I found no stale closure apart from the S1 response race.
4. **Nullable fields.** An `undefined` vs `null` `sleeperLeagueId` gives different copy as
   designed (N3 aside). `scoringSeason` and `scoringMatchesRequested` from an older backend are
   treated as unknown (`== null` / `=== false`). `board.rows` is never read when
   `available=false`, and `statsBoard` gates it.
5. **Formatting outside `Cell` (FR-002).** Every stat value goes through `statCells.Cell`, moved
   verbatim, with the same `td` classes as `StatLeaderboard.tsx:469`. The only numbers formatted
   outside it are the name sub-line's `ADP n` and `n% there at your next pick`
   (`DraftStatsTable.tsx:74-75`). Those aren't leaderboard values, and they match the tier list's
   own rounding.
6. **Sorting.** Rows with no stats sort last in both directions (`sortDraftRows`). Null cells
   sort last in both directions, and ties break by games desc, then name, then id, via the
   leaderboard's own `compareRows`. Removing the sorted column falls back to FP/G, then name
   (`sortAfterColumns`).
7. **Backend and wire.**
   - `seats` puts a nullable `sleeperLeagueId` into a `LinkedHashMap` (no `Map.of`).
   - `StatLeaderboard`'s two new components are mirrored in `api.ts` as optional nullable
     fields, and `SeatsResponse.sleeperLeagueId?` likewise.
   - `unavailableBoard` passes nulls.
   - The no-fallback, match and differ cases are tested.

   The equality caveats are N1 and N2.
8. **CSS (apart from S7).**
   - Only the page-level stacking matters here: the ≤700 px rule moves `.avail-sheet` to fixed
     layer 43. That's below the pick card (44) and the FAB (45), and above nothing else fixed in
     the room.
   - The breakpoint matches the one where `.app` becomes `height: auto` (the stage collapse,
     `styles.css:2177/2259`), so 701–860 px still uses the absolute sheet as before.
   - The collapsed rule still wins `left: auto` on specificity.
   - DraftView and MockDraftView pick the rule up as well. That's the intended fix there too,
     with S7 the side effect. CompletedDraftBoard doesn't render the panel.
9. **Blocked storage.** Every `sessionStorage` and `localStorage` access, including the bare
   global reference, is inside `try`. A mock with no league and a remembered `stats` view falls
   back to Tiers.
10. **Modal accessibility.** Focus moves in, focus is restored, `role="dialog"`, `aria-modal`,
    the close and move buttons are labelled, and the checkboxes are wrapped in labels. The issues
    are N9 and N10.

---

## Fixes applied (2026-10-09)

- **S1 (late response overwrites):** new `web/src/draftStatsCache.ts`, a module-level
  `Map<"league|window", Promise<StatLeaderboard>>`. A rejected promise is evicted so a later open
  retries. The panel subscribes to the current key's promise only and drops a resolution once its
  key is no longer current. Tests: window switched while the first fetch is pending shows the
  second window even when the first resolves later; remount makes no second call; a rejected fetch
  is retried on a later open.
- **S5 (state across mock turns):** the same cache means a remount reuses the board (no refetch).
  Sort, window, mode, position filter and likely-only persist in sessionStorage under
  `bk.availStatsState.v1` (versioned JSON, try/catch, invalid falls back to defaults; a stored
  position not in the sport's list falls back to ALL). Test: unmount/remount keeps all five, and a
  garbage value gives defaults.
- **S2:** the tier list's empty-state message renders only when not in Stats. Also added N6: the
  Stats table says "No players match." when filters leave zero rows.
- **S3:** with a seat known but no projection (`started` false), the likely-filter reason is
  "Survival appears once the projection is ready."; "You have no picks left." stays for a started
  room whose picks are exhausted. The tier list's older "No picks left" depth label is untouched
  (it predates 023).
- **S4:** new `statsPoolLoading` prop. While the live room's pool is in flight the Stats view shows
  "Loading the player list…" and no table; the simulation's subset is no longer a fallback there.
  Mocks still use `players`.
- **S6:** DraftView (pre-draft simulation page) passes `sleeperLeagueId={seats.sleeperLeagueId}` and
  the 400-player pool. The fetch is extracted to `web/src/useStatsPool.ts` (`{pool, loading,
  error}`, NBA only), now used by both LiveDraftView and DraftView. Football is unchanged.
- **S7:** at 700px and under the open sheet's `bottom` is 76px (the FAB's 44px height plus 2 x its
  16px offset), and the collapsed pill is anchored bottom-left (`left: 8px; right: auto`). Not
  measured with `elementFromPoint` at 375px; arithmetic from the CSS only.
- **S8:** `StatRow` has a custom equality (player and leaderboard row by reference; reason,
  survivalNext, fillsSlot and the other props by value), and the misleading comment is corrected.
  Measured with a new test: a pick that removes one unrelated player re-renders **0** `Cell`s of
  the surviving rows (the review measured 108 before).
- **N1 + N2:** `PlayerStatsService.sameScoring(a, b)` drops entries weighted 0.0 before comparing
  and returns null when either map is empty; it also reuses the `scoring` map already in hand
  instead of a third query. Unit tests in `PlayerStatsServiceTest` (3) and a new IT case
  (zero-weight extra key gives TRUE). contracts/api.md C2 carries an "amended after code review" note.
- **N5:** the "Next 1..6" depth chips are hidden in the Stats view.
- Not touched: N3, N4, N7, N8, N9, N10.

**Run after the fixes:** `tsc -b` clean; vitest 1305/1305 passed (94 files); gradle
`PlayerStatsLeaderboardReadIT` 10 run / 0 skipped, `PlayerStatsServiceTest` 23 / 0 skipped,
`LeagueControllerSeats*` 4 / 0 skipped, no failures. Not live-verified in a browser.

# Code review — A. Auto-detect which slot is you

Bug-hunting review (not a style pass) of the developer stage's diff on
`feature/auto-detect-slot`, done cold against `claude/plan-review-A.md`'s
amendments and `AGENTS.md`'s recurring bug classes, per this repo's
plan → review → build → code-review → live-verification pipeline. Reviewed
commit `47586bb` ("Auto-detect which draft slot is you") on top of
`0041ba4` (plan-review stage), against `main`.

## Verdict: solid implementation, one small hardening fix applied, no
correctness bugs found in the shipped behavior

All five of plan-review-A.md's required/recommended amendments were actually
built, not just claimed:

1. **`Map.of(...)` NPE (blocking)** — `LeagueController.seats()` now builds
   its whole top-level response as a mutable `LinkedHashMap`, mirroring
   `board()`'s existing fix in the same file, with an explicit comment naming
   why. Confirmed no leftover `Map.of(...)` call site anywhere in the null-value
   path; `board()`'s own unrelated `Map.of(...)` at the very end of that method
   only wraps non-nullable values and was correctly left alone.
2. **Auto-detect effect placement** — a new `useLayoutEffect` keyed on
   `[seats]`, not bolted onto the stale mount-only effect the original plan
   mis-cited.
3. **Flash guarantee** — the developer picked the harder of the two options
   plan-review offered (hard zero-flash, not "brief flash acceptable"): the
   slot input is gated on a new `slotKnown = slotParam != null || seats != null`
   and rendered blank/disabled until then, and `useLayoutEffect` (not
   `useEffect`) is used specifically so the post-seats-arrival `setMySlot` →
   `setSearchParams` commit happens before the browser paints, not after.
4. **Forward-keyed `ManagerRepository` method skipped** — confirmed no new
   method was added; the existing reverse-keyed `idsBySleeperUserId()` is
   looked up once per request (not once per seat — no N+1), compared against
   each seat's already-known `managerId` while iterating `slotToManager`.
5. **Blank-config guard** — `OwnerProperties.configured()` explicitly checks
   `!isBlank()` before any lookup; `ownerManagerId` is `null` (not a
   `.get("")` miss) whenever `APP_OWNER_SLEEPER_USER_ID` is unset.
6. **Stale `.start-overlay-cta` copy updated** — now conditionally reads
   "We found your seat — you're slot N" vs. the original "Set your slot
   above..." text, keyed off `seats?.mySlot != null`.
7. **Fourth IT test added** — `LeagueControllerSeatsUnsetOwnerIT` forces real
   Jackson serialization of the unset-config response and asserts
   `"mySlot":null` appears in the JSON, which is the actual regression test
   for risk #1 (a vacuous "field is logically null" assertion would not have
   caught the original `Map.of` bug, since it throws before that point is
   ever reached — this test's own docstring calls that out correctly).

### Specific things checked against the real code, per the review brief

- **`mySlot` resolution when a manager isn't in `idsBySleeperUserId()`'s
  reverse map**: not reachable as a failure mode. The implementation does
  exactly one reverse lookup — the *configured owner's* sleeper id →
  managerId — never a per-seat forward lookup. A seat manager missing a
  `sleeper_user_id` (impossible today anyway, the column is `not null` in
  `V1__init.sql`) is irrelevant to this code path; it's never touched. If the
  owner's id isn't in the map, `ownerManagerId` is simply `null` and the
  `forEach` loop's `ownerManagerId != null && ownerManagerId == id` guard
  short-circuits for every seat — `mySlotHolder[0]` stays `null`, degrading
  cleanly exactly as spec'd. No NPE possible. Verified live via
  `LeagueControllerSeatsOwnerConfiguredIT.configuredOwnerAbsentFromLeagueYieldsNullMySlot`
  (owner exists as a manager, but not in the specific draft under test).
- **`useLayoutEffect` re-adoption / loop risk**: traced the full render
  sequence. The effect's dependency array is `[seats]` only (not
  `slotParam`), and it returns immediately once `slotParam != null` — after
  the first successful auto-adopt, `setSearchParams` makes `slotParam`
  non-null, but that alone doesn't re-run the effect (wrong dependency), and
  the effect wouldn't re-fire anyway unless `seats`'s object identity changes
  (a fresh `refetchSeats()` call, e.g. `draftId` change or a seat edit via
  `handleSeatsChanged()`). No loop.
- **Interaction with feature C's `.start-overlay`/`!started` state**: the
  slot `<input>` lives in the top-level `.controls` bar, which renders
  unconditionally regardless of `started`/`seats` — it is never unmounted
  during the full-screen empty-board state, so the effect never reaches for a
  ref or DOM node that isn't there. No crash risk; confirmed by reading the
  render tree, not assumed.
- **Zero-flash trace**: `BrowserRouter` (classic, not the data router) is
  used in `main.tsx`, so `useSearchParams`'s underlying history update is a
  synchronous React state update, not deferred via `startTransition` the way
  a data-router navigation might be — this is what makes the
  "`useLayoutEffect` closes the gap" reasoning in the code's own comment
  actually hold. Sequence: (1) before `seats` loads, `slotKnown` is `false`,
  input renders blank/disabled — no `1` painted. (2) The instant `seats`
  commits, `slotKnown` flips `true` in the *same* render as `mySlot` still
  reading `DEFAULT_SLOT` — this frame is real but is a layout-effect-phase
  commit, not yet flushed to the screen. (3) The `useLayoutEffect` fires
  synchronously in that same pre-paint phase and calls `setMySlot` →
  `setSearchParams`, which (per (1)) is itself synchronous, producing a
  second commit before the browser ever paints. Net result: the browser only
  ever paints the corrected value. This matches AC1's "hard zero-flash"
  reading, not just "no flash once loaded."
- **IT test vacuity check**: both new tests build a *real* draft row with a
  real `slot_to_manager` jsonb blob and call `controller.seats(...)` through
  the real Spring-wired `ManagerRepository`/`OwnerProperties`, not mocks —
  `LeagueControllerSeatsOwnerConfiguredIT` asserts the actual matched slot
  (7) and the actual no-match-in-this-league null, and
  `LeagueControllerSeatsUnsetOwnerIT` forces real JSON serialization
  (`objectMapper.writeValueAsString`) rather than only inspecting the Java
  map. None of the three assertions could pass by accident against the
  pre-fix `Map.of(...)` code — that version throws before any assertion runs.
- **`api.ts` mirrors the Java record field-for-field**: `SeatsResponse.mySlot:
  number | null` correctly mirrors `Integer` semantics (`null` when
  unconfigured/no-match, a number when matched) — Jackson serializes a boxed
  `null` `Integer` as JSON `null`, which the new TS field already expects.
  No absent-vs-null distinction is needed here since the field is always
  present in the response (confirmed by the IT test's
  `map.containsKey("mySlot")` assertion), so there's no `?:` vs `| null`
  mismatch to get wrong.

## One fix applied

**Test determinism hardening (small, applied):**
`LeagueControllerSeatsUnsetOwnerIT` (the direct regression test for the
`Map.of` NPE) relied on `application.yml`'s default
(`${APP_OWNER_SLEEPER_USER_ID:}`, blank) to guarantee the "unconfigured"
state under test. Nothing in the backend actually loads a `.env` file (no
dotenv dependency exists in `backend/`), so this is a real, if narrow,
determinism gap: a developer who has exported `APP_OWNER_SLEEPER_USER_ID` in
their own shell (to actually use the feature day-to-day, which is exactly
what this config value is *for*) would silently flip this specific test into
the "configured" state it isn't meant to cover — either failing for a
confusing reason, or worse, happening to match a manager and passing for the
wrong reason, with zero code change to explain why. Fixed by adding
`@TestPropertySource(properties = "draftsim.owner.sleeper-user-id=")` to pin
the property to blank regardless of the ambient shell environment, matching
how `LeagueControllerSeatsOwnerConfiguredIT` already pins its own value the
same way. File:
`backend/src/test/java/com/ballknowers/draftsim/api/LeagueControllerSeatsUnsetOwnerIT.java`.

Not a functional bug in shipped behavior — `APP_OWNER_SLEEPER_USER_ID` was
confirmed unset in this verification environment, so the test passed before
and after this change. Applied anyway because it directly protects the one
test whose entire purpose is guarding against a real regression class named
in `AGENTS.md`.

## Deferred (not fixed — documented, needs a product judgment call or is out
of scope for this feature)

- **Sub-16ms race in the top control-bar "start" button** (not the
  full-screen overlay's button): the overlay's "Start the mock draft" CTA
  only renders once `seats` is truthy, so by the time a user can see or click
  it, `useLayoutEffect` has already corrected `mySlot` (confirmed above). The
  separate, always-rendered "start" button in the `.controls` bar at the top
  of the page, however, is not gated on `seats` or `slotKnown` — only on
  `running || resimming`. In the network round-trip window before `seats`
  first arrives, a click there would fire `run()` with `mySlot` still at
  `DEFAULT_SLOT` rather than the not-yet-known real value. This is not a
  regression: the exact same race existed before this feature (previously
  `mySlot` just stayed `DEFAULT_SLOT` until a manual edit, with no window at
  all where it could self-correct), and closing it would require gating the
  top button on `slotKnown` too — a small change, but a UX call (does
  disabling the primary "start" button during the sub-second seats fetch read
  as sluggish?) rather than a clear bug fix, and the acceptance criteria only
  talk about the input's displayed value, not button-click timing. Flagging
  for Allan rather than guessing.
- **No CSS for `.controls input:disabled`**: the new `disabled` state on the
  slot input during the brief `!slotKnown` window has no dedicated styling —
  it'll fall back to the browser's default disabled appearance. Purely
  cosmetic, out of scope for a correctness review per this task's own
  instructions, but worth a follow-up glance given this repo's stated design
  aesthetic preferences (tinted pills/hover depth, not default browser
  chrome).

## Verification

- `cd backend && .\gradlew.bat test` — **BUILD SUCCESSFUL**, all suites green
  including both new `@SpringBootTest` IT classes, run against a throwaway
  Postgres 14 cluster stood up on `localhost:5433` per `claude/environment.md`'s
  documented Windows recipe (`initdb`/`pg_ctl`/`psql` from
  `C:\Program Files\PostgreSQL\14\bin`) and torn down afterward — confirmed
  by `netstat` that port 5433 is no longer listening.
- `cd web && npx tsc -b && npm run build` — both clean, no type errors, `vite
  build` succeeded (191.10 kB JS / 14.38 kB CSS bundle).
- Re-ran both after the one test-hardening fix above; still green.

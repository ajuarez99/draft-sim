# Code review — B. Player picker: best available and team needs

Bug-hunting review, 2026-09-01, of the developer stage's diff
(`d7fda3e..5a905dd`, i.e. commit `5a905dd Player picker redesign: best-ADP
sort, team needs, best-available quick action`) against
`claude/ui-polish-roadmap.md` section B and `claude/plan-review-B.md`'s eight
binding amendments. Read cold, with fresh eyes, per this repo's pipeline
convention (`AGENTS.md`) — not a rubber-stamp of the developer's own summary.

**Verdict: no correctness bugs found. Nothing fixed, nothing deferred beyond
minor non-blocking notes below.** This is an unusually clean pass — every one
of plan-review-B.md's eight amendments was traced to a specific line and
confirmed actually resolved, not just claimed resolved.

## What was checked, and what was found

**1. `Map.of` hazard in `seats()` (amendment 1).** Confirmed fixed:
`LeagueController.seats()` (`LeagueController.java:72-90`) now builds a
`LinkedHashMap`, not `Map.of(...)`. `rosterPositions` itself is never null
(`LeagueRepository` always returns a `List`, confirmed by reading both
`bySleeperId()` and the new `byId()` — same array-unmarshal pattern, `Array
a = rs.getArray(...); List.of((String[]) a.getArray())`, never a null
branch), so this fix is prophylactic for plan A's `mySlot` landing later, not
something B's own field needed — correctly identified as such in the code's
own comment.

**2. `LeagueRepository.byId(long)` (amendment 2).** Added, and correctly
mirrors `bySleeperId()`'s query/unmarshal shape line-for-line (same six
columns, same `Array`→`String[]`→`List<String>` conversion). `LeagueController`
already held a `LeagueRepository leagues` field, wired at construction, so no
new DI issue. `draft.get().leagueId()` is safe to call unconditionally at that
point in `seats()` since the method already returns 404 earlier when `draft`
is empty.

**3. Empty `rosterPositions` (amendment 3).** Confirmed handled at two
independent points that both have to agree, and do: `PlayerPicker.tsx:87`
gates the "your team" strip on `rosterPositions.length > 0`, and
`computeTeamNeeds([], drafted)` naturally returns `[]` (its `starterSlots`
filter of an empty array is empty) if that gate were ever bypassed — no
"strip with zero badges" state is reachable.

**4. FLEX-overflow tie-break (amendment 4).** This is the one I checked most
skeptically, since a plausible-looking client reimplementation is exactly
the kind of thing that silently diverges from the engine. Traced both sides
line by line:
- Backend `FootballRules.value(BoardEntry) = exp(-adp/decay)` — monotonically
  *decreasing* in `adp`, so "highest value" ⇔ "lowest adp". `startingLineupValue()`
  (`FootballRules.java:66-89`) fills each position's dedicated slots from
  `RosterState.at(pos)`, which sorts **ascending by adp** (`RosterState.java:27`,
  i.e. best-value-first), then whatever's left beyond the dedicated count
  spills into a flex pool sorted `Double.compare(value(b), value(a))`
  (descending value = ascending adp) and consumed greedily.
- Frontend `teamNeeds.ts`'s `computeTeamNeeds()` sorts each position's drafted
  players ascending by `adp` before assigning dedicated slots in template
  order (line 51), then collects the overflow (`have.slice(nextIndex)`) into a
  `flexPool` also sorted ascending by `adp` (line 71) before assigning FLEX
  slots in order.
- These are the same ordering by the same proxy (ADP ascending = engine value
  descending), applied in the same two-pass structure (dedicated first, then
  flex from the remainder). No divergence found. `dedicatedStarters()`
  (`LeagueSettings.java:31-40`) counting literal occurrences of each position
  string in the raw `rosterPositions` list also matches `computeTeamNeeds`'s
  per-occurrence `nextIndex` increment in pass 1 — same "one dedicated slot
  per literal template entry" semantics on both sides.
- Unrecognized slot strings (`SUPER_FLEX` etc.): confirmed `RECOGNIZED_SLOTS`
  excludes them and both `computeTeamNeeds`/`openPositions` treat them as
  permanently unfillable/non-open, matching the plan's stated resolution.

**5. `bestAvailable` reused instead of a fresh client sort (amendment 5).**
Confirmed `PickPrompt` takes `bestAvailable: PlayerRef | undefined` as a prop
and `DraftView.tsx:348` sources it as
`result.bestAvailable[String(reveal.pausedAt)]?.[0]?.player` — the exact
simulation-weighted field the plan review flagged as already-unused-but-correct,
not a second, independently-drifting ADP computation. `bestAvailable` is a
`Map<Integer,List<Candidate>>` keyed only by `myPicks` entries server-side
(`MonteCarloRunner.java:117`); `pausedAt` is only ever a member of `myPicks`
when `PickPrompt`/`PlayerPicker` can render at all (both gated on
`reveal.pausedAt != null`, which the reveal mechanism only sets at the user's
own picks), so the lookup is never attempted against a key that can't exist.
The optional-chained `?.[0]?.player` access is safe against every edge
enumerated in the task brief: a missing key, an empty list (possible if
`avail.length == 0` for every run at that pick — `MonteCarloRunner.java:126`
only merges into `bestAvailCounts` when `avail.length > 0`, so the list can be
legitimately empty), and a filtered-out null player
(`MonteCarloRunner.java:143`, `.filter(cand -> cand.player() != null)` already
guarantees `bestAvailable` never contains a null-player candidate).

**6. Shared helper, not duplicated logic (amendment 6).** Confirmed —
`web/src/teamNeeds.ts` is the only place `computeTeamNeeds`/`needLabel`/
`openPositions`/`draftedSoFar` are defined, and both `PlayerPicker.tsx` and
`DraftView.tsx` (which derives `myDraftedPlayers` for `PickPrompt`'s eventual
consumers) import from it rather than reimplementing.

**7. `positionalRank == 999` display rule (amendment 7).** Confirmed handled
in exactly one place (`PlayerPicker.tsx:21-23`, `rankLabel()`), which falls
back to `ADP {adp}` instead of rendering `RB999`. Traced the sentinel's origin
(`BoardService.java:220`, `r.positionalRank() == null ? 999 : r.positionalRank()`)
through `BoardEntry` → `MonteCarloRunner.ref()` → `SimulationResult.PlayerRef`
→ `api.ts`'s `PlayerRef.positionalRank` — never null at any hop, so the `999`
check is the only guard actually needed, and it's the only place
`positionalRank` is consumed on the frontend (grepped `web/src` for other
uses — none).

**8. Live-verification note (amendment 8).** Not added to the roadmap doc as
prose by this stage (that's a documentation nit, not a code defect); noted
here for the next stage: this repo has no frontend test framework
(confirmed again — zero `.test.`/`.spec.` files under `web/src`), so every one
of section B's 7 acceptance criteria still needs a live pass against the real
running app, same as plan-review-B.md said. The test stage should not expect
anything runnable here beyond `tsc -b`/`vite build`.

## Specifically-requested checks (task brief)

- **New mutable `LinkedHashMap` in `seats()` — actually used correctly, all
  nullable fields guarded?** Yes (see #1). `rosterPositions` isn't nullable to
  begin with; the map conversion exists for plan A's future `mySlot`, exactly
  as amendment 1 specified.
- **FLEX tie-break matches `FootballRules`'s greedy-by-value ordering, or
  only looks like it does?** Verified it actually does — see #4's line-by-line
  trace, not just the presence of a docstring claiming it.
- **`positionalRank` 999-sentinel handling?** Verified end-to-end from
  `BoardService` to the one render site — see #7.
- **`SimulationResult.bestAvailable[pausedAt][0]` ever accessed unsafely
  (empty array, missing key, wrong pick number)?** No — see #5. Key is always
  a `myPicks` member when accessed; `?.[0]?.player` chain covers empty-list
  and (structurally impossible, but still guarded) null-player cases.
- **Empty-`rosterPositions` path?** Verified hidden, not rendered broken —
  see #3.
- **`api.ts` new fields truly mirror the Java records field-for-field?**
  Yes — `PlayerRef` gained `positionalRank: number` matching
  `SimulationResult.PlayerRef`'s new `int positionalRank` exactly (same
  field, 7-of-7 fields present on both sides, checked by direct comparison of
  the record declaration and the TS type); `SeatsResponse` gained
  `rosterPositions: string[]` matching the new `body.put("rosterPositions",
  rosterPositions)` (a `List<String>`, serializes to a JSON string array).
- **"Fills a need" tag double-counting or mishandling a multi-position
  player?** No double-count: `needLabel()` (`teamNeeds.ts:97-101`) returns on
  the first match (`open.has(position)` before the FLEX fallback), so a
  player who could plausibly fill two slot types only ever gets one tag
  string. `PlayerRef.position` is a single primary position (mirrors
  `BoardEntry.position() = player.primary()` on the backend), so there is no
  multi-position list to mishandle in the first place — both sides agree a
  player has exactly one position for roster-fill purposes.
- **Regression risk (acceptance criterion #7): dedup, position filter chips,
  Escape/backdrop close, resim trigger.** Confirmed untouched by reading the
  actual diff (`git diff main...HEAD -- web/src/components/PlayerPicker.tsx`),
  not the developer's claims: the `alreadyPicked`/dedup filter
  (`PlayerPicker.tsx:61`), the `POSITIONS` filter-chip array and its render
  loop, the `Escape`-key `useEffect` and backdrop `onClick={onClose}` +
  `stopPropagation()` card wrapper, and the `onPick` prop threading straight
  into `choosePick` (unchanged in `DraftView.tsx`) are all present in the
  diff's *unchanged* lines — the diff only adds the team-strip block, swaps
  the sort comparator, and swaps the rightmost two column bodies. No line
  touching any of the four regression-risk mechanisms was modified.

## Non-blocking notes (not fixed — judgment calls or too minor to touch)

- `PlayerPicker.tsx:89`, `needs.map((n, i) => ...)` keys the team-strip list
  on array index rather than a stable identifier (e.g. `` `${n.slot}-${i}` ``).
  Harmless here since `needs`' order is deterministic per render (driven by
  `rosterPositions`, which doesn't reorder within a session), but it's a
  latent React-key smell if that ever changes. Style-level, not a bug —
  left alone per this review's scope.
- `LeagueController.seats()` now does one extra DB round-trip
  (`leagues.byId(...)`) per request, on top of the existing per-slot profile
  lookups. Plan-review-B.md's amendment 2 already flagged this as a "real, if
  small, backend addition" and accepted the cost; not revisited here since
  it's a deliberate, documented tradeoff, not an oversight.
- `PickPrompt`'s "best available" button doesn't independently re-check
  `alreadyPicked`/`userPicks` before rendering — but neither does the
  existing "Take {modelPick.player.name}" button (pre-existing pattern,
  unchanged by this diff), and `choosePick()`'s own dedup guard
  (`DraftView.tsx`, `Object.values(userPicks).some(...)`) makes a stale click
  a no-op rather than a duplicate pick either way. Not a new risk introduced
  by this change.

## Verification

- `cd backend && .\gradlew.bat test` — **BUILD SUCCESSFUL**, all tests pass
  (ran with `--rerun-tasks` to force a real recompile/run rather than trust a
  cached UP-TO-DATE result).
- `cd web && npx tsc -b && npm run build` — clean, no type errors; `vite
  build` succeeds (46 modules transformed).

No code changes were made during this review — the developer stage's diff is
being committed as-is (this file is the only addition).

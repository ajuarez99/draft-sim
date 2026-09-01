# Plan review — B. Player picker: best available and team needs

Adversarial plan-review pass, 2026-09-01, on `claude/ui-polish-roadmap.md`'s section
B, before any code exists. Per this repo's pipeline convention (`AGENTS.md`), this
stage reads the plan and the *current* code independently and looks for gaps — it
does not implement anything.

**Verdict: GO, with amendments.** The plan's concrete code claims (file:line
references, field shapes, existing behavior) check out against the current tree
almost exactly as written — it is not stale in the way the doc's own note about A/C
warned it might be. But it has one real hazard that must be fixed as part of this
change (not after), one place where it reinvents something that already exists, and
several under-specified edges that will otherwise get decided ad hoc mid-build.

## What checks out (verified against current code, not re-trusted from the doc)

- `PlayerPicker.tsx`'s sort-by-survival-descending and the bar/percentage column are
  exactly where the plan says: `rows` useMemo at lines 31–37 (filter on
  `survivalByPick[key] > 0.01`, sort descending), bar cell at lines 93–96.
- `PickPrompt.tsx` has exactly one quick action today (`Take {modelPick.player.name}`,
  lines 29–33) — the "add a second button" framing is accurate, nothing to migrate
  away from.
- `api.ts`'s `PlayerRef` (id, sleeperId, name, position, team, adp — no rank),
  `AvailabilityRow`, and `SeatsResponse` (draftId, teams, rounds, status, seats — no
  `rosterPositions`, no `mySlot`) match the plan's description exactly.
- `LeagueSettings.rosterPositions/.dedicatedStarters()/.flexSlots()/.benchSlots()`
  and `FootballRules`'s FLEX-eligibility logic (`Position.isFlexEligible()` →
  RB/WR/TE) are exactly as described, and are engine-internal only today — genuinely
  never serialized out anywhere.
- `GET /api/board`'s `positionalRank` (`LeagueController.java:106`) really is on a
  different DTO path than `SimulationResult.PlayerRef`, exactly as the plan flags as
  "check before assuming it's free." Having checked: it *is* nearly free — see below.
- All four named regressions (dedup, position filter chips, Escape/backdrop close,
  resim trigger) are real, present, and correctly described: filter chips
  (`PlayerPicker.tsx:54-60`), Escape+backdrop (`:23-29`, `:40`), dedup
  (`PlayerPicker.tsx:35` + `DraftView.tsx:129`), resim trigger
  (`DraftView.tsx:114-200`'s `choosePick`).
- `.pos`/`.pos.QB` etc. badge classes the plan wants to reuse for the "your team"
  strip genuinely exist and are shared across `DraftBoard`, `AvailabilityPanel`,
  `PlayerCard`, `PlayerPicker` already (`styles.css:128-134`).

## Hazard: the plan's own SeatsResponse note undersells a live crash risk

`LeagueController.seats()` still builds its top-level response with **`Map.of(...)`**
(`LeagueController.java:72-77`, six pairs today). `Map.of` throws `NullPointerException`
on any null value — this is AGENTS.md's own named hard rule, and `LeagueController`
has already been bitten by it once and fixed it *one method away*: `board()`
(`LeagueController.java:97-107`) switched to a mutable `LinkedHashMap` specifically
because `Map.of` can't carry a legitimately-null field, with a comment explaining
exactly why.

The roadmap doc itself says B's `rosterPositions` and sibling plan A's `mySlot` need
to land additively in this exact same response. Plan A's own text is explicit that
`mySlot` **is nullable** ("`null` when the config value is unset, or when it's set
but doesn't match any seat in this league"). Whichever of A/B lands second into
`seats()` will crash this endpoint at runtime — not at compile time, `Map.of` is
generic — the first time `mySlot` resolves to null in a real league, which per A's
own acceptance criterion #3 is an expected, common state (config unset, or a league
the owner isn't in).

**This must be fixed as part of whichever change touches `seats()` first**, not
deferred: convert the response construction from `Map.of(...)` to a `LinkedHashMap`
(mirroring `board()`'s own fix) before adding either new field. `rosterPositions`
itself is not nullable (see next section) so it doesn't independently trigger this,
but it cannot be added to a `Map.of(...)` that plan A's field would later break, and
there's no guarantee which of A/B a developer picks up first.

## Gap: rosterPositions can be empty, and nothing says what the UI does then

`roster_positions` is `text[] not null default '{}'` (`V1__init.sql:54`) —
`LeagueRepository` always returns a `List`, never `null`. So `rosterPositions` does
not hit the `Map.of` null trap directly. But it **can legitimately be an empty
list** — a league whose roster settings haven't synced, or a malformed ingest. The
plan's team-needs helper ("given `rosterPositions`, ... compute which slots are
filled and which remain open") has no stated behavior for the empty-list case. Two
very different outcomes are both plausible reads of the current text: render a "your
team" strip with zero badges (looks broken, not obviously "no data"), or hide the
strip entirely. Pick one explicitly before building — hiding with a small "roster
settings unavailable" affordance (or simply not rendering the strip) is almost
certainly right, but it isn't written down anywhere in the plan.

## Gap: FLEX-overflow ordering is unspecified when more than one candidate exists

`FootballRules.startingLineupValue()` (`FootballRules.java:66-89`) fills dedicated
slots first, then fills FLEX **greedily by `value()`** (an ADP-decay function) from
whatever RB/WR/TE remain across all flex-eligible positions at once — order matters
whenever there's more than one flex-eligible overflow candidate. The plan's
client-side helper says only "treating FLEX correctly ... matching how
`FootballRules`'s own `flexSlots()` concept already works," which describes *which*
positions are flex-eligible but not the *tie-break* for which specific player fills
FLEX first when, say, three WRs have been drafted against two dedicated WR slots and
one open FLEX. Two reasonable implementations (draft order vs. value/ADP order) give
different answers for "is FLEX filled, and by whom" on the identical roster — pick
one before building. Reusing the picker's own new ADP-ascending sort as the value
proxy is the cheapest option and keeps the frontend's notion of "value" consistent
with what the picker already displays, but the plan should say so explicitly rather
than leave two engine-diverging implementations equally plausible.

Related, non-blocking: `LeagueSettings` only recognizes literal `"FLEX"` — a
`SUPER_FLEX`/`REC_FLEX` league (comment at `LeagueSettings.java:23`: "exist in other
formats; only FLEX is handled here") would have slot strings in the raw
`rosterPositions` list the client-side helper doesn't know how to badge. Not an issue
for fantasy(heart) (confirmed standard QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF), but
`rosterPositions` is proposed as a generic, read-only, "any league" field — worth one
line in the plan on what an unrecognized slot string renders as (a plain, always-open
badge is the simplest safe default) so it isn't decided ad hoc mid-build.

## Gap: an existing, unused backend field may already answer "best available"

`SimulationResult.bestAvailable: Map<Integer pickNo, List<Candidate>>`
(`SimulationResult.java:15`) is computed today in `MonteCarloRunner.java:112-148`,
scoped specifically to the user's own `myPicks` — for each of your picks, which
player was most often the actual best-available option *across the simulated runs*
(not a static ADP number; it accounts for what was realistically still on the board
in each run). It is fully typed on the frontend
(`api.ts:58`, `bestAvailable: Record<string, Candidate[]>`) and **completely
unreferenced anywhere in `DraftView.tsx`, `PickPrompt.tsx`, or `PlayerPicker.tsx`** —
confirmed by search, not assumed.

This is the same shape of miss `AGENTS.md` names as a recurring lesson ("a roadmap
doc's stated scope/difficulty is not a verified spec... found the mechanism it
needed already existed, fully tested, just unexposed"). The plan proposes computing
`PickPrompt`'s new "best available" button from a fresh client-side ADP sort plus a
duplicated survival filter — `result.bestAvailable[String(pausedAt)]?.[0]?.player`
may already be the more correct answer for that exact button (simulation-weighted,
not static), and needs zero new plumbing since it already ships in every
`SimulationResult`. Before building: decide explicitly whether `PickPrompt`'s second
button should read `bestAvailable` or a fresh ADP sort — and if fresh ADP is chosen
anyway (e.g. because "best ADP" was Allan's literal ask, not "best simulated"), say
so in the plan so it reads as a deliberate choice, not an oversight.

## Gap: prop-wiring needed for the new data isn't itemized

Today, `DraftView.tsx` passes `PlayerPicker` only `pausedAt, teams, availability,
alreadyPicked, onPick, onClose`, and `PickPrompt` only `pausedAt, teams, modelPick,
onPick, onOpenPicker`. Neither receives `seats.rosterPositions`, `result.myPicks`,
`userPicks`, or (if not using `bestAvailable`) the survival-filter inputs needed for
a second best-available computation. The plan describes the *derivations*
abstractly but not the concrete new props threading through `DraftView` into both
components. Minor on its own, but combined with the previous gap: if `PickPrompt`
ends up re-deriving "best available" via a fresh ADP sort/filter instead of reading
`bestAvailable`, that logic will exist in two places (`PlayerPicker` and
`PickPrompt`) and can silently diverge. Recommend one shared function — extending
the plan's own proposed `web/src/teamNeeds.ts` (or a sibling module) — used by both
call sites, not independently reimplemented.

## Gap: positionalRank's 999 sentinel isn't mentioned

`BoardService.java:220` defaults `positionalRank` to `999` when Sleeper's own board
doesn't have a rank for a player (never null at the `BoardEntry` level — good, no
`Map.of`-style trap here since `SimulationResult.PlayerRef` is a plain record
constructor, not a `Map.of` call). But if the picker displays `"{position}{rank}"`
labels as the plan proposes ("RB2," "WR5"), a `999` sentinel needs an explicit
display rule (omit the rank, fall back to plain ADP, etc.) or a real player will
render as "RB999." One line in the plan would close this.

## Confirmed but worth restating: backend touch is slightly larger than "one field"

`LeagueController.seats()` has no current path from a draft to its league's
`roster_positions` — `DraftRepository.DraftRow` carries the *internal* `leagueId`
(long), but `LeagueRepository` only exposes `bySleeperId(String)` and `all()`, no
lookup by internal id. Getting `rosterPositions` into `seats()` needs either a new
`LeagueRepository.byId(long)` method or an inline join — a small, real addition the
plan's "no backend change beyond the one additive field" framing (acceptance
criterion #6) doesn't itemize. Not a reason to reject the approach — just don't let
"additive field" become "no new backend code," since it visibly is.

## Test-stage note (not a defect in the plan, but should be said explicitly)

There is no frontend test framework anywhere in this repo (`web/src` has zero
`.test.`/`.spec.` files, no vitest/jest config found). Every one of section B's 7
acceptance criteria is phrased as a UI observation ("shows players ordered...", "a
strip is visible...") — none are unit-testable as written, and there's nothing to
add automated coverage to even if desired. This matches how C was actually verified
("Built and verified live," no test file). The plan should say up front, the same
way C's own note does, that B's verification pass is 100% live/manual (real server,
real browser, per AGENTS.md's own stated bar for "verified" in this repo) — mainly
so the eventual test-stage agent doesn't burn a turn discovering there's no harness
to run.

## Amendments the developer stage must apply

1. **Fix the `Map.of` hazard in `seats()` first.** Convert its response construction
   to a mutable `LinkedHashMap` (same pattern as `board()`) before adding
   `rosterPositions` — required regardless of whether A's nullable `mySlot` has
   landed yet, since the next thing to touch this method might be either plan.
2. Add the `LeagueRepository` lookup `seats()` actually needs (by internal
   `leagueId`, not `bySleeperId`) to source `rosterPositions` — a real, if small,
   backend addition beyond the response field itself.
3. Decide and document: empty `rosterPositions` → hide the "your team" strip (or an
   explicit "unavailable" state), not a strip with zero visible content.
4. Decide and document the FLEX-overflow tie-break for the client-side team-needs
   helper (reusing ADP order is the cheapest, most consistent choice) and what an
   unrecognized slot string (`SUPER_FLEX` etc.) renders as.
5. Before implementing `PickPrompt`'s "best available" button from scratch, check
   whether `result.bestAvailable[String(pausedAt)]` already answers it — it is
   unused today, computed per-your-pick, and weighted by real simulated
   availability rather than static ADP. If a fresh ADP sort is still chosen instead,
   say why in the plan/PR description rather than leaving it implicit.
6. Extract the best-available/team-needs derivation into one shared client helper
   used by both `PlayerPicker` and `PickPrompt` — don't let the same filter/sort
   logic exist twice and drift.
7. Add a display rule for `positionalRank == 999` (unknown rank) before shipping
   "RB2"-style labels.
8. State plainly (as C's entry does) that B's acceptance criteria are verified live,
   not by an automated test suite that doesn't exist for this frontend.

None of the above changes the plan's fundamental shape or scope — B is still the
right-sized, additive change the roadmap doc describes. These are the specific
things that would otherwise get decided by accident mid-build, or (in the `Map.of`
case) shipped as a live crash the first time a real league has no matching
`mySlot`.

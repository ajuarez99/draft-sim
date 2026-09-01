# Plan review — A. Auto-detect which slot is you

Adversarial review of `claude/ui-polish-roadmap.md` §A, done cold before any code
exists, per this repo's plan → review → build → code-review → live-verification
pipeline (`AGENTS.md`, "This repo's convention for building anything
feature-sized"). Reviewed against worktree HEAD `54fbb45` ("Instant-start board
reveal, plus AGENTS.md and ui-polish roadmap doc") on `feature/auto-detect-slot`,
2026-09-01. Plan A itself is dated 2026-08-30, one commit behind (C landed since).

**No code was written or changed as part of this review.**

## Verdict: GO, with amendments

The design is sound and small — single config value, one new/avoided repository
lookup, one nullable response field, one frontend effect. Nothing here should be
rejected wholesale. But one of the amendments below (the `Map.of` risk) is not
optional polish — building the plan exactly as literally worded will 500 on the
endpoint in the default, unconfigured case, which is the state every dev
environment starts in. Treat that one as a blocking correction, not a nice-to-have.

## Stale references (plan is one commit behind; C's landed changes shifted line numbers)

All checked against the actual current file contents, not assumed:

- **Real staleness, could misdirect the developer**: plan cites `DraftView.tsx:84-87`
  as "the existing `refetchSeats()`/seats-loading `useEffect`." That range is now
  `handleSeatsChanged()`, a *different* function. The effect that actually calls
  `refetchSeats()` on mount/`draftId` change is now at lines **90-93**, and — this
  matters more than the line drift itself — that effect fires before `seats` exists;
  see "Auto-detect timing" below for why the auto-detect step doesn't actually belong
  bolted onto it as literally described.
- Cosmetic only, no design impact: `DraftView.tsx:242-251` (manual slot input) is now
  at **~253-260**; `api.ts:74-80` (SeatsResponse) is now at **75-81**. Both drifted by
  C's added comments/`started` const/full-screen overlay block, not by anything
  design-relevant.
- Confirmed still accurate, no drift: `DraftPicker.tsx:48`, `DraftView.tsx:23,41`,
  `DraftView.tsx:95-106` (setMySlot), `api.ts:62-81` (Seat+SeatsResponse combined
  span), `ManagerRepository.java:44-51` (`idsBySleeperUserId()`), `V1__init.sql:38-42`
  (`manager` table — `sleeper_user_id text not null unique` confirmed verbatim).

## Critical risks (must be addressed before/while building)

### 1. `Map.of(...)` NPE — directly named in AGENTS.md's hard rules, and this endpoint already has the fix pattern sitting three methods below it

`LeagueController.seats()` builds its response with the 5-arg `Map.of(...)`
(current lines 72-77), which throws `NullPointerException` on **any** null value.
The plan explicitly specifies `mySlot: Integer (nullable)`, and null is the
*default* case — unset config (true of every environment until someone sets
`APP_OWNER_SLEEPER_USER_ID`) or a league the owner isn't in. Adding `"mySlot",
mySlot` straight into the existing `Map.of(...)` call means the endpoint 500s on
every request until the feature is fully configured and matching — i.e. broken in
the state every fresh checkout starts in.

The fix is already the established local pattern: `board()`, two methods below in
the same file (current lines 92-114), was already bitten by this exact class of bug
and fixed by switching to a mutable `LinkedHashMap` with an explicit comment
explaining why (current lines 97-101: "`Map.of` rejects null values... a free
agent/retired player can have a null team... `LinkedHashMap` tolerates the null
directly"). `seats()` needs the identical treatment — mirror that method, don't
reinvent it.

**Required amendment**: build `seats()`'s top-level response map as a
`LinkedHashMap` (or otherwise avoid `Map.of` wherever `mySlot` lands), not an
extension of the current `Map.of(...)` call.

### 2. Auto-detect timing / the "no flash of 1" acceptance criterion is close to unsatisfiable as architected

`mySlot` is *derived on every render* from the URL (`slotParam ? Number(slotParam)
: DEFAULT_SLOT`), not held as component state. The plan's mechanism is: once
`seats` arrives, if the URL has no `slot` param, call `setMySlot(seats.mySlot)`.
Two real gaps here:

- **Where the effect lives.** The plan's citation (`DraftView.tsx:84-87`) pointed at
  the *wrong* effect even before drift — the mount effect at (now) lines 90-93 fires
  once, before `seats` exists, and has nothing to check. The auto-detect step needs
  its own effect keyed on `[seats]` (or to live inside `refetchSeats()`'s `.then()`),
  not to be "one more step" tacked onto the mount effect as literally described.
- **The flash itself.** Even wired correctly, there are two sequential state updates
  after the seats fetch resolves — `setSeats` (commits with `mySlot` still showing
  `DEFAULT_SLOT`, since the URL hasn't changed yet) and then, from a `useEffect`
  (which runs *after paint*, not before), `setMySlot` → `setSearchParams` (a second
  commit). Between those two commits there's a real, paintable frame with `1` showing
  — on the exact full-screen `.start-overlay` CTA this feature is meant to
  streamline. And before `seats` arrives at all (the network round trip itself),
  `1` is what necessarily renders, full stop — no implementation choice avoids that
  part.
- AC1 as literally worded ("shows `11` immediately on load — no manual typing, no
  flash of `1` first") conflates two different guarantees: "never *needs* a
  keystroke" (achievable) and "zero visible frames of the default" (not achievable
  given an inherently async seats fetch, without hiding/disabling the slot input
  until `seats` has loaded — which the plan doesn't propose and which would be a
  bigger UX change than intended here).

**Required amendment**: either (a) accept a brief flash during the unavoidable
fetch window as within spec and reword AC1 to "no flash *once seats have loaded*"
(mitigated by using the `[seats]`-keyed effect promptly, `useLayoutEffect` if the
post-fetch gap alone needs closing), or (b) if a hard zero-flash guarantee matters
to Allan, gate the slot input's rendering/enabled state on `seats != null`. Don't
let the developer stage discover this ambiguity mid-build — decide which bar
applies before writing the effect.

## Other gaps found

- **Feature-C interaction, not addressed by the plan (written a day before C
  landed)**: the `.start-overlay-cta` copy — "Set your slot above if you know it,
  then start the mock draft." — was written for a world with no auto-detection. Once
  A ships, this sentence is often simply wrong (the slot is usually already correct)
  and reads as confusing residue on the exact full-screen CTA a user lands on.
  Doesn't break anything, but should be updated (conditionally suppressed or
  reworded) in the same change, not left as a follow-up.
- **Unneeded backend surface**: the plan calls for a *new* forward-keyed
  `ManagerRepository` method (`managerId → sleeper_user_id`) "mirroring" the
  existing reverse-keyed `idsBySleeperUserId()`. That's more than is needed:
  `idsBySleeperUserId()` already returns `Map<sleeperUserId, managerId>` — a single
  lookup, `Long ownerManagerId = managers.idsBySleeperUserId().get(configuredOwnerId)`,
  done once per request and compared against each seat's already-known `managerId`
  while iterating `slotToManager`, computes `mySlot` with **zero new repository
  methods** and no per-seat/N+1 lookups. Recommend dropping the new method from
  scope — simpler, less surface, same result.
- **Blank-config landmine**: `APP_OWNER_SLEEPER_USER_ID` defaults to `""` when
  unset (via `${APP_OWNER_SLEEPER_USER_ID:}`, matching `API_TOKEN`'s own pattern).
  `manager.sleeper_user_id` is `not null` but not guarded against being blank at the
  DB level. A raw `.get("")` lookup would only misfire if a blank `sleeper_user_id`
  ever actually got written (not currently possible via normal ingest, but nothing
  stops it structurally) — one `if (ownerId.isBlank()) return null` before the
  lookup closes this off explicitly rather than relying on it happening to work.
  Small, but exactly the kind of nullable-field carefulness AGENTS.md's hard rules
  ask for.
- **Test coverage**: the plan's three states (matched / unset config / no match in
  league) are the right shape, but should explicitly include a fourth assertion —
  that the unset-config case's HTTP response actually serializes (i.e. doesn't
  500) — as the direct regression test for risk #1 above, not just an incidental
  check that the field is logically null. No `LeagueController`/seats test exists
  today (confirmed: nothing under `backend/src/test` references it), and no
  mocking-framework precedent exists for this kind of test in this suite — follow
  this repo's actual convention instead: a real-Postgres `@SpringBootTest`,
  gated to *skip* (not fail) when `localhost:5433` isn't reachable, same shape as
  `DraftRepositoryAllWithLeagueIT`/`DraftRepositoryUpsertPicksIT`.

## AGENTS.md hard-rule checklist

| Rule | Status |
|---|---|
| `api.ts` types mirror Java response field-for-field, same change | Plan already covers this (`SeatsResponse.mySlot: number \| null`) — compliant |
| Migrations append-only / new migration needed? | Plan correctly says none needed; confirmed `manager.sleeper_user_id` already exists in `V1__init.sql` as `not null unique` — compliant, nothing to append |
| `Map.of(...)` null-value risk | **Not addressed by the plan text at all** — see Critical risk #1, the one blocking item in this review |
| Ask before committing | N/A here (review stage), applies to the build stage |
| Roadmap's stated scope/difficulty isn't a verified spec | Plan's framing ("smallest, lowest-risk") held up under this review — no engine-side surprises found, the actual risk was in response-serialization mechanics, not in scope size |

## Summary for the developer stage

1. **Blocking**: build `seats()`'s response as a mutable map (`LinkedHashMap`),
   mirroring `board()`'s existing null-value fix in the same file — not an
   extension of the current `Map.of(...)` call.
2. Hook the auto-detect step to an effect keyed on `[seats]` (or `refetchSeats()`'s
   `.then()`), not the mount-only effect the plan mis-cited.
3. Decide and document which flash guarantee AC1 actually needs before
   implementing — full zero-flash requires gating the slot input on `seats`
   having loaded; anything less should be reworded into the acceptance criteria
   rather than discovered as a surprise in review.
4. Skip the new forward-keyed `ManagerRepository` method; reuse
   `idsBySleeperUserId()` with one reverse lookup instead.
5. Guard blank config explicitly rather than relying on lookup-miss behavior.
6. Update the now-stale `.start-overlay-cta` copy to not contradict a
   successful auto-detection.
7. Add the fourth test case (unset config → response actually serializes,
   not just "field is null"), using this repo's real-Postgres `@SpringBootTest`
   IT convention.

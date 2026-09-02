# Next features — reconciled plan and build order

Design note, 2026-08-29. **Nothing built.** Produced by planning four features in
parallel (separate agents, no visibility into each other's plans beyond a short
description) and reconciling the results into one roadmap. The four source plans
are not preserved verbatim here — this document is the reconciled result, written
to stand on its own as a brief for whoever builds next, per this project's existing
convention (see `borrowed-drafts.md`, `live-reveal-and-tendencies-ui.md`).

Features, referred to by letter throughout:

- **A** — configurable league size (8/10/12/14 teams), narrowing the existing
  unbuilt design in `claude/borrowed-drafts.md`'s "Variable league size" section.
- **B** — app shell + "pick a draft" start screen (today's app is one hardcoded
  page with a free-text draft-ID field).
- **C** — interactive Sleeper-style pick-by-pick mock draft room (bots auto-pick
  down the line, user picks on their turn, rest adapts).
- **D** — live real-Sleeper-draft polling + prediction (the "draft-day gap"
  `HANDOFF.md` already flags as the largest remaining payoff).

**Time-sensitive context that shapes sequencing:** fantasy(heart)'s real draft
(`HANDOFF.md`'s league facts — 14 teams, `pre_draft` as of this session) is
2026-08-31 — two days out. `HANDOFF.md` already calls D "the real draft-day gap.
Nothing built." That reprioritizes D upward regardless of the "ideal" engineering
sequencing below.

---

## 1. Executive summary

**D — Live Sleeper draft polling.** While a real Sleeper draft is running, the app
polls it in the background, writes new picks into the same database the simulator
already reads, and re-runs the "who's likely available at my next pick" prediction
as the board fills in. Matters most *right now*: concrete imminent use date,
**zero changes needed to the scoring/simulation engine** (confirmed by reading the
code, not assumed — `SimulationService.simulate()` already resume-simulates
correctly against a partially-drafted, `drafting`-status draft), and most pieces
are small, additive, independent of A/B/C.

**A — Configurable league size (8/10/12/14).** Lets the simulator run against a
league that doesn't exist in the DB — any of the four sizes, seats assigned to
real managers or left as bots. The dropdown is the easy part; the real work is
fixing several places in the scoring engine hard-coded to "round number" instead
of "fraction of the draft," which silently misbehaves at any team count other than
what the model was tuned on (12–14). Foundational for C, which needs the identical
capability to start a mock at an arbitrary size.

**B — App shell + draft picker.** Replaces the single hardcoded page with real
navigation: a picker screen listing your drafts, an "add a new draft" flow, and
the existing simulation view moved behind a URL — so C's "start a custom mock" and
D's "go live" each get a real entry point instead of being bolted onto the current
header.

**C — Interactive mock draft room.** The largest lift: you click real picks, bots
pick around you down the snake order, the room reacts to what you actually took.
Needs a new table pair, a refactor to share the core "decide a pick" logic between
the existing batch simulator and this new turn-by-turn flow, and careful isolation
so a mock session's synthetic picks never leak into the real per-manager profiles.

**Recommended order (updated after review with Allan, 2026-08-29):** D first
(deadline), then B's shell, then C's mock room, with A's *visible* feature
(ad-hoc league sizing) last. **Why A moved down:** most near-term value is seeing
and acting on drafts you're actually in (B) or mocking them turn-by-turn (C) —
those are fixed-size real leagues, so letting someone spin up an ad-hoc
8/10/12/14 league that doesn't correspond to a real draft matters less right now.
A's *normalization sub-piece* (fixing `PickScorer`/`ProfileService` off raw round
onto `pick_pct`) is kept early despite this, split out from the rest of A — it's
an existing bug fix independent of whether ad-hoc sizing ships, and it touches the
same files C needs to extract `DraftSimulator.choose()` from, so sequencing it
before C avoids each landing through a rebase of the other's changes to
`PickScorer`/`ProfileService`. See §4. **Built 2026-09-01** — and the "existing
bug fix" framing turned out to be half wrong: the reach-bias half of it was not
a bug at all (§Phase 1), leaving the rebase argument as the real reason it went
first, which it still was.

---

## 2. Resolved agreements

### (a) Keep mock/live picks out of `draft_pick` and out of `ProfileService.fit()` — confirmed, genuinely resolved

`ProfileService.fit()` reads `draft`/`draft_pick` via `DraftRepository.allCompletedPicks()`,
filtered to `status = 'complete'`. `draft.sleeper_draft_id` is `not null unique`
with a required `league_id` FK — a from-scratch mock can't get a row there without
inventing a fake unique Sleeper ID and a fake league.

C's answer: separate `mock_draft_session`/`mock_draft_pick` tables (new V3
migration), structurally walled off from anything `ProfileService` touches. D's
independent instinct (never intending to build C, just flagging the risk):
don't persist mock picks at all, keep them as ephemeral `startState` on each sim
call. These aren't identical mechanisms — D's version wouldn't satisfy C's own
resumability requirement (replay-after-refresh needs committed rows) — but the
underlying goal (real fitted profiles must never see synthetic picks) is genuinely
shared, and C's mechanism is a strict superset. **Build C's separate-table design;
treat D's suggestion as satisfied, not as an additional requirement.** Keep a
standing regression test asserting `ProfileService.fit()`/`allCompletedPicks()`
never see `mock_draft_pick` rows — this is exactly the class of bug
`claude/lessons.md` #1 describes (structural tests pass, semantics silently wrong).

### (b) A and C need the same "DraftContext from explicit config" builder — confirmed; A builds it

`SimulationService.simulate()` only knows how to build a `DraftContext` from a
real `draft`+`league` row. A's ad-hoc branch needs a second path: build
`LeagueSettings` + a seat map directly from a request payload, no DB lookup. C
needs the identical capability twice — starting a `POST /api/mocks` session, and
its optional on-demand "outlook" rerun. Both want the same function:
`(teams, rounds, rosterPositions, ppr, seatAssignments) -> DraftContext`.

**A builds it** — A needs it as a hard, minimal prerequisite for its own core
feature with no migration or extraction blocking it first; C needs it as one piece
behind a bigger migration+extraction effort. Land it as a standalone, unit-tested
class before either controller ships, so `POST /api/mocks` literally calls the
same code the ad-hoc `POST /api/sims` branch calls. One shape mismatch to resolve
while building it — see §3.2.

### (c) B's `DraftSummary.status` and D's `SeatsResponse.status` are the same field, not competing signals — confirmed, with a freshness gap

`GET /api/drafts/{id}/seats` already does `seat.put("status", String.valueOf(draft.get().status()))`
against the existing `draft.status` column. B's proposed `GET /api/drafts` list
endpoint reads the same column through a different, list-oriented shape — no
schema mismatch, no need for B's endpoint to serve anything extra for D.

The real gap is **freshness**: D's poller only updates `draft.status` for drafts
that have been explicitly `track`ed. A draft that goes live between visits shows
stale `pre_draft` on the picker. **Give the picker card a lightweight "refresh"
action** (calls `/track` or a cheap one-shot status check) rather than assuming
the DB value is current — a natural extension of B's status-driven CTA design,
not new infrastructure.

---

## 3. Real conflicts or gaps requiring a decision

### 3.1 Team-size ceiling mismatch (16 vs 14)

A deliberately narrows the original 8–16 design to {8,10,12,14} with a concrete
reason: board depth (max 14×20=280 picks) stays comfortably inside the ~600-player
pool the engine assumes, but that stops being true above 14. C's test list still
mentions "8/10/12/14(/16)," implicitly reopening 16. **Cap the mock room at the
same {8,10,12,14} set A ships**, for the same board-depth reason; drop 16 from C's
test matrix unless board depth at 16 is separately verified. One team-size domain
across the app also means B only ever renders one dropdown.

### 3.2 Two incompatible seat-assignment shapes

A's ad-hoc seats are 2-state: a slot maps to a real `managerId`, or is omitted and
falls back to a neutral bot. C's seats are 3-state: `USER | MANAGER | BOT`,
because the mock server genuinely needs to know which seat requires synchronous
human input. If both ship their own shape, B's shared "assign seats" UI has
nowhere consistent to render into, and the shared `DraftContext` builder from
§2(b) ends up with two callers passing different-shaped seat maps.

**Adopt C's 3-state `SeatSpec` everywhere** — A's `/api/sims` ad-hoc mode, C's
`/api/mocks` mode, and B's UI. For A's use, `mySlot` becomes redundant with (or a
validated cross-check against) whichever slot has `type: USER`; `MANAGER` carries
`managerId`; `BOT` is the neutral fallback. Decide this before either controller
ships.

### 3.3 Is `rosterPositions` configurable in ad-hoc/mock mode?

A flags this as its own open question. It isn't only A's problem — C's session
start needs the same answer, and B's "start a custom mock" form needs to know
whether to render a roster editor or a one-field dropdown. **Ship a fixed roster
template per team size in v1** (derive from an existing production league's
template, e.g. scale fantasy(heart)'s 14-team shape), defer a roster editor
entirely. Keeps B's UI to one dropdown and avoids stacking an untested dimension
(custom roster shapes) on top of the pick_pct normalization risk in the same
release.

### 3.4 C's "eventually unify with live" musing vs. D's explicit push-back — genuine disagreement

C floats generalizing its tables into `draft_session`/`draft_session_pick` with a
`mode: MOCK | LIVE_SLEEPER` column "if the two features' data models end up
wanting to converge." D argues the opposite: share the *consumer* contract
(`completedPicks()`/`startState()`) but never force one *producer* abstraction —
D's writes are idempotent upserts from an external ground truth with no user
input; C's writes are synchronous user/bot decisions under a row lock for
concurrency safety. **Side with D.** Keep `draft`/`draft_pick` (extended by D's
`upsertPicks`) and `mock_draft_session`/`mock_draft_pick` (C) as two separate
producers indefinitely — document this so a future session doesn't "fix" it by
merging schemas and reintroduce the exact contamination risk §2(a) avoided.

### 3.5 Frontend reuse: C over-rejects `DraftBoard`; align with D's more surgical approach

D reuses `DraftBoard`/`SeatList` mostly as-is for live mode (one `landed: boolean`
field on the board-cell type, an on-the-clock className). C says its mock room
should be "a NEW session-driven view, explicitly NOT a repurposed DraftBoard" —
right about *state* (a mock session's turn-taking has nothing to do with
`useRevealedBoard`'s animation timer, and C is right to avoid that hook), too
broad about the *grid component*. A mock draft's committed picks are, for
rendering purposes, exactly as "real" as a live draft's — both are actually-decided
picks, unlike the aggregate board's probabilistic cells. **C's mock room should
reuse the `DraftBoard` grid the same way D plans to** (feed it real picks,
`landed: true` throughout, no probability data), building genuinely new pieces
only for what's actually new — the turn indicator and the on-the-clock pick input.
Three near-identical draft grids is avoidable rework.

---

## 4. Recommended build order

Two tracks running partly in parallel, not a strict A→B→C→D sequence, given the
imminent draft date. **Updated 2026-08-29: A's visible ad-hoc-league-size feature
moves to last** — Allan's real near-term usage is drafts he's actually in (served
by B and C), not spinning up leagues that don't exist yet. Only A's normalization
*sub-piece* (§Phase 1) stays early, kept separate from the rest of A specifically
because it's (a) an existing bug fix with value on its own and (b) a prerequisite
for C touching the same files without rebase pain — see the tradeoff called out
in §1. If that tradeoff isn't worth it, the alternative is doing all of A last and
accepting that C's `DraftSimulator.choose()` extraction happens before, not after,
A's rewrite of the same file.

### Phase 0 (now, parallel, no cross-dependencies) — draft-night-critical + independent low-risk infra

- **D's poller + incremental ingest**: `LiveDraftPoller` (virtual thread per
  tracked draft), `POST /api/drafts/{id}/track`, `DraftRepository.upsertPicks()`
  (the `unique(draft_id, pick_no)` constraint already exists). Confirmed
  additive — zero engine changes. Ship standalone if the deadline is tight; it
  delivers real value without waiting on A/B/C.
- **D's benchmark step**: measure actual Monte Carlo wall-clock time at current
  iteration counts before picking a re-simulation debounce threshold.
- **Pre-draft checklist**: confirm `POST /api/ingest/board` has been run for
  fantasy(heart) before the draft goes live — the live-poll path deliberately
  skips board rebuilds on every tick.
- **A's snake mechanics test matrix**: parametrizing `DraftSlotTest` across
  {8,10,12,14} (+9/11/13/15 as insurance) is pure test work, zero dependency.

### Phase 1 — the one genuinely risky shared-math change (before Phase 3)

**Only the normalization sub-piece of A, not the rest of A** — see the split
explained in §1/§4 intro. The ad-hoc league-size API, seat assignment, and
frontend dropdown (the rest of A) move to Phase 5, after C.

- **A's pick_pct normalization — BUILT 2026-09-01.** Rebucketed
  `PositionalPriors`/`ProfileService.fitPriors` off raw `round` onto
  fraction-of-draft buckets, and moved K/DEF gating from a fixed round number
  onto rounds-remaining (`weights.yml`'s `earliestRound: {K: 13, DEF: 12}` is
  now `latestRounds: {K: 3, DEF: 4}`, identical on a 15-round league).
  Positional tilt's "first four rounds" window moved onto the same fraction
  basis. See "What the build found" below.

- ~~**Fitting-time reach-bias unit mismatch.**~~ **This was not a bug — the
  claim above was a misreading of `BoardService`, corrected here 2026-09-01
  rather than left to look authoritative.** The claim was that `BoardService`
  rescales `adp_at_time` to `cfg.referenceTeams()` while `ProfileService.fit()`
  compares it against a raw `pickNo`. The `referenceTeams` rescale happens to
  the three input sources *before* they are blended, and the blend is then
  **re-ranked to a dense 1..N ordering** (`BoardService.rebuild`, the "Re-rank
  so the board is a clean 1..N pick ordering" block) — so what lands on
  `adp_at_time` is a board *rank*, not a 14-team pick number. Verified against
  the live board, whose top entries carry `adp` of exactly 1.0, 2.0, 3.0, 4.0,
  5.0. Board rank and pick number both count players consumed, so
  `adpAtTime - pickNo` is dimensionally consistent at any team count, and the
  re-rank has been there since v0 (`77f34bb`) — this was never true, not
  something that drifted. What *is* genuinely size-dependent is what a fixed
  reach of +8 picks means behaviourally (half a round at 16 teams, a full round
  at 8); that is a modelling question, deliberately left alone, since changing
  it would also change the units the tendencies UI states reach in.
- **Sequence before Phase 3, not concurrently.** C needs to extract
  `DraftSimulator.choose()` (currently private) into a reusable unit shared with
  the batch `run()` loop. If A's rewrite of `PickScorer`/`ProfileService` lands
  while C is mid-extraction of a neighboring method in the same file family, one
  effort rebases through the other for no reason.
- **Run A's own recommended safety net**: before/after regression check on the
  real 14-team fantasy(heart) league's modal picks before trusting the refactor —
  one of two things in this whole roadmap that can only be de-risked by actually
  running it (the other is D's live-draft-night assumptions, §5).
- The shared `DraftContext`-from-config builder (§2b) still belongs at the tail
  of this phase, low risk — C's Phase 3 needs it. **Note this creates a partial
  dependency the reprioritization doesn't remove**: C needs the *builder*, which
  was originally described as "part of A," even though the rest of A (the ad-hoc
  `SimulationRequest` branch, seat assignment, dropdown) is now deferred to
  Phase 5. Build just the builder here; the rest of A's plumbing waits.
  **BUILT 2026-09-01** as `DraftContextFactory` + `SeatSpec` + `LeagueShape`.
  `SimulationService.simulate()` was rewritten to go through it rather than
  assembling a `DraftContext` inline, so C's `POST /api/mocks` and the deferred
  ad-hoc branch inherit one already-exercised path instead of a second one. The
  §3.2 3-state `SeatSpec` and the §3.3 fixed roster template are both settled in
  code now, not just on paper.

#### What the build found (2026-09-01)

**The normalization is a numerical no-op on today's data, and that is the
result, not a shortcut.** With buckets = 15 and every ingested league at 15
rounds, a fraction-of-draft bucket *is* a round — for 12- and 14-team drafts
alike — so the fitted table comes out identical. Likewise `latestRounds
{K: 3, DEF: 4}` reproduces `earliestRound {K: 13, DEF: 12}` exactly at 15
rounds. The change is what makes both correct at a round count or team count
nothing was fit on; it buys nothing at 14×15 and was never going to.

Verified by running it, per this phase's own safety net, though **not against
fantasy(heart)** — that draft completed between the roadmap being written and
this build, so it now replays 210 real picks and simulates nothing. Ran against
West Coast FF 2026 instead (14 teams, `pre_draft`, no picks), old code and new
code side by side on the same board:

- **T=0, 1 iteration (fully deterministic): all 210 picks identical**, same
  players, same probabilities, K in rounds 13–15 and DEF in 12–15 on both.
- **T=1.0, 2000 iterations**: modal player agrees at 32/210, `mean |dProb|`
  0.0040. That looks like a difference and is not — the **control** (old code
  against *itself*, different seeds) gives 33/210 and 0.0039. At round-1 modal
  probabilities of 4–9%, which player is modal is decided by sampling noise.
  Cite the T=0 run, not this one, if you need the evidence.

The suite went 74 → 132 tests, all passing, integration tests included (real
Postgres, none skipped). New: `PositionalPriorsTest` (bucketing, and the
property that makes the change safe — bucket == round at 15/15 for every
supported size), `DraftContextFactoryTest`, `LeagueShapeTest`, a rounds-remaining
gating test, and `DraftSlotTest` parametrized across {8,9,10,11,12,13,14,15} —
the §Phase 0 snake matrix, which was free and is now done.

**Deliberately not done, and it is the same class of bug:** `runWindow` is a
fixed 6 picks, which is 43% of a round at 14 teams and 75% at 8
(`claude/borrowed-drafts.md` flags it alongside the three items above). It is
left for Phase 5, where it belongs with the rest of the size work — unlike the
three above it has no correctness argument at 14 teams, and scaling it changes
current behaviour rather than preserving it.

### Phase 2 — B's shell (parallel with Phase 0/1; land before Phase 3's frontend)

- Backend: `GET /api/drafts` (new `DraftRepository.allWithLeague()` join, no
  migration needed), `DraftSummary` record, extend `POST /api/ingest/all/{id}`
  with `draftsTouched`.
- Frontend: add a router (currently zero router dependency), real routes, move
  `draftId`/`mySlot` off `useState` onto route params/query, refactor the current
  page into a route-scoped view, remove the free-text draft-id input.
- **Doesn't need to wait for Phase 1** — touches only the frontend and one new
  read-only endpoint, no dependency on the `PickScorer`/`ProfileService` changes.
  Sequenced ahead of Phase 3 because C's frontend wants a real `/mock/new` route
  to land in, and A deferred its own frontend insertion point to whatever B
  builds. Reserve `/drafts/:draftId/live` for D's frontend the same way.
- If Phase 2 slips behind the draft-night push, D's live frontend can still ship
  against the current single-page app (it reuses `DraftBoard`/`SeatList` largely
  as-is) and get retrofitted into a route later.

### Phase 3 — C's mock draft room (largest lift; depends on Phase 1 and Phase 2)

Shared `DraftContext`-from-config builder (resolving the `SeatSpec` shape
decision, §3.2, first) → V3 migration (`mock_draft_session`/`mock_draft_pick`) →
extract `DraftSimulator.choose()` into a reusable decide-and-apply unit → service
(with a row lock for concurrency) → controller → frontend (new session view,
reusing `DraftBoard`'s grid per §3.5, entering through B's `/mock/new`) → optional
"outlook" rerun UI → full test suite including the standing profile-contamination
guard.

Starting C's migration or extraction before Phase 1's normalization and shape
decisions land means redoing the `SeatSpec`/`DraftContext` plumbing partway
through — this is the one phase where "build the shared thing first" matters most.

### Phase 4 — D's remaining pieces (can trail Phase 2/3 without blocking them)

`GET /api/drafts/{id}/live-stream` SSE endpoint (reusing the existing
`SimulationController.stream()` pattern), `LiveStatusBar`/`useLivePickCount`,
wiring into B's reserved `/drafts/:draftId/live` route, then the replay-proof
end-to-end test and a real draft-night dry run. If Phase 0's minimal poller
already shipped standalone, this phase is pure UI polish, not new risk.

### Phase 5 — A's remaining pieces: ad-hoc league-size feature (last, deprioritized)

The rest of A, deferred per Allan's reprioritization: the ad-hoc
`SimulationRequest` branch, `SeatSpec`-based seat assignment for a from-scratch
league (reusing the shape already settled in §3.2 for C), board-depth
confirmation, the snake-mechanics test matrix (§Phase 0 already covers this
independently and can move earlier if convenient — it has no dependency on
anything else), and the frontend dropdown/seat-assignment UI inside B's shell.
Nothing here blocks C or D; it only unlocks "mock a league you're not actually
in," which is real but lower-value than B/C/D for near-term use.

---

## 5. Biggest risks to the plan as a whole

**Hot-file contention is real but narrower than "all four features touch the
engine."** Only A (scoring math in `PickScorer`/`ProfileService`) and C
(structural extraction of `DraftSimulator.choose()`) modify engine internals. D
confirmed — by reading the code — that it needs zero engine changes; B never
touches the engine at all. The real collision is two-party (A × C), and Phase
1-before-Phase 3 is the mitigation: let A's semantic rewrite fully land and get
regression-tested before C starts structurally extracting a neighboring method in
the same files.

**The profile-contamination boundary has no structural enforcement beyond "these
are different table names."** `ProfileService.fit()` has no runtime check
stopping it from being pointed at the wrong table by a future refactor — the
safety today is purely that `mock_draft_pick` is a different table. C's guard
test is the right mitigation, but treat it as a permanent regression test any
future ingestion or mock-schema change must keep green, not a one-time
check-the-box item. This matters more, not less, if §3.4's schema-unification
idea is ever revisited.

**Two things here cannot be de-risked by more planning — only by running them,
on a deadline that matters for one.** A's normalization refactor: whether
refitting priors/reach in pick_pct space actually changes fantasy(heart)'s real
numbers is unknown until run — budget time for the before/after comparison, don't
treat the refactor as done when it compiles and passes structural tests.
**Settled 2026-09-01: it changes nothing, and the run is what established that
— see Phase 1's "What the build found". The advice stands for the next refactor
that touches this math; it was worth the hour it took.** D's
live-poll assumptions: every verification of Sleeper's `drafting`-status/`picked_by`
behavior in this codebase so far has been against `pre_draft` or `complete`
drafts, never a truly live one — the only way to know the poller's assumptions
hold is a real live draft, and fantasy(heart)'s on 2026-08-31 is the first
opportunity. Treat that draft as the actual integration test for D, plan to watch
it live, and have the `startState` escape hatch (for a pick seen in Sleeper's own
UI before the poller catches up) ready before that day, not discovered during it.

**Frontend architecture drift if Phase 2 (B) lags behind the draft-night push on
Phase 0 (D).** If live-mode ships against the current single-page app under time
pressure, and C later needs its own top-level entry point too, two independently-
invented "where do I put this new screen" answers can appear before B's router
ever lands, each needing retrofitting. Manageable — D's live view is decoupled
enough to retrofit cheaply — but decide consciously now: if the deadline forces
Phase 0 to ship standalone, treat the *routing* migration as the very next thing
after the draft, before C's frontend work begins, so C doesn't repeat the same
"ship it flat, retrofit later" pattern.

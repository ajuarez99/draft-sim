# Implementation Plan: Live draft pick insight

**Branch**: `012-draft-pick-insight` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Base**: local `main` @ `de1e1dd` (includes specs 010 and 011, unpushed)

**Input**: Feature specification from `specs/012-draft-pick-insight/spec.md`

## Summary

A passive card for every landed pick in the live draft room shows fit, open
slots, a summary, reach and surprise tags, the manager's on-brand read, and
their likely next pick. Two always-on meters sit beside it: position scarcity and
a room-wide on-brand read. Almost all of it is client-side derivation over data
the live room already holds.

Planning found three things the spec did not know (spec amended, see its
"Amended after planning" note):

1. **The live room re-projects once per seat, not once per pick** (`41b9426`).
   "Likely next" needs a post-pick projection, so this plan brings back a
   background re-projection per landed pick, **off the critical path**. Facts
   keep arriving through the existing `recentPicks` overlay (R1).
2. **The server rations simulations** (`SimulationPermits`: ≥ 2 concurrent, 3s
   wait, then 429). A dozen tabs re-projecting on every pick will see 429s. The
   plan treats a 429 as "busy" on the card, measures the rate (Q4), and names a
   shared server-side projection as the fallback (R1-alt). It does not retune
   the permits.
3. **The scarcity meter needs the board with player ids**, which no endpoint
   returns today. One small scoped endpoint, `GET /api/drafts/{id}/pool`, is the
   only backend change (R6).

## Technical Context

**Language/Version**: TypeScript 5 (strict), React 18, Vite; Java 21 / Spring Boot 3.5 for the one endpoint

**Primary Dependencies**: none new. No new npm or Gradle dependency.

**Storage**: none. There is no migration: all state is in memory per tab. One `localStorage` key (`bk.pickCards.v1`) holds a per-device preference.

**Testing**: vitest + Testing Library (web); JUnit + Spring MockMvc ITs against Postgres (backend); live verification against a disposable Sleeper draft ([quickstart.md](quickstart.md))

**Target Platform**: browser (desktop and 375px phone); backend on Railway (frontend and backend deploy independently)

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**: card fit section ≤ 1s after the feed row (SC-002). This needs facts only (R3). The post-pick projection's wall-clock time on the live stack is **unmeasured**; Q4 measures it.

**Constraints**: nothing factual may wait on a simulation (the `41b9426` invariant). The client must not add retry load on a 429. `SimulationResult` is not widened (parity baseline hashes its shape). New and old frontend/backend must coexist during a rollout: a missing `/pool` (404 or network error on an old backend) degrades the scarcity meter to a one-line reason, never the page.

**Scale/Scope**: one page (`LiveDraftView`). Leagues of 8–14 teams, ≤ 15 rounds (≤ 210 picks), up to ~12 concurrent viewers per draft.

## Constitution Check

`.specify/memory/constitution.md` is still the unfilled template, so there are no
ratified principles to gate on. The project's binding rules are in `AGENTS.md`;
this plan is checked against those instead.

| Rule (AGENTS.md) | Status |
| --- | --- |
| Migrations append-only | ✅ No migration |
| `api.ts` mirrors Java records in the same change | ✅ `getDraftPool` returns the existing `PlayerRef`; no record changes |
| Never retune a constant to match a guess | ✅ Q4's gate forbids tuning `RESIM_ITERATIONS` or permits to fit; R1-alt is the escalation |
| `Map.of` null trap in JSON paths | ✅ `/pool` serializes `PlayerRef` records directly; `team` is nullable |
| Hand-set constants labelled arbitrary | ✅ One exported object; the UI says "hand-set" where a threshold decides a verdict |
| Honesty: don't look more certain than you are | ✅ Frozen pre/post-pick stamping (R2), "as of pick N", "under X%" bounds, projected column gated to where it is exact (R7), no verdicts for neutral seats or under 3 picks |
| Recurring bug class 1 (sign inversion) | ✅ Ordering tests on reach sign and lean verdict (R10) |
| Class 5 (right stat, wrong display) | ✅ Per-cell marginal, not the coherent-board assignment, for "likely next" (R4) |
| Class 6 ("no caller yet" ≠ works) | ✅ `/pool` is verified from a real browser (Q5/Q6), not only curl |
| Two implementations of one rule | ⚠️ `SNAPSHOT_DEPTH` mirrored on the frontend. Mitigated by a backend pin test (R7). The fit clause and the run flag are **shared, not copied** |
| Ask before committing; concurrent sessions | ✅ Work is isolated in its own worktree; nothing is committed without asking |
| Coding subagents run on Sonnet | Applies at `/speckit-implement` |

**Post-design re-check**: unchanged. The design adds one endpoint and client
derivations. The only ⚠️ is mitigated, not waived.

## Project Structure

### Documentation (this feature)

```text
specs/012-draft-pick-insight/
├── spec.md              # amended after planning (see its note)
├── plan.md              # this file
├── research.md          # R1–R10
├── data-model.md        # derived client-side entities, hand-set constants, card state machine
├── quickstart.md        # Q1–Q7 verification guide
├── contracts/
│   ├── pool-endpoint.md # GET /api/drafts/{id}/pool
│   └── ui-live-room.md  # card, scarcity row, room read, preference, feed
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
└── api/LeagueController.java            # + GET /drafts/{id}/pool (scoped via membership.visibleDraft)
backend/src/test/java/com/ballknowers/draftsim/
├── api/LeagueControllerPoolIT.java      # new: scoping, order, clamp, null team
└── engine/MonteCarloRunnerSnapshotDepthTest.java  # new: pins SNAPSHOT_DEPTH = 75 (R7)

web/src/
├── api.ts                               # + getDraftPool
├── pickInsight.ts        (+ .test.ts)   # new: PickInsight derivation, fills/openAfter (shared with feed), summary, ADP delta, model share, likely next
├── scarcity.ts           (+ .test.ts)   # new: starter pool, leftNow, expectedAtNext gate
├── onBrand.ts            (+ .test.ts)   # new: OnBrandRead + verdict rules
├── insightConstants.ts                  # new: hand-set constants, labelled
├── pickCardsPref.ts      (+ .test.ts)   # new: bk.pickCards.v1 read/write (sound.ts pattern)
├── pickRun.ts                           # export RUNNABLE_BY_SPORT (no behaviour change)
├── components/
│   ├── PickInsightCard.tsx (+ .test.tsx)  # new
│   ├── ScarcityMeter.tsx   (+ .test.tsx)  # new
│   ├── OnBrandPanel.tsx    (+ .test.tsx)  # new
│   ├── PickFeed.tsx                       # rows clickable → onPickClick
│   └── SeatPopover.tsx                    # + on-brand read for that seat
├── pages/LiveDraftView.tsx              # landed list from getRealDraftBoard (R3); per-pick background resim with startState + asOf stamp + 429→busy (R1/R2); card lifecycle; meters; preference chip
└── styles (existing stylesheet)         # card, meter, panel; dark card/avatar aesthetic, tinted pills
```

**Structure Decision**: the existing web application layout. The derivations
are pure modules beside `teamNeeds.ts` and `pickRun.ts`, so they can be
unit-tested without React. `LiveDraftView` only wires them up.

## Build order (for /speckit-tasks)

1. **Foundation**: export `RUNNABLE_BY_SPORT`; extract the feed's fit into `pickInsight.fills` and repoint `feedPicks` at it (no behaviour change, existing tests stay green); constants; preference helper.
2. **R3** (landed list from the real board): unblocks every fact-based section and fixes the mid-draft-open fit gap on its own.
3. **Story 1 card** (fit, open slots, summary) with its lifecycle, on-the-clock rule and preference. This is the MVP.
4. **Story 3 ADP tag**: facts only.
5. **R1/R2 per-pick background resim** with startState, asOf stamping and 429→busy. **Measure Q4 here, before building on it.**
6. **Story 2 likely next** and **Story 3 model share**: both need step 5.
7. **`/pool` endpoint + Story 4 scarcity meter**: "left now" first, then the R7-gated projected column.
8. **Story 5 on-brand**: panel, card section, SeatPopover.
9. Adversarial code review, then Q5–Q7 live verification → `verification.md`.

Steps 5–6 carry the risk. If Q4 fails its gate, steps 1–4 and 7–8 still ship
(with "likely next" and "model had this" omitted and a reason given), and R1-alt
becomes its own spec.

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| Per-pick background resim (partial reversal of `41b9426`) | Stories 2, 3 and 4's projected column need a projection that knows about the latest pick | Labelling the baseline's age still shows gone players as "likely next" (R1) |
| Client-side `asOfPick` stamping via explicit `startState` | FR-007 / SC-003 need to know which side of a pick a projection is on | Stamping with `picksMade` at request time is only a lower bound; a server-side field widens a hashed record (R2) |
| New `/pool` endpoint | The starter pool needs board order **with ids** | `/api/board` has no ids or scoping; `availability` rows are truncated at 75 (R6/R7) |

# Implementation Plan: Navigation that doesn't strand you

**Branch**: `003-easier-navigation` | **Date**: 2026-09-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-easier-navigation/spec.md`

## Summary

Make every page the app offers reachable without going through Home, and stop three routes from
deleting the league you are inside.

The complaint that started this ("hard to get to every feature") turns out to be two different
problems wearing one coat, and they want opposite fixes:

1. **A defect.** Three of six league-scoped routes drop league context, because the set of
   league-scoped routes is written down in three places and one copy is missing `analysis`. The rail
   links you to Analysis and then deletes the block that linked you. This is small, cheap, and the
   largest share of the complaint.
2. **A missing capability.** There is no way to reach anything that isn't on the current screen.
   Five leagues × four pages, plus 53 manager profiles, all walked by hand from Home.

So the plan fixes the defect first at its root — one declared destination table, consumed by every
component that needs to know what a league offers — and then builds the palette on top of that
table, so the new capability inherits the fix rather than becoming a fourth copy of the list.

| Phase | Story | Backend? | Ships alone? |
|---|---|---|---|
| 1 | US1 — league context on every league route | no (except mocks) | yes |
| 2 | US1 — mocks link back to their league | **yes** — `V16` + one field | yes |
| 3 | US2 — the jump-to palette | no | yes |
| 4 | US3 — league switcher + collapsed-rail seasons | no | yes |
| 5 | US4 — phone return affordance | no | yes |

Phase 2 is separated from Phase 1 precisely because it is the only part of US1 that needs a
migration; separating it keeps the pure-frontend fix shippable the same day. Phases 4 and 5 are thin
once Phase 3 exists — the switcher and the phone affordance are both consumers of the palette's
table and the palette's overlay.

**The one structural decision**: a single `destinations.ts` module owns *what pages exist for a
league, and which of them a given sport offers*. Today that knowledge is split across `App.tsx`'s
route list, `railLeague.ts`'s regex and `LeagueRailSection.tsx`'s JSX, and they already disagree —
that disagreement is bug #1. The palette, the switcher and the rail all need the same answer, so
adding them without consolidating first would take three copies to six.

## Technical Context

**Language/Version**: TypeScript 5.7 (frontend), Java 21 (backend — Phase 2 only)

**Primary Dependencies**: React 18.3 + React Router 6.30, Vite 6; Spring Boot 3.5.5
(`starter-web`, `starter-jdbc` — `JdbcClient`, no JPA), Flyway. **No new dependencies** — the
palette is hand-rolled (research.md R3).

**Storage**: PostgreSQL 17. One new column in Phase 2: `mock_draft_session.source_sleeper_league_id`.
Current migration head is `V15__player_projection.sql`, so this feature's migration is `V16`.

**Testing**: Vitest + Testing Library for the frontend; JUnit via `spring-boot-starter-test` for the
backend, with `*IT` integration tests that require Postgres. **Note**: the backend suite reports
`BUILD SUCCESSFUL` with ~52 tests silently skipped when Postgres is down — check the skip count, not
just the exit code, when verifying Phase 2.

**Target Platform**: Web. Deployed on Railway as split services that deploy independently, so
"new frontend, old backend" is a state every rollout passes through — Phase 2's client code must
tolerate the field being absent.

**Project Type**: Web application — `backend/` (Spring Boot) + `web/` (React SPA)

**Performance Goals**: Opening the palette issues no network request on a warm cache (NFR-001). The
rail continues to fetch `/api/drafts` at most once per session (NFR-002, currently guaranteed by the
module-scope cache in `railLeague.ts`).

**Constraints**: Nothing may take horizontal space in a draft room — a 14-team board already
overflows a 1440px viewport by 99px with no rail at all, and one column is 96px (NFR-004). Nothing
may add a permanent viewport band at phone width (NFR-010, research.md R6).

**Scale/Scope**: 5 leagues, ~9 league-seasons, 53 managers, 12 routes. The search index is a few
hundred rows held in memory.

## Constitution Check

`.specify/memory/constitution.md` is **an unfilled template** — every principle is still
`[PRINCIPLE_N_NAME]` / `[PRINCIPLE_N_DESCRIPTION]` placeholder text. There is no ratified rule for
this plan to be checked against, so this gate cannot pass or fail on its own terms, and recording it
as "PASS" would be a false signal.

Stating it plainly instead: **gate skipped, no constitution ratified.** Filling it is out of scope
for a navigation feature, but it is worth doing before a plan is written that a real principle would
have changed. In its place, this plan is checked against the conventions the codebase actually
enforces in review, which are visible in its own comments:

| Convention (observed) | This plan |
|---|---|
| One rule, one implementation — the multi-sport landmine class | The whole point of `destinations.ts`; see research.md R1/R5 |
| No optional parameter that silently encodes a rule | Destination availability is an explicit field, never a default |
| Decisions carry their measurement in a comment | R6 quotes and preserves the existing 252px/812px mobile trade |
| Degrade a field, never the page, across split deploys | Phase 2's client treats the new field as absent-tolerant |
| Tests alongside the change, integration tests where Postgres is involved | Phase 2 adds an `*IT`; Phases 1/3/4 add Vitest specs |

**Post-design re-check**: unchanged. The design adds one frontend module, one column and one
response field; it introduces no new service, no new endpoint and no new dependency.

## Project Structure

### Documentation (this feature)

```
specs/003-easier-navigation/
├── spec.md              # feature specification
├── plan.md              # this file
├── research.md          # Phase 0 — measured findings and decisions
├── data-model.md        # Phase 1 — Destination, LeagueContext, SearchIndex
├── contracts/
│   ├── destinations.md  # the internal module contract every consumer reads
│   └── mock-session.md  # the one API change (Phase 2)
└── quickstart.md        # how to verify each phase
```

### Source (files this feature touches)

```
web/src/
├── destinations.ts            # NEW — the declared table (R1, R5)
├── destinations.test.ts       # NEW
├── railLeague.ts              # CHANGED — matcher derives from destinations.ts
├── searchIndex.ts             # NEW — flattens leagues/seasons/managers (R4)
├── searchIndex.test.ts        # NEW
├── components/
│   ├── JumpTo.tsx             # NEW — the palette overlay
│   ├── JumpTo.test.tsx        # NEW
│   ├── LeagueRailSection.tsx  # CHANGED — renders from destinations.ts; season links; switcher
│   ├── Rail.tsx               # CHANGED — palette entry point
│   └── AppShell.tsx           # CHANGED — hosts the overlay, owns the shortcut
├── pages/
│   ├── ManagerTendencies.tsx  # CHANGED — link each row to its profile (FR-007)
│   └── MockDraftView.tsx      # CHANGED — surfaces source league id for the rail
├── api.ts                     # CHANGED — MockSessionState.sourceSleeperLeagueId
└── styles.css                 # CHANGED — overlay, switcher, phone affordance

backend/src/main/
├── resources/db/migration/V16__mock_source_league_id.sql   # NEW
└── java/com/ballknowers/draftsim/
    ├── mock/MockDraftService.java        # CHANGED — persist the id it already holds
    ├── store/MockDraftRepository.java    # CHANGED — column in insert + row record
    └── api/MockDraftController.java      # CHANGED — field on the response
```

**Structure Decision**: Web application, existing `backend/` + `web/` split. No new top-level
directories. `destinations.ts` and `searchIndex.ts` sit at `web/src/` root beside `railLeague.ts`
and `leagueLineage.ts`, which are the existing peers doing the same kind of job (URL and data
derivation shared by several components, outside `<Routes>`).

## Phase detail

### Phase 1 — League context on every league route (US1, P1)

**Root cause**: research.md R1. Three copies of "what routes belong to a league", one missing entry.

1. Add `web/src/destinations.ts` declaring, once, the destinations a league has: key, label, glyph,
   URL builder, the sports that offer it, and the route pattern that identifies it. Analysis carries
   `sports: ['nfl']` with the existing football-only reasoning moved into a comment beside it, not
   duplicated in JSX.
2. Rewrite `leagueRefFromPath` to test against the patterns in that table rather than a literal
   alternation. It stays a hand-written matcher — `useParams` is unavailable outside `<Routes>` and
   the shell must stay there (`AppShell.tsx:72`).
3. Extend the matcher to the two non-`/leagues/` shapes: `/managers/:id/history` (league taken from
   navigation state when arriving from a standings table, else no context) and `/mock/:id`
   (Phase 2 supplies the id; until then it resolves to nothing, which is the current behaviour).
4. Render `LeagueRailSection`'s rows by mapping the table, so the rail's links and the matcher can no
   longer disagree.

**Test**: a table-driven spec asserting every destination's URL is matched by the matcher, and a
rail test per route shape asserting the League section renders and marks the right row. The
table-driven half is what makes the next added page fail loudly instead of silently.

**Ships alone**: yes. Fixes Analysis and manager-history context with no backend and no new UI.

### Phase 2 — Mocks remember their league (US1, P1 — backend)

**Root cause**: research.md R2. The id is in hand at `MockDraftService.java:146` and discarded three
lines later.

1. `V16__mock_source_league_id.sql`: `alter table mock_draft_session add column
   source_sleeper_league_id text;` — nullable, no backfill.
2. `MockDraftService` passes the id it already validated to `createSession` alongside the name.
3. `MockDraftRepository` writes and reads the column; the row record gains the field.
4. `MockSessionState` gains `sourceSleeperLeagueId?: string | null`, **optional on the client**, so a
   new frontend against an old backend shows no league context rather than throwing. This is not
   defensive habit: `api.ts:579` records the 2026-09-14 outage where exactly this pattern took the
   home screen to a white page.

**Explicitly not doing**: backfilling old mocks by matching `source_league_name`. Names are neither
unique nor stable, and the id path's membership check would be lost. Existing mocks keep today's
behaviour, which FR-003 permits.

**Test**: an `*IT` asserting a mock created with `sourceSleeperLeagueId` round-trips it, and one
asserting a mock created without one reads back null. **Check the skip count** — the suite reports
success with ~52 skipped when Postgres is down.

**Ships alone**: yes, and should ship backend-first given split deploys.

### Phase 3 — The jump-to palette (US2, P2)

1. `searchIndex.ts` builds a flat `Destination[]` from the cached `getDrafts()` response plus
   `getManagers()` for **both** sports, merged on manager identity (research.md R4 — a single-sport
   call listed only football managers, and ten of twelve Ball Knowers managers are the same Sleeper
   user in both sports).
2. `JumpTo.tsx` renders the overlay: input, grouped results (Leagues / Pages / Managers), keyboard
   navigation, Escape-to-close with focus restoration.
3. `AppShell` owns the open state and the `Ctrl/Cmd+K` handler; `Rail` gets a visible entry point so
   the feature is discoverable without the shortcut (FR-004 requires both).
4. Results are labelled with their league and, for managers, their sport pill, so two leagues'
   "History" are distinguishable (FR-005). Availability comes from the Phase 1 table, so a basketball
   league is never offered Analysis (FR-006).

**Test**: index-building is a pure function with its own spec (both-sports merge, season
distinctness, sport availability). The overlay gets a keyboard-only interaction test (NFR-003) and a
test asserting no fetch on a warm cache (NFR-001).

**Ships alone**: yes. Phase 1 should precede it so that arriving somewhere leaves you navigable.

### Phase 4 — League switcher and collapsed-rail seasons (US3, P3)

1. The rail's league crest becomes a switcher listing the user's other leagues. Choosing one keeps
   the current destination if the target sport offers it, else falls back to that league's History
   (FR-008) — the fallback rule reads the Phase 1 table rather than restating the sport gate.
2. Seasons become reachable while collapsed (FR-009) without widening the rail — NFR-004 is
   non-negotiable in a draft room. The collapsed presentation is a flyout from the crest, not a
   column.
3. `ManagerTendencies.tsx` links each row to `/managers/:id/history` (FR-007) — the one-line gap from
   research.md R7.

**Ships alone**: yes, though it is largely redundant with Phase 3 for keyboard users. It exists for
navigation-by-pointing.

### Phase 5 — Phone return affordance (US4, P4)

A fixed bottom-corner button at phone width only, opening the Phase 3 overlay. Nothing is pinned;
nothing gains a permanent band. Research R6 explains at length why the existing scroll-away trade is
kept rather than reversed — this phase solves only the return trip, and is nearly free once Phase 3
exists.

## Risks

| Risk | Handling |
|---|---|
| Split deploy shows a frontend expecting `sourceSleeperLeagueId` to an old backend | Field optional on the client; absent → no league context, never a throw. Ship backend first. |
| Consolidating three lists into one changes rail behaviour on a route nobody tested | Phase 1's table-driven test covers every destination × every route shape before the rail changes. |
| Palette shortcut collides with a draft room input | `Ctrl/Cmd+K` chosen over `/` for this reason (research.md R3). Overlay must not open while a text input has focus without the modifier. |
| Manager merge across sports double-lists the same person | Merge on manager identity, not name; ten of twelve Ball Knowers managers are the same Sleeper user in both sports and must appear once. |
| Mobile affordance quietly reintroduces the 252px band | NFR-010 states it; Phase 5 adds a button, not a bar. |

## Progress Tracking

- [x] Phase 0 — research complete, no `NEEDS CLARIFICATION` remaining ([research.md](./research.md))
- [x] Phase 1 — design artifacts ([data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md))
- [x] Constitution check — **skipped, no constitution ratified**; checked against observed conventions instead
- [x] Post-design constitution re-check — unchanged
- [ ] `/speckit-tasks` — generate `tasks.md`
- [ ] Implementation

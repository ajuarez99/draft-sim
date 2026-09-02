# draft-sim — handoff

Last updated 2026-09-02 by a Claude session running directly on Allan's Windows
machine. **Read this first.** `DEPLOY.md` covers deployment; `README.md` covers
running it. A Claude session should then read `claude/` — orientation, sandbox
recipes and bug post-mortems that are not worth rediscovering.
`claude/environment.md`'s recipes are written for a different (cloud-sandbox)
environment; this machine has real internet, real JDK/Node/Postgres installs,
and does not need most of them — see the new note at the top of that file
before assuming something is blocked.

**~~Time-sensitive: fantasy(heart)'s draft is 2026-08-31 21:15 CDT.~~ That draft
has happened.** It is `complete` in the DB with all 210 picks. Nothing in this
repo is on a deadline any more, and fantasy(heart) can no longer be used as a
simulation target — it replays 210 real picks and simulates nothing. Point sims
at **West Coast FF 2026** (`1389361939561332737`, 14 teams, `pre_draft`) instead.

**Where the roadmap stands: `claude/next-features-roadmap.md` now opens with a
status table, and that table is now out of date.** D's poller, B's app shell, and
A's normalization + the shared `DraftContextFactory` were already built. As of
2026-09-02 **Phase 4 (D's live frontend) is built but unverified** — see "Stopped
here" below. **C's interactive mock draft room (Phase 3) is planned in detail and
not started**, and is the next real feature; A's ad-hoc league sizing stays last.
The roadmap's claim that Phase 4 is "pure UI polish, not new risk" was wrong: it
opened with a correctness fix in the ingest/poll path.

**~~Read before touching profile output:~~ FIXED 2026-09-02** — a league ingest
run after a board rebuild used to silently zero every fitted manager profile.
See "Known live bug" below, kept as a record of what it looked like.

**Draft-night bug-fix batch, 2026-09-02.** Eight fixes ahead of
West Coast FF 2026 going live — the first truly `drafting`-status draft this
codebase has ever seen. See "2026-09-02: pre-draft-night bug batch" below.

**Several Claude sessions often run against this one working tree, DB and
server at once.** Two of them landed work on 2026-09-01 within minutes of each
other. Check `git status` before you start, and if you are taking a before/after
measurement, boot your own build on a second port and run both sides back to
back — the shared DB moves under you otherwise.

---

## 2026-09-02: pre-draft-night bug batch (committed, suite ended at 174/174 green)

Eight bugs found by a review pass, each of which would have broken or degraded
tonight's live draft. Nothing new was built. Backend suite went 137 -> 157
tests, 0 skipped (the ITs ran against the real Postgres on 5433 and
`DifferentialReplayIT` against the real Sleeper API), `npx tsc -b` clean.

1. **`LiveDraftPoller` busy-looped on any error.** `Thread.sleep` was the last
   statement *inside* the try, so an exception out of `pollOnce` skipped it and
   the loop re-entered instantly — an unthrottled hammer on api.sleeper.app,
   which trips Sleeper's rate limit, which throws, which sustains the loop. The
   sleep now lives outside the try, with a capped 6x backoff on consecutive
   failures.
2. **The poller dropped the final picks of a draft.** It returned on `complete`
   *before* fetching picks, so anything drafted between the last `drafting`
   tick and the draft closing was permanently missing from `draft_pick`. It now
   ingests once on the way out (`upsertPicks` is idempotent).
3. **The seat map was frozen at `/track` time.** Sleeper returns
   `"draft_order": null` until the commissioner sets the order — verified live
   against West Coast FF 2026 — so `LeagueIngestService` persisted an **empty**
   map for it and `/api/drafts/.../seats` came back `"seats": []`. Every
   autopick (Sleeper leaves `picked_by` blank) would have landed with
   `manager_id null`, been filtered out of `allCompletedPicks`, and left all 14
   seats simulating as league-average bots all night. The poller now re-derives
   slot->manager from the draft object it already fetches, **every tick,
   `pre_draft` included**, and persists it via a new
   `DraftRepository.updateSlotToManager`. The inversion is extracted into
   `ingest/DraftOrderMapper` and shared with `LeagueIngestService` (mirroring
   how `PickMapper` was extracted).
   **Corrected 2026-09-02 by a review pass:** this used to say
   "`DifferentialReplayIT` is the proof that extraction is behaviour-preserving."
   It is not. `DifferentialReplayIT.java:103-105` hand-builds its `slotLookup`
   from `draftRow.slotToManager()` — which `DraftOrderMapper` itself has just
   written via `ingestChain`. Both sides of the "differential" therefore derive
   from the same new code, so the test proves `Integer.parseInt` round-trips,
   not equivalence with the deleted inline loop. What actually covers the
   extraction is `DraftOrderMapperTest` plus `LiveDraftPollerTest`'s seat-map
   cases; `DifferentialReplayIT` remains a real test of the *pick-mapping*
   pipeline against live Sleeper data, which is what it was originally for.
4. **`/track` could 500, and lied about status.** `Map.of` throws NPE on a null
   value and `ErrorHandler` doesn't catch NPE. Rebuilt on `LinkedHashMap`. It
   also reported the *stored* status, so it would say "pre_draft" for a draft
   live for an hour — `track()` now runs one tick synchronously first and
   returns the freshly-observed `status`, plus `seatsMapped`/`teams` as a
   draft-night diagnostic. **Check `seatsMapped` before 8:15 PM: anything less
   than 14 means seats are unattributed bots.**
   **Caveat, added 2026-09-02:** West Coast FF 2026's `draft_order` was still
   `null` as of 01:50 CDT, so `seatsMapped: 0` is *expected* until the
   commissioner sets the draft order — it is not yet a failure. It becomes one
   if it is still 0 close to 8:15 PM. The poller now logs an ERROR the first
   time it sees zero mapped seats (it used to return early past the logging
   block, so the total-failure case was the one case that said nothing).
   The response also carries `"observed"`: false means the status came from the
   DB because the synchronous Sleeper tick threw, not from Sleeper.
5. **The picker screen white-screened on a null status.** `DraftSummary.status`
   was typed non-null while the column is nullable.
6. / 7. **Availability/picker filtering and a duplicate-player hole.** The
   revealed-board filter used `revealedThrough`, which equals `pausedAt` while
   paused, so the panel dropped the board's predicted player at your own
   undecided pick. `PlayerPicker` was still filtered on your own roster only, so
   it listed players the board showed as gone; taking one wrote a duplicate into
   `startState` and **`DraftSimulator` accepted it silently** — `available.remove(e)`'s
   return value was ignored, so the removal no-opped while the roster add ran
   and the player was double-counted in `rosterNeed`. Both halves fixed.
8. **`adp_at_time` null-wipe — closed, see below.**

### Second pass, same day: review fixes + two new endpoints

Six more findings from a code review of the batch above, plus the backend half of
Phase 4's live UI. All still uncommitted.

- `refreshSeatMap` was silent in exactly the case it exists to detect (0 seats
  mapped returned early past the logging block). Now an ERROR, rate-limited
  through the same `lastSeatMap` mechanism so it fires once, not 360 times an hour.
- `/track` reports `observed` — false when the synchronous tick threw and the
  status is the stale DB value.
- `/track` no longer runs a second full tick when the draft is already tracked.
  It used to `sleeper.draft` + `sleeper.draftPicks` + a 210-row upsert on the
  Tomcat request thread on every call, racing the poll loop — and `/track` is the
  only way to read `seatsMapped`, so refreshing it by hand doubled Sleeper load
  mid-draft. `trackCalledTwiceStartsExactlyOnePollerAndFetchesSleeperOnlyOnce`
  now asserts the fetch count, which is what made this invisible.
- `/track` on a `complete` draft used to answer `"tracking": true,
  "alreadyTracking": true` for a draft nothing was polling. `TrackResult` carries
  `pollerRunning` now.
- `LeagueIngestService.backfillAdpAtTime` moved out of the ingest transaction. It
  is a global `UPDATE draft_pick` and was taking row write-locks on tonight's
  picks and holding them across Sleeper HTTP calls until commit, while the poller
  upserts the same rows every 10s. **Note:** the reviewer's suggested
  `REQUIRES_NEW` would have been worse — it suspends the outer transaction but
  does not release the locks it already holds, so the inner one would block on
  its own caller. Done with an explicit `TransactionTemplate` instead.
- `DraftSimulator` logs a WARN (once per player per process, not per iteration)
  when a `startState` duplicate is dropped.
- **`GET /api/drafts/{id}/live-stream`** — SSE, GET so the browser's native
  `EventSource` drives it and reconnects for free. `state` on connect and on
  every tick where status/picksMade/seatsMapped changed, `heartbeat` every 15s
  regardless, `error`. Driven off the existing poll loop via a listener registry
  on `LiveDraftPoller`, **not** a second polling loop. Auto-`track()`s on open.
- **`POST /api/drafts/{id}/picks`** `{"pickNo": 38, "sleeperPlayerId": "4046"}` —
  the manual escape hatch when the poller lags a pick already visible in Sleeper.
  400 on an unknown player id rather than a silent `player_id = null`. Self-heals:
  the poller overwrites the row with truth on its next tick.
- **Not verified live.** Everything here is unit-tested only; nothing in this
  pass has been driven against a running server or a real `drafting` draft.

### Third pass, same day: the frontend half of Phase 4

- **`/drafts/:draftId/live`** is a real route now. `web/src/App.tsx:7-8` reserved
  it in a *comment* only — the roadmap called it "reserved," which was wrong.
  It goes through a `KeyedLiveDraftView` remount wrapper for the same reason
  `KeyedDraftView` exists: an in-flight resim for the old draft must not paint
  onto a new one.
- **`web/src/useLiveDraft.ts`** — native `EventSource` against `/live-stream`.
  Closes itself on `status === 'complete'`: `EventSource` auto-reconnects after a
  server-side close, and the reconnected request would see `complete` and close
  again, forever, every ~3s. Last-contact time lives in a ref, not state, so a
  15s heartbeat doesn't re-render the whole view.
- **`web/src/components/LiveStatusBar.tsx`** — status pill, on-the-clock seat
  (the largest element, `hueFor(managerId)` so it is the same identity colour the
  board uses), `picksMade/totalPicks` over the existing `.progress` track, and a
  freshness pill: `live · 4s` teal under 25s, `stale · 1m 40s` crimson past it.
  **That pill is the point of the component** — it is what tells you the backend
  died rather than the draft going quiet.
- **`web/src/pages/LiveDraftView.tsx`** — reuses `DraftBoard` unmodified, feeding
  `revealedThrough={live.picksMade}`: reality rather than an animation timer. The
  roadmap's §3.5 asked for a new `landed: boolean` field on the board-cell type;
  that is stale, `revealedThrough` already does the job and no `DraftBoard` edit
  was needed. Resim on a new live pick reuses `startState`-free DB replay
  (`SimulationService.resolveStartState` already falls back to the poller's own
  rows), 1.5s trailing debounce, coalesced not cancelled.
- **Picker screen** grew a `track` chip and a `live →` link as *siblings* of the
  row `<Link>` — a `<button>` cannot nest inside an `<a>`, and
  `.draft-row .chip { pointer-events: none }` would have killed a chip placed
  inside it. The chip shows `seatsMapped/teams` inline, crimson at 0, and marks
  the reading `· stale` when `observed === false`.
- Added `.chip.status-pre_draft`. There was no rule for it, so the picker's most
  common chip had been falling through to the base `.chip` by accident.

---

## Stopped here — read this before continuing

**This session ended mid-flight, deliberately.** Two agents were building the
backend and frontend halves of Phase 4 in parallel and were stopped partway.
What is committed compiles and passes, but is **not finished and not verified
live**:

- `./gradlew test` — **174 tests, 0 failures, 0 skipped** (the ITs really ran
  against Postgres on 5433 and the real Sleeper API, they did not skip).
- `npx tsc -b` clean, `vite build` clean.
- **Nothing in Phase 4 has been driven against a running server.** The backend on
  8080 was deliberately left running pre-batch bytecode so other concurrent
  sessions were not disrupted, which means `/live-stream`, `/picks`, the live
  route and the whole SSE path are **unit-tested only**. Nobody has watched a
  `state` event arrive in a browser.
- The planned **test/verification agent pass never ran.** Same gap as the
  original poller work — see "This session: live draft poller built" below,
  which records the same omission for the same reason.

### If you are picking this up before the draft

West Coast FF 2026 (`1389361939561332737`, 14 teams) starts **2026-09-03
01:15 UTC = 2026-09-02 20:15 CDT**. In order:

1. **Restart the backend** — `bootRun` does not hot-reload, and everything above
   is inert until you do. The poller keeps no persisted tracking state, so a
   restart also silently un-tracks every draft.
2. `curl -X POST localhost:8080/api/drafts/1389361939561332737/track` and read
   **`seatsMapped`**. That one number is the draft-night health check.
3. **`draft_order` was still `null` as of 2026-09-02 01:50 CDT**, verified
   directly against Sleeper. It stays null until the commissioner sets or
   randomizes the order, and *nothing* — not this app, not Sleeper's own UI —
   can know your slot before then. `seatsMapped: 0` is EXPECTED until that
   happens, not a bug. Re-check every ~30 min from 6 PM; chase the commissioner
   if it is still null at 7:45 PM.
   - A fallback via `slot_to_roster_id` was considered and **rejected on
     evidence**: all four completed drafts in the DB carry a populated
     `draft_order` *and* a real `slot_to_roster_id`, while West Coast has null
     and an identity placeholder. The two fields fill in together, so the
     fallback would buy nothing.
4. **Do not run `POST /api/ingest/all` or `/api/ingest/league` while the poller
   is live.** Both call `replacePicks`, and the board rebuild moves the pool
   under a running draft.
5. Dry run before 8:15 if there is time: the checklist wants a *real* throwaway
   Sleeper league, not a public mock — `ingestChain` walks `previous_league_id`
   from a **league** id and a public mock may carry none. Verify with
   `curl -s https://api.sleeper.app/v1/draft/<ID> | grep league_id` first.
   **Delete the throwaway league before the real draft** — it finishes
   `complete`, so its picks enter `allCompletedPicks()` and get fitted into the
   manager profiles you are about to draft against.

### Then: Phase 3, the mock draft room

Planned in detail this session but **not started**. Two corrections to
`claude/next-features-roadmap.md`'s §4 Phase 3 that matter before anyone builds it:

- **Its central instruction is wrong.** It says to extract
  `DraftSimulator.choose()` into a reusable decide-and-apply unit. `choose()`
  deliberately reads and writes six instance-level scratch buffers allocated once
  in the constructor — that reuse is part of the ~20-30x speedup in `be423eb` —
  and it returns an *index into `available`*, not a `BoardEntry`, so it cannot be
  the shared unit's return type. It is also not the "apply" half at all; that
  lives in `run()`. The reusable unit is **the mutable per-draft state**: a new
  `DraftState` class plus a public `DraftSimulator.advance(state)`, with
  `choose()` becoming a private `chooseIndex(state)` and every buffer staying put.
  Acceptance is seeded-RNG output **byte-identical** before and after.
- **"Not started" overstates it.** Reactive resimulation already shipped a
  client-driven version of the user-visible flow — `choosePick()` in `DraftView`,
  `PickPrompt`, `PlayerPicker`, `useRevealedBoard`. Phase 3 is really: give that
  persistence, a real per-pick bot decision path, and an entry point that does
  not need an ingested Sleeper draft. Do not rebuild those components.

`V2__ffc_adp.sql` is the highest migration on disk, so the mock tables are a
genuine **V3**. Build the profile-contamination guard as **two** tests, not one:
the real-Postgres IT plus a no-database source-level check, because the IT
silently skips on any machine without a database and that is exactly the machine
where someone will add the join.

---

## ~~Known live bug~~ — fitted profiles are silently switched off (FIXED 2026-09-02)

Both halves are now closed. `DraftRepository.replacePicks` no longer deletes and
re-inserts: it prunes only the picks missing from the incoming list and hands the
rest to `upsertPicks`, whose `coalesce(draft_pick.adp_at_time, excluded.adp_at_time)`
already got this right. And `LeagueIngestService.ingestChain` now calls
`BoardService.backfillAdpAtTime` (made public) at the end, so a fresh ingest's own
picks are scoreable without remembering to `POST /api/ingest/board`. Coalescing a
stale value is safe because the backfill overwrites unconditionally rather than
only filling nulls. Pinned by `DraftRepositoryUpsertPicksIT.replacePicksPreservesABackfilledAdpAtTime`.

The original description follows, because the *symptom* is worth recognising:

### What it used to look like

`DraftRepository.replacePicks` (any league ingest) reinserts picks with
`adp_at_time = null`. Only `BoardService.backfillAdpAtTime`, at the end of a
board rebuild, puts it back. Ingest after a rebuild therefore leaves zero
scoreable picks, and every manager comes back `NEUTRAL` with `picksScored: 0`
while `/api/managers` still returns full-looking profiles. Found in exactly
this state on 2026-09-01, and it is invisible unless you look for it.

Check it:

    curl localhost:8080/api/board | head -c 120     # picksWithContemporaneousBoard
    # or: select count(adp_at_time) from draft_pick;

Zero used to mean re-run `POST /api/ingest/board`. Both durable fixes are now in
(coalesce on replace, and backfill at the end of a league ingest), so a zero here
now means something genuinely new — investigate rather than papering over it with
a rebuild.

---

## Where things actually stand

Repo `ajuarez99/draft-sim`, branch `main`. Working tree has uncommitted work from
this session (below) — **not yet committed, ask before committing.** Multiple
Claude Code sessions have been running concurrently against this same working
directory (one on this live-poller work, others on separate frontend work per
`git status`) — if you're picking this up, diff carefully before assuming the
working tree matches what this doc describes.

    f8df93d  Designs in and future work that needs to be done
    795975b  frontend: pick-by-pick board reveal, manager tendencies UI
    c4bf479  Add in simulation tests and booting up app locally first time
    4b5da75  claude/environment: record the UI screenshot and slf4j-stub recipes
    3d65302  fix: coherent predicted board, stale frontend types, memo deps
    39f4a72  frontend: seat cards branch on provenance, not pick count
    e58094a  handoff: point at claude/ instead of duplicating environment notes
    3297ee2  add claude/ notes: orientation, sandbox recipes, bug post-mortems
    76d661d  manager tendencies: stated beliefs as shrinkage prior, per-seat unpredictability
    e7ea1a1  add HANDOFF.md
    0b54d2d  deploy prep: env-var config, shared-token auth, Dockerfile, runbook
    ef17f3e  fix: backfillAdpAtTime UPDATE..FROM join, createArrayOf binding
    77f34bb  v0: ingest, board derivation, engine, Monte Carlo, SSE, React UI

### This session: live draft poller built (D, Phase 0 of `claude/next-features-roadmap.md`)

`claude/next-features-roadmap.md` (written 2026-08-29, reconciling four parallel
feature plans) prioritized D — live Sleeper draft polling — first, ahead of the
other three features, specifically because of tomorrow's deadline. Built this
session via a plan → build → code-review pipeline (three separate agent passes);
full design and reasoning in `claude/live-poller-plan.md`. Summary:

- `LiveDraftPoller` (new) — one virtual thread per tracked draft, polls Sleeper's
  `/draft/{id}` + `/draft/{id}/picks` every 10s while `status` is `drafting`,
  writes into the existing `draft`/`draft_pick` tables via a new
  `DraftRepository.upsertPicks()` (idempotent `ON CONFLICT` upsert, replacing the
  need for the destructive `replacePicks()` on the polling path).
- `POST /api/drafts/{sleeperDraftId}/track` (new, on `LeagueController`) starts
  tracking; safe to call any time before the draft goes live (no-ops on
  `pre_draft`), idempotent if called twice.
- `PickMapper` (new) — the raw-Sleeper-pick-to-`PickRow` mapping logic extracted
  out of `LeagueIngestService.ingestDraft` so both the batch ingest path and the
  poller share one implementation instead of two.
- Tests: 14 new (`PickMapperTest`, `LiveDraftPollerTest` with mocked
  `SleeperClient`/repositories, `DraftRepositoryUpsertPicksIT` and
  `DifferentialReplayIT` against a real Postgres + real Sleeper API, gated to
  skip cleanly with no Postgres reachable). Full suite: **74/74 passing**,
  verified independently by both the build pass and a separate code-review pass
  (which also killed Postgres mid-run to confirm the integration tests actually
  skip rather than silently pass).
- **What was NOT independently re-verified**: a fourth "test and verify" agent
  pass (booting the real server, hitting `/track` live, checking logs, clean
  shutdown) was planned but not run this session — Allan reviewed the plan +
  build + code-review results and judged them sufficient, and moved on to
  starting the next roadmap phase in a separate session. The one thing genuinely
  unverified by any of this — per `claude/live-poller-plan.md`'s own "Open
  risks" — is real `drafting`-status behavior; nothing in this codebase has ever
  observed a truly live Sleeper draft. Budget the disposable-mock-draft dry run
  in the manual checklist before trusting this against fantasy(heart) tomorrow.
- **Still uncommitted.** Ask before committing — this doc may be read while that
  decision is still pending.

**The application has been started, for the first time, this session — end to end,
against real Sleeper data, through the real UI.** Everything below labelled
"verified" was verified by actually running it, not by inspection.

### Verified by actually executing it, this session

| What | How |
|---|---|
| Spring context, bean wiring, Flyway migration, config binding, Jackson, pgjdbc | `./gradlew bootRun` against a real local PostgreSQL. Boots clean, `GET /api/health` returns `weightsLoaded: true` |
| Full backend test suite, 60 assertions | `./gradlew test`, real JUnit under real JDK 21 (Gradle toolchain auto-provisioned it — see "Build tooling" below) |
| FFC ADP source, live | Real ingest against fantasy(heart)'s board: 271/271 players matched, board rebuilt, re-simulated. See "Real ADP import" below |
| Real Sleeper ingest, all 4 leagues in "League facts" below | `POST /api/ingest/all/{leagueId}` — found and fixed a real bug, see "Bugs fixed this session" |
| The board, by eye | `GET /api/board?limit=40` after ingest — top of the board is Bijan/Gibbs/Allen/Chase-tier, reads like a real 2026 first round |
| `picksWithContemporaneousBoard` | 180, matching the ~180 HANDOFF predicted |
| A real 2000-iteration simulation, T=1.0, fantasy(heart) slot 11 | `POST /api/sims` — see "First real simulation" below for both the pass and the open question it surfaced |
| Availability monotonicity across a manager's own picks | Checked over the real 2000-iter run (0 violations / 1292 pairs) **and** now pinned by a new `MonteCarloRunnerTest` — this was flagged in HANDOFF as untested; it no longer is |
| Frontend build | `npm install`, `tsc -b`, `vite build` clean under `strict` |
| Frontend against the real backend, in a real browser | Not the mock-server workaround — `vite dev` proxied to the live `bootRun` instance, real ingested data. Seat cards, predicted board, availability panel, SSE progress streaming all confirmed correct. Zero console errors |
| SSE parser, 19 assertions | `api.ts` bundled with esbuild, run against a mock Node SSE server at chunk sizes 1, 7, 64, 100000 |
| Schema + every repository query | Applied to a real Postgres; each query re-run as `PREPARE`/`EXECUTE` so bind params behave as JDBC drives them |
| Manager tendencies, 53 assertions total | Stated-as-prior blending, clamping, per-seat unpredictability |

### Bugs fixed this session

1. **`DraftRepository.upsert` timestamp binding.** Bound a `java.sql.Timestamp`
   against `Types.TIMESTAMP_WITH_TIMEZONE` for the `timestamptz` `start_time`
   column. pgjdbc rejects this outright: *"Cannot cast an instance of
   java.sql.Timestamp to type Types.TIMESTAMP_WITH_TIMEZONE."* First real ingest
   call, 500 every time. Fixed by binding `OffsetDateTime.ofInstant(startTime,
   ZoneOffset.UTC)` instead — same class of bug as the `text[]` binding fixed in
   `ef17f3e`, caught the same way: by actually calling it.
2. **`FfcClient` — FFC serves valid JSON as `Content-Type: text/html`.**
   Confirmed with plain `curl -D -`, so it's genuinely the server, not Spring.
   `RestClient.body(Map.class)` refuses to parse JSON out of a body declared
   `text/html`. Fixed by fetching as `String` and parsing with Jackson
   directly. See `claude/lessons.md` #11.
3. **`LeagueController.board()` / `SimulationController` / `ErrorHandler` —
   `String.valueOf(null)` is `"null"`, not null.** `Map.of()` rejects a null
   value outright, and these all used `String.valueOf(...)` to route around
   that for a genuinely-nullable field (a player's team; an exception's
   message). It doesn't throw, but the literal four-character string `"null"`
   is valid JSON and indistinguishable from real data to a consumer that isn't
   specifically checking. Found via a real retired player (Todd Gurley, still
   sitting in Sleeper's static dump) surfacing a `"team":"null"` in the live
   board response. Fixed: board uses a mutable map so the field can be a real
   `null`; the error paths fall back to the exception's class name instead of
   a null message, which is more useful than either. See `claude/lessons.md`
   #12.
4. **`DraftSimulatorTest.higherTemperatureProducesMoreVariedBoards` was flaky by
   construction**, not by chance. It counted distinct boards out of 25 trials,
   which saturates at 25 the moment every trial differs from every other — and at
   this pool size that happens well before T=0.2. Both T=3.0 and T=0.2 hit the
   ceiling (25/25), so the assertion compared 25 to 25 and failed. Rewritten to
   measure average Hamming distance from the T=0 modal board instead, which keeps
   discriminating past the point a distinct-count ceiling stops being able to.
   Same lesson as `claude/lessons.md` #7: a statistic can be well-formed and still
   be the wrong thing to assert on.

### Build tooling note

No JDK 21 was preinstalled on this machine (only JDK 24) and the Gradle wrapper
was never committed. Fixed both: added the `foojay-resolver-convention` plugin to
`settings.gradle.kts` (lets Gradle auto-provision JDK 21 via its toolchain, no
manual JDK install needed) and generated + this-session-only-staged the wrapper
files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) — **not yet committed**, they
should be, since HANDOFF's own "get it running" instructions assume they exist.

Local Postgres: this machine has a Postgres 14 service already running on 5432
for other things. Rather than touch it or need its credentials, this session
initialized a **throwaway** Postgres 14 cluster in a temp dir, listening on 5433
with trust auth for user/db `draftsim` — which happens to match
`application.yml`'s local-dev defaults exactly. That data directory will not
survive a reboot; `docker compose up -d` (once Docker is available on this
machine — it currently is not) or a real local install is still the durable
answer. See `claude/environment.md` for the exact commands used.

### First real simulation — the pass, and the open question it surfaced

Ran 2000 iterations, T=1.0, fantasy(heart), slot 11 (Allan). Two of HANDOFF's
three predicted sanity checks held: availability decreases monotonically (0
violations), and the 1.11 menu is recognizable (Gibbs/Bijan/Chase/CMC-tier).

**The third did not.** HANDOFF predicted round-1 modal probabilities in the
20–60% band. The actual run: 3.5%–8.8%, every round-1 pick, no exceptions — pick
1.01 goes to the modal player (Jahmyr Gibbs) only 8.8% of the time. This is not
noise; it is what the math in `PickScorer` produces at the current
`weights.yml` values: `adpScale: 12.0` means the ADP gap between the #1 and #4
overall players (a 3-pick spread) is worth `0.25` in score, and at `temperature:
1.0` a softmax over a candidate pool that flat is close to uniform among the top
handful. **The 20–60% figure was a guess, never run, and it was wrong** — per
this project's own convention, that gets corrected here rather than left to look
authoritative. Whether the *model* is wrong (T=1.0 is too flat for round 1 of a
real draft, where consensus is usually much sharper) or the *guess* was just off
is a real open question, not yet resolved, and it matters before Allan trusts the
round-1 output on draft day. Options, undecided: lower default temperature,
increase `adpScale`'s bite at the very top of the board specifically, or accept
that this league's real behavior is this flat and the guess was simply wrong.
**Worth 20 minutes before 2026-08-31.**

**Follow-up after building the FFC ADP source (below) and re-running the same
sim on the improved board: the numbers didn't move** (still 3.5%–9.4%,
monotonicity still 0/1368 violations). That isolates the flatness to
`weights.yml`'s scoring math, not board quality — useful, and still open.

### Never executed

CORS from a real browser origin other than the dev proxy, the Docker build, and
the `ApiTokenFilter` with a token actually set (ran this session with auth off,
the local-dev default).

---

## Do this next

**Schema is now live: `V1__init.sql` is applied and Flyway has recorded its
checksum (verified this session, real Postgres).** Any further schema change —
including the `adp_snapshot` columns in `claude/adp-sources.md` and the
`league`/`manager_profile` changes in `claude/borrowed-drafts.md` — is a **V2**
migration from here on. Do not edit V1 again.

### 1. Manager tendencies — DONE, both display and editing, verified live

Seat cards correctly **display** provenance (`3d65302`, `39f4a72`, verified live
in a browser). **Setting** `reachBias` / `unpredictability` / `note` now has a UI
too — added in a later pass this same session via an architect → coder →
reviewer → verification pipeline, `claude/live-reveal-and-tendencies-ui.md` has
the full design. Each seat card is now editable in place. That pass also found
and fixed a real, previously-invisible bug: `WebConfig`'s CORS config never
allowed `PUT`/`DELETE`, so save/clear silently 403'd from any browser the entire
time this feature existed — worked fine over curl (no preflight), never worked
from the UI it was built for. See `claude/lessons.md` #14.

`manager_profile` was dead; it now carries two separately-owned columns.
`feature_json` is fitted and written only by ingest. `manual_json` is what you say
about a seat and is written only through the API. Neither upsert touches the other.

A stated reach bias is the **shrinkage target**, not an override: no history means
your number is used exactly, two drafts means one third data and two thirds you.
That is what makes fantasy(heart) — zero history, fourteen otherwise-identical
league-average bots — actually worth simulating.

You can set `reachBias`, `unpredictability` (a multiplier on run temperature for
that seat alone) and a free-text `note`. Positional tilt stays fitted-only.

**There is no UI for this.** Drive it with Postman or curl:

    GET    /api/managers
    PUT    /api/managers/{managerId}/tendencies
           {"reachBias": 8, "unpredictability": 1.6, "note": "drafts his own Bengals"}
    DELETE /api/managers/{managerId}/tendencies

`GET /api/managers` shows `stated` (what you typed) next to `effectiveReachBias`
(what the engine will use), so the blending is visible rather than mysterious.

Provenance — `NEUTRAL` / `STATED` / `FITTED` / `BLENDED` — rides out to
`/api/drafts/{id}/seats` and the confidence panel, so a seat running on your
opinion is never displayed as though it were evidence. **Verified this session,
live in a browser**, against real fantasy(heart) seats: `njerickson` and
`popsharky` (Allan) show `FROM HISTORY` with real fitted reach/position text;
the other 12 correctly show the neutral "drafts like the room" copy.

### 2. Get it running — DONE this session, here's what it actually took

The steps below are what HANDOFF said to do; here is what was different in
practice, since the next session (or Allan, on a different machine) will hit
the same gaps:

    git pull
    cd backend
    ./gradlew test          # wrapper is now committed — see "Build tooling" above
    ./gradlew bootRun       # needs a Postgres reachable per application.yml's
                             # defaults (localhost:5433, db/user/pass "draftsim");
                             # docker compose up -d if Docker is available, otherwise
                             # see "Build tooling" above for the throwaway-cluster recipe
    curl localhost:8080/api/health          # weightsLoaded: true — confirmed

No JDK 21 needs to be manually installed — the toolchain auto-provisions it now.
Open `backend/` as the IDE project root, not the repo root, still applies.

### 3. Read the board before trusting anything — DONE, it looks real

    curl -X POST localhost:8080/api/ingest/all/1391509063170293760
    curl localhost:8080/api/board?limit=40

**Done this session.** Top of the board: Bijan Robinson, Jahmyr Gibbs, Josh
Allen, Jonathan Taylor, Ja'Marr Chase, James Cook, Puka Nacua, CMC, Jaxon
Smith-Njigba, Drake Maye... — reads like a real 2026 14-team PPR first round.
`picksWithContemporaneousBoard` came back 180, matching the ~180 predicted.
**This does not mean the board is good enough** — it is still `search_rank`
blended with observed order at a coin-flip weight, per the honest-headline
section below and `claude/adp-sources.md`. It means the pipeline that produces
it is not broken.

### 4. Then, in order

- **Real ADP import — DONE (§3 + §5 + §9), this session.** `claude/adp-sources.md`
  has the full writeup, including a real finding: FFC's `teams` parameter is
  scoring-aware but not yet team-count-aware this early in the preseason (verified
  live — see that doc), so the full 8-14-team matrix from §4 was deliberately not
  built; one cell (14-team PPR) was, and it self-corrects once FFC's data actually
  differentiates. 271/271 players matched, zero misses. QB1 moved from pick 3
  (search_rank alone) to pick 28 blended with FFC — the kind of move that confirms
  the new source is actually doing something. FantasyPros CSV, ESPN, and Yahoo
  are still not built — lower priority now that FFC is real market data rather
  than a popularity proxy.
- **First real simulation — DONE, see above.** Two of three sanity checks passed;
  the round-1 modal-probability one didn't and is now an open question, not a
  guess — see "First real simulation" above. Worth resolving before the ADP
  import, since a real ADP source will change these numbers again and you want
  to know whether today's flatness is temperature or the board.
- **Borrowed drafts — planned in detail, nothing built.**
  `claude/borrowed-drafts.md`. Its **normalization pass is now DONE**
  (2026-09-01, Phase 1 of the roadmap): positional priors and positional tilt
  are keyed on fraction-of-draft rather than round index, and K/DEF gating is on
  rounds-remaining rather than a fixed round number. That was the piece the doc
  flagged as a fix to existing behaviour worth doing on its own; the borrowed
  *data* half is still unbuilt and still conditional on the volume check.
  One item from that doc's normalization list was deliberately left: `runWindow`
  is a fixed 6 picks (43% of a round at 14 teams, 75% at 8), deferred to the
  ad-hoc-sizing work because it changes current behaviour rather than preserving
  it.
- **Live draft polling — built and committed.** See "This session" above and
  "Draft-day gap" below. **Still never dry-run against a real
  `drafting`-status draft**, and fantasy(heart)'s draft went by without one.
- **Variable league size — half-built.** The shared pieces landed with the
  normalization pass: `DraftContextFactory`, `SeatSpec` (3-state
  `USER`/`MANAGER`/`BOT`), `LeagueShape` (sizes {8,10,12,14}, fixed roster
  template). `SimulationService` already routes through them. What is left is
  the visible half — the ad-hoc `SimulationRequest` branch and the frontend
  dropdown — deliberately last, as Phase 5.
- **The interactive mock draft room is the next real feature.** Phase 3 of
  `claude/next-features-roadmap.md`, not started, the largest thing left:
  V3 migration for `mock_draft_session`/`mock_draft_pick`, extracting
  `DraftSimulator.choose()` into a reusable decide-and-apply unit, service +
  controller, and a frontend at the already-reserved `/mock/new`.
- **Frontend `VITE_API_BASE` + auth header.** ~20 lines. Blocks any remote deploy.
  Not needed while running locally against `localhost`.

---

## Context a new session needs

### League facts

    fantasy(heart)       2026  league 1391509063170293760  draft 1391509064357273600  14 teams, COMPLETE (drafted 2026-08-31)
    West Coast FF        2026  league 1389361939561332736  draft 1389361939561332737  14 teams, pre_draft  <- the one to simulate
    (Foot) Ball Knowers  2026  league 1346366555759341568  draft 1346366555776126976  12 teams, complete
    (Foot) Ball Knowers  2025  league 1254190892974084096  draft 1254190894563729408  12 teams, complete
    West Coast FF        2025  league (see /api/drafts)     draft 1262506916932759552  12 teams, complete

All PPR, snake, 15 rounds. Starters QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench.
Sleeper user `popsharky` = `1122386008709910528`. Allan is **slot 11 of 14** in
fantasy(heart), which drafted 2026-08-31 21:15 CDT.

**fantasy(heart) is now a completed draft, which changes what it is good for.**
It is no longer a simulation target — `SimulationService` replays its 210
recorded picks and there is nothing left to predict. It is now *history*: 210
picks in a 14-team league, which is the first 14-team data this project has ever
had for profile fitting (everything fitted before was 12-team). Point live sims
at **West Coast FF 2026** instead.

### Corrections to `draft-simulator-plan.md`

The plan doc is the design of record but three things in it are now wrong:

1. **Football has two seasons of history, not one.** Ball Knowers drafted 2025 *and*
   2026, both complete. The no-backtest decision stands as instructed — but its stated
   premise ("one season, can't validate") is factually off by one.
2. **`reachBias` sign is inverted in the plan.** The plan writes reach as
   `pick_no - adp` and calls positive a reach. The code uses
   `boardPosition - pickNumber`, so positive means reaches.
3. **Ball Knowers 2026 already drafted**, so the league the plan was written around is
   not the one the tool can help with.

### The bug worth remembering

`valueDelta` was originally `(boardPosition - pickNo)` — inverted, making the engine
prefer the **worst** available player. Every structural test passed anyway: 210 picks
made, no duplicates, kickers gated. None asserted that good players go first. Caught by
running a modal draft and seeing the 1.01 pick come back as the last man on the board.
`PickScorerTest.aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot` and
`DraftSimulatorTest.theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop` pin it.
**Keep them.**

### Known warts

| What | Weight |
|---|---|
| ~~No UI for setting tendencies~~ — DONE, seat cards are editable in place, verified live in a browser (see below) | closed |
| `ProfileService.fit()` runs per simulate call. Deliberate: fitting ~360 picks is cheap and a cache would only add staleness | low |
| `available.remove(choice)` is a linear scan — fine at 2k iterations, possibly minutes at 10k | medium |
| Positional priors fit on ~360 picks with `alpha = 8`; smoothing dominates | by design |
| 2025 picks excluded from reach fitting (no contemporaneous board) | by design |
| No backtesting; probabilities are internally consistent, not calibrated | by design |

### Deferred by design

`ideas/` holds speculative directions that are explicitly not planned work — see
`ideas/README.md` for the convention. Nothing there is scheduled or half-built.

`ideas/player-affinity.md` — whether manager tendencies and players belong in a vector store.
Summary: player-to-player similarity built from *features* is worth building and slots
into the scorer as the `w_aff` term the plan already reserves. Embedding notes and
players into a *shared* text space is the appealing wrong answer and the doc says why.
Notes want a one-off LLM extraction into structured attributes, not vector search.
Anything at corpus scale is blocked on an unverified assumption: that public Sleeper
drafts can be enumerated at volume. Check that with a small script first.
Use `pgvector` in the existing Postgres if it ever happens — not a second datastore.

### Retired players are off the board (2026-09-01)

Todd Gurley, out of football since 2021, was being recommended in round 2.
Sleeper's players dump is an archive, not a roster: it never walks `search_rank`
back when a player leaves the league, so Gurley still ships with `search_rank`
27, `status` "Active" and `active` true. Dense-ranking `search_rank` therefore
seeded the board with **1,177 undraftable players, 107 of them inside the top
400** — Tom Brady at 93, Drew Brees at 94, Antonio Brown at 114, Gronkowski at
192.

`status`/`active` are useless as the filter (Gurley reads Active/true). The
field Sleeper does maintain is `team`, nulled when a player is off an NFL
roster. `BoardService.dropOffRoster` now keeps a player only if he is rostered
**or** the wider market drafts him anyway (an FFC ADP row) — the second clause
is load-bearing: Dean Connors (LAR, FFC ADP 170) is a real rookie Sleeper has
not assigned a team yet. Board went 2,007 -> 830 entries; the drafted 210 are
now all rostered players.

Fixing this exposed a second bug worth knowing about: `BoardRepository.save`
upserted without deleting, so a snapshot could only grow. A rebuild that
*shrinks* the board left the dropped rows in place at their old ranks — the
first clean rebuild landed 830 rows on top of 2,007 stale ones and Gurley
stayed at board 33. Snapshot writes are now transactional delete-then-insert.
Any other rebuild that removes players would have hit the same wall.

### The honest headline

**The board is stronger than it was, and still not calibrated.** As of this
session it's a three-way blend — Sleeper's `search_rank` (popularity, not size-
or scoring-aware), observed pick order from completed drafts, and real FFC
market ADP (scoring-aware, not yet team-count-aware — see
`claude/adp-sources.md`) — at weights that are still coin flips wearing
parameters' clothes, just better-informed ones than a single popularity number.
The round-1 modal-probability finding above says the remaining gap between
"the board is real" and "the numbers are trustworthy" is now more likely in
`weights.yml`'s scoring math than in the board itself.

### Draft-day gap

**Built this session — see "This session: live draft poller built" above.**
`LiveDraftPoller` now does the ~10s polling job on `/draft/{id}/picks` while
status is `drafting` that this section used to describe as missing. Resume-
from-state still works the same way it always did (against whatever's in the
DB), it's just now kept current automatically once `/track` is called, instead
of needing ingest re-run by hand mid-draft.

**What's still actually open:**
- ~~Uncommitted~~ — committed.
- **No dry run has ever happened against a real `pre_draft → drafting →
  complete` transition, and fantasy(heart)'s draft went by without one.** The
  differential-replay test only proves the pick-mapping logic is stable against
  already-`complete` drafts; it says nothing about whether Sleeper's `status`
  field or pick-object shape behaves as assumed mid-draft. fantasy(heart) is
  now `complete` with all 210 picks in the DB, but **how they got there was not
  observed** — treat the poller's live behaviour as still unverified. The next
  opportunity is West Coast FF 2026 (`1389361939561332737`); the
  disposable-mock-draft dry run in `claude/live-poller-plan.md`'s "Manual
  pre-draft-night checklist" is still the cheap way to close it without waiting
  for a real draft.
- **There is no UI for any of this.** `/track` is curl-only and the picker card
  shows whatever `draft.status` was last written. That is Phase 4 of
  `claude/next-features-roadmap.md`, not started.
- `startState` still exists on the API and is still unexposed in the UI —
  unrelated to the poller, still an open gap if you want a manual override for
  a pick the poller hasn't caught up to yet.
- In-memory-only tracking state: an app restart mid-draft silently drops
  polling with no alert. Keep the process alive through the real draft.
- No `/untrack` endpoint — if `/track` is ever called against the wrong draft
  id, the only recovery in this version is an app restart.

---

## Environment notes for a future Claude session

Moved to `claude/environment.md` and expanded — what is reachable from the sandbox,
how to execute engine code without Maven, how to stand up Postgres to test SQL, the
`device_stage_files` depth limit, and the git lock-file problem.

`claude/lessons.md` has the bug post-mortems. Read it before trusting a green test
run: the `valueDelta` sign inversion passed every structural test in the suite.

### Working style that fits this project

Allan is a backend engineer — skip framework tutorials. He pushed back usefully when
told something couldn't be verified, and that push found a real bug. Prefer testing a
claim over asserting it, and say plainly which is which.

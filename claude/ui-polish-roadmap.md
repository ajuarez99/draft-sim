# draft-sim — three UI ideas, reconciled

Design note, 2026-08-30. Allan gave three pieces of feedback in one message and
asked for them as "separate plans," then asked for the plans to be folded into one
roadmap doc — same convention as `claude/next-features-roadmap.md`: the individual
plans aren't preserved as separate files, this document is the reconciled result,
written to stand on its own as the brief for whoever builds next.

**Nothing has been built yet — this is planning only**, per how Allan framed the
original request.

## How they relate, and recommended order

**A — auto-slot-detection** and **C — instant-start-reveal** compound directly.
Today, getting from "click a draft in the picker" to "watching the board fill in"
takes two avoidable steps: type your slot number, then click run and wait through a
bare progress screen before the reveal even starts. A removes the first step when it
can (falls back to today's manual input when it can't); C removes the *feel* of the
second (the computation still takes the same wall-clock time, but nothing forces you
to stare at a number-only screen while it happens). Built together, landing on a
draft you're actually in goes from "set slot → click run → wait → then watch" to
"click start → watch."

**B — player-picker-redesign** is independent of both in mechanics, but touches the
same components (`PickPrompt`, `PlayerPicker`) `claude/reactive-resimulation.md`
most recently changed, and needs one new backend field
(`SeatsResponse.rosterPositions`) that A also touches (`SeatsResponse.mySlot`) —
same method (`LeagueController.seats()`), same response shape, no reason to land
them as two separate edits to the same handful of lines.

**Recommended order: A, then C, then B.**
1. **A first** — smallest, lowest-risk, no UI redesign of anything, delivers real
   value standing alone, and is a clean prerequisite for C's full effect.
2. **C second** — frontend-only (see C's own Scope decision below for the deeper,
   explicitly-deferred alternative). Doing it before A would mean rehearsing the
   empty-board flow against a still-manual slot input, only to have the very next
   change reduce how often anyone sees that input at all.
3. **B third** — independent of A/C's mechanics, but land it after so
   `LeagueController.seats()`/`SeatsResponse` picks up both its new field
   (`rosterPositions`) and A's (`mySlot`) together. Also the largest of the three,
   so the two smaller, higher-confidence wins ship first if time is short.

Nothing here blocks building them in a different order if Allan wants B first — the
three are genuinely independent at the code level. This is a suggested default, not
a dependency graph with teeth.

**What none of these three touch**: no database migration, no change to
`SimulationResult`'s core shape beyond two small additive `SeatsResponse` fields (A's
`mySlot`, B's `rosterPositions`), no change to
`DraftSimulator`/`MonteCarloRunner`/the SSE contract. All three are extensions of
work already shipped this session (`claude/reactive-resimulation.md`,
`claude/your-pick-and-pacing.md`), not new architecture.

---

## A. Auto-detect which slot is you

Allan: "when clicking on a draft it should know what slot im in and do that right
away if not then it should let user pick a slot."

### What's there now

`DraftPicker.tsx` links to a draft with no slot at all —
`<Link key={d.id} to={`/drafts/${d.sleeperDraftId}`} className="draft-row">`
(`DraftPicker.tsx:48`). `DraftView.tsx` reads `mySlot` from a `?slot=` query param,
falling back to `DEFAULT_SLOT = 1` (`DraftView.tsx:23,41`) if absent — every draft
view, for every league, starts as if you're slot 1 until you manually type your real
number into the `slot` input (`DraftView.tsx:242-251`).

Nothing in this codebase currently knows "which manager is the actual account
owner." Grepping the whole repo, `popsharky` and Sleeper user id
`1122386008709910528` appear **only in documentation** (`README.md`, `HANDOFF.md`) —
zero code references, no config value, no env var. `Seat`/`SeatsResponse`
(`api.ts:62-81`, `LeagueController.seats()`) carry a `managerId` (the internal
Postgres id) and a display name, never a Sleeper user id — so there's currently no
way, even by hand, to match a seat to "you" from data the frontend already has.

The data to do this exists, just not wired up. `manager.sleeper_user_id` is a real,
`unique`, `not null` column (`V1__init.sql:38-42`), and `ManagerRepository` already
has a lookup keyed the right direction —
`ManagerRepository.idsBySleeperUserId(): Map<String, Long>` (`ManagerRepository.java:44-51`)
— used today only by `LiveDraftPoller`, never by the seats-building path.

### Proposed design

**A new config value, `APP_OWNER_SLEEPER_USER_ID`**, following the exact pattern
`API_TOKEN` already establishes in `.env.example`/`application.yml` (a
deployment-varying value with a blank local default, read once at startup). This
project is explicitly single-user (Allan's own tool, no multi-tenant auth model
anywhere) — a single configured "who am I" value fits how everything else here is
built, not a new concept.

**Backend**: `LeagueController.seats()` gains one more field on the response,
`mySlot: Integer` (nullable). Computed the same place the endpoint already iterates
`draft.get().slotToManager()` per slot — for each slot's `managerId`, look up that
manager's `sleeper_user_id` (a new `ManagerRepository` method, forward-keyed by id,
mirroring the existing reverse-keyed `idsBySleeperUserId()`) and compare against the
configured owner id. `null` when the config value is unset, or when it's set but
doesn't match any seat in this particular league (e.g. a league you're not actually
in, or the owner manager hasn't been ingested into this league yet — both real,
already-possible states, not edge cases invented for this feature).

**Frontend**: `SeatsResponse` type (`api.ts:74-80`) gains `mySlot: number | null`.
`DraftView.tsx`'s existing `refetchSeats()`/seats-loading `useEffect`
(`DraftView.tsx:84-87`) gets one more step: once seats arrive, if the URL's `slot`
query param is **absent** (not just falsy — an explicit `?slot=3` must never be
silently overridden, that's a deliberate user choice, not a stale default) and
`seats.mySlot != null`, call the existing `setMySlot(seats.mySlot)`
(`DraftView.tsx:95-106`, already does `replace: true` so this doesn't pollute
browser history). This is the entire client-side change — no new state, no new
component, reusing the exact same URL-param mechanism the manual slot input already
writes to.

**When it can't detect you** (config unset, or configured id matches nobody in this
league): nothing changes from today — `mySlot` stays at whatever the URL says or
`DEFAULT_SLOT`, and the manual slot input is exactly as available as it is now. This
is "if not then it should let user pick a slot" — not a new fallback UI, the existing
manual input already *is* that fallback; this feature just means it usually won't be
needed.

### Not needed for this

- A UI for setting "who am I" — a single env var matches this project's existing
  single-user, env-var-configured pattern. A settings screen for multiple people to
  each set their own identity is a different, bigger feature this project has no
  evidence of needing yet.
- Auto-detecting slot from `DraftPicker.tsx`'s list view (e.g. showing "you're slot
  11" next to each draft before clicking in) — possible later, not asked for; this
  scope only covers the moment you land on a specific draft.
- Any change to how `mySlot` is used once set — `run()`, the reactive-resimulation
  request, `AvailabilityPanel`, etc. all already just read whatever `mySlot` is;
  auto-detection only changes how that value gets its *initial* value.

### Acceptance criteria

1. With `APP_OWNER_SLEEPER_USER_ID` set to `popsharky`'s real id and fantasy(heart)
   opened with no `?slot=` in the URL, the slot input shows `11` immediately on load
   — no manual typing, no flash of `1` first.
2. Opening the same draft with an explicit `?slot=5` in the URL is respected exactly
   as today; auto-detection never overwrites an explicit query param.
3. Opening a league where the configured owner isn't a manager (or the config is
   unset) behaves exactly as it does today — `DEFAULT_SLOT` fallback, manual input
   fully functional, no error, no broken UI.
4. `./gradlew test` passes. No `LeagueController`/seats-endpoint test exists today
   (confirmed by searching `backend/src/test`) — this is an opportunity to add one
   covering the new `mySlot` field's three states (matched, unset config, no match
   in this league), not just a "don't break anything" check.

---

## B. Player picker: best available and team needs, not survival percentages

Allan, verbatim: "when its my pick and i want to choose a player that screen should
have the best adp palyer possible. I dont care about the Survives to this pick
column, it doesnt make sense to me. maybe give me best player to take and show me
team needs and best adp player there percentages dont mean anything to a user."

### What's there now

`PlayerPicker.tsx` (the "Choose a player" modal) sorts its rows by
`survivalByPick[pausedAt]` descending (`PlayerPicker.tsx:31-37`) and displays that
percentage as the rightmost, most prominent column — a bar plus a number
(`PlayerPicker.tsx:93-96`), the same visual weight as `AvailabilityPanel`'s own
survival curves. ADP ("Board") is shown too, but as a secondary numeric column, not
what the list is sorted or led by. Nothing about roster construction — what
positions the user has already filled, what's still open — appears anywhere in the
picker or in `PickPrompt`. `PickPrompt.tsx`'s one quick action is "Take
{modelPick.player.name}" — the model's own Monte Carlo modal suggestion for that
seat, which factors in that manager's fitted reach bias and roster need, and is
**not the same thing** as "the objectively best player on the board by ADP" (they
often agree; a live test earlier this session paused on a pick where the model's own
suggestion was a 4%-probability player, not the top of the board).

### Why the survival percentage reads as noise, and what it's actually for

It answers "in how many of N simulated drafts was this guy still here at this pick" —
a real, correctly-computed number, but not the question a user picking a player is
actually asking. It matters as a **filter** (a player extremely unlikely to have
lasted this long shouldn't clutter the list) but doesn't need to be the **sort key**
or a **displayed number** for that job to be done. Same "a statistic can be correct
and still be the wrong thing to show" lesson `claude/lessons.md` #7 already names for
the board's own marginal-mode cells — same mistake, different screen.

### Proposed design

**1. Re-sort and re-purpose the picker's existing data, no backend change for this
part:**
- **Sort by ADP ascending** (`r.player.adp`, already on every row) instead of
  survival descending — "best player available" first, matching how a real draft
  board reads.
- **Keep the survival filter, drop the survival column.** `.filter((r) =>
  (r.survivalByPick[key] ?? 0) > 0.01 ...)` stays exactly as-is — still the right
  guard against listing someone realistically already gone — but the bar/percentage
  in the rightmost column goes away. A small text tag ("likely gone" at the very low
  end of what still clears the filter) is enough if any signal is still wanted; a
  full bar chart of a number nobody reads is not.
- **A `positionalRank`-style label ("RB2," "WR5") would read better than a raw ADP
  float, but check before assuming it's free**: `GET /api/board` already returns
  `positionalRank` per player (confirmed live this session), but that's a
  *different* DTO than `SimulationResult.PlayerRef` (`id, sleeperId, name, position,
  team, adp` — no rank field), which is what `AvailabilityRow`/the picker's rows
  actually are. Adding it to `PlayerRef` would be the same kind of small, additive,
  single-call-site change as `sleeperId` was in `claude/reactive-resimulation.md`
  §1 — worth doing, but a real (if small) backend touch, not free reuse.

**2. Team needs — one new backend field, otherwise fully derivable client-side:**

What's already enough, confirmed by reading the code, not assumed: the user's
roster-so-far is fully computable today with zero backend changes.
`SimulationResult.myPicks` gives every pick number belonging to `mySlot`;
`result.board` has the resolved player for every earlier pick number; `userPicks`
(from the reactive-resimulation feature) already holds the user's own confirmed
choices. Filtering `result.board` to `pickNo ∈ myPicks, pickNo < pausedAt`,
preferring `userPicks[pickNo]` over the board's own entry per pick — the exact
precedence `choosePick()` already computes for `startState` — yields the user's real
roster by position with no new data.

What's missing: the roster-positions template. There's no `{QB: 1, RB: 2, WR: 2,
TE: 1, FLEX: 2, K: 1, DEF: 1, BN: 5}`-shaped thing anywhere in a JSON response
today. `LeagueSettings.rosterPositions`/`.dedicatedStarters()`/`.flexSlots()`/
`.benchSlots()` all already exist as methods (`LeagueSettings.java`) but are only
ever used inside the engine (`FootballRules`), never serialized out. **Add
`rosterPositions: string[]`** (Sleeper's own raw flat slot list — same shape already
stored in `league.roster_positions`, no reshaping needed) to `SeatsResponse` — it's
league/draft-level and constant across runs, so the existing seats endpoint is the
right place, not a new endpoint or a per-simulation field.

New shared client-side helper (e.g. `web/src/teamNeeds.ts`), used by both the
picker and a new compact display: given `rosterPositions`, the user's drafted
players so far (per above), compute which slots are filled and which remain open,
treating FLEX correctly (fillable by RB/WR/TE, matching how `FootballRules`'s own
`flexSlots()` concept already works — read that class before reimplementing the
FLEX-eligibility rule differently on the frontend).

**3. Surface it in both places, not just the modal:**
- **`PlayerPicker`**: a compact "your team" strip above the table — each starting
  slot as a small badge, filled (shows the player) or open (position abbreviation,
  dimmed) — same visual family as `DraftBoard`'s position badges (`.pos` class,
  already exists), not a new component style. Each row in the table gets a small
  "fills a need" tag when that player's position matches an open starting slot
  (including FLEX-eligible positions when only FLEX is open).
- **`PickPrompt`**: add a second quick action, **"Take {bestAvailable.name}"**
  (best-ADP player still realistically on the board, same filter as the picker),
  alongside the existing "Take {modelPick.player.name}." When they're the same
  player, show one button, not two identical ones. This directly answers "the
  screen should have the best adp player possible" for the common case of not
  wanting to open the modal at all — the model's own suggestion and "the best player
  on paper" are two different, both legitimate answers to "what should I do here,"
  and the user should get either in one click.

### Not needed for this

- Re-deriving `positionalRank`/ADP itself, or touching the board/scoring engine —
  this is a display and data-plumbing change on top of numbers that already exist.
- A roster editor or custom starting-lineup configuration — `rosterPositions` is
  read-only here, exactly as ingested from Sleeper.
- Changing what `PickPrompt`'s primary "Take X" button *does* (still funnels through
  the same `choosePick`) — only adding a second, equally-primary option alongside it.
- `AvailabilityPanel`'s own survival-curve table below the board — a different,
  already-labeled "how confident is the model" panel with its own purpose; this is
  scoped to the in-the-moment picker/prompt, not every place a probability appears.

### Acceptance criteria

1. Opening the picker at a real pause shows players ordered best-ADP-first, not by
   survival percentage; the survival percentage/bar is gone from the row display.
2. A player with under ~1% chance of still being there still doesn't appear in the
   list (the filter, not the display, is what changed).
3. A "your team so far" strip is visible in the picker, correctly reflecting every
   pick made at this slot so far (including reactive-resimulation's `userPicks`),
   with open slots visibly distinct from filled ones, FLEX handled correctly.
4. Rows that would fill an open slot are visibly marked as such.
5. `PickPrompt` offers a one-click "best available" action distinct from "take the
   model's suggestion" whenever the two differ, and doesn't show a redundant second
   button when they're the same player.
6. No backend change beyond the one additive `rosterPositions` field on
   `SeatsResponse` (plus the optional `positionalRank` addition to `PlayerRef`);
   `SimulationResult`'s core shape is otherwise unchanged.
7. Regression: dedup (can't pick a player already taken at an earlier one of your
   picks), position filter chips, Escape/backdrop close, and the resim trigger on
   picking a row all still work exactly as `claude/reactive-resimulation.md` left
   them — this changes what's displayed and how it's sorted, not the pick-then-resim
   mechanism itself.

---

**Amended after review, 2026-09-01.** Plan-review pass found the section's code
claims accurate, but flagged: `LeagueController.seats()` still builds its response
with `Map.of(...)`, which throws on this section's sibling `mySlot` field (plan A)
being null — must become a mutable map before either field lands, not after; no
current repository method fetches a league by internal id for `rosterPositions`
to source from; FLEX-overflow tie-break and empty-`rosterPositions` display are
unspecified; and `SimulationResult.bestAvailable` — already computed per-your-pick,
simulation-weighted, and currently unused anywhere in the frontend — may already be
the right data source for `PickPrompt`'s new button instead of a fresh client-side
ADP sort. Full detail and the complete amendment list: `claude/plan-review-B.md`.

## C. Start the board immediately, don't gate it behind a wait screen

**Built and verified live, 2026-08-30**, same day as the plan. `DraftView.tsx` now
renders `DraftBoard` the moment `seats` loads (before any simulation exists),
wrapped in a new `.board-stage` with a `.start-overlay` CTA
("Start the mock draft") on top while `!started`; the button/label changed from
"run" to "start"; `.lower-grid`/`.seats-panel` are gated on `started` (`result !=
null`) so the pre-start screen is genuinely full-screen board, nothing else. The
re-run case (a board already exists, `start` clicked again) keeps the original thin
`.progress` bar instead of the overlay — confirmed live, since the overlay is
reserved for the true nothing-has-happened-yet state. `npx tsc -b` and `npm run
build` both clean; no backend changes, matching the Scope decision below.

Allan: "the whole hitting run on start is poor interface lets just start the mock
after a user hits start. we dont need to hit run since we are simulating round by
round anyways that run seems like a waste so give us the board right away thats
empty and go round by round."

### Scope decision, read this first

There are two different things this request could mean, and they are **very**
different in cost. Read this before building either one.

**(i) Make the Monte Carlo computation itself genuinely incremental** — the board
actually fills in pick-by-pick as iterations land, each pick's board value refining
as more simulations complete. Checked against the real code, not assumed: this would
require restructuring `MonteCarloRunner.run()`'s iteration-collection loop (today,
`for (Future<...> f : futures) results.add(f.get())` drains every future before
`aggregate()` ever runs once, at the very end — nothing is incremental today despite
`SimulationController.stream()`'s own doc comment claiming otherwise, which is itself
a stale claim worth fixing separately), moving `counts`/`bestAvailCounts`
map-building out of a one-shot `aggregate()` into something callable periodically on
a partial `results` list, adding a new SSE event type for partial boards, and
reworking `api.ts`'s SSE switch plus `DraftView.tsx`'s whole staleness-guard model
(`requestSeqRef`, `runSeq`, the reactive-resimulation reentrancy lock) to tolerate a
result that changes shape mid-stream instead of arriving once, final. Real, but a
multi-file backend-and-frontend rearchitecture — not a UI tweak.

**(ii) Stop making the user watch two sequential waits (a bare progress bar, then
separately the pick-by-pick reveal) and collapse them into one** — show the same
empty board grid the reveal already knows how to render, immediately, and let the
existing reveal animation simply start ticking the moment the one real (still
computed all-at-once, exactly as today) result lands. Zero backend changes. This is
what "we are simulating round by round anyways" is actually pointing at: the reveal
*already* looks incremental once it starts — the complaint is that today's flow
makes you sit through a *different-looking*, separate wait first.

**This plan builds (ii).** It gets the experience Allan described — hit start, see
an empty board, watch it fill in round by round, no separate waiting screen in
between — without violating the schema-free, small-change discipline this project
has used all evening. (i) is worth a follow-up doc of its own if (ii) doesn't feel
like enough once it's built and tried; don't build it speculatively alongside (ii).

### What's there now

`run()` (`DraftView.tsx:202-227`) sets `running=true`, shows a bare percentage
progress bar (`DraftView.tsx:286-290`), and the entire board section is gated behind
`{result && (...)}` (`DraftView.tsx:298`) — before the first successful run,
`{!result && !running && (...)}` shows plain text ("Run ingest first if you have
not..."), and *during* a run, neither branch renders anything but the progress bar.
Only once `streamSimulation()` resolves does `setResult(r)` fire, `runSeq` bump, and
`useRevealedBoard`'s reset effect (keyed on `runSeq`, per
`claude/reactive-resimulation.md`) start ticking `revealedThrough` up from 0 at
450ms/pick. The reveal mechanism itself is already exactly what Allan is asking
for — "round by round" — it's just gated behind an unrelated-looking wait first.

### Proposed design

- **`DraftBoard` renders even with no `result` yet.** Give it (or a thin wrapper) an
  "empty/loading" mode: same grid, same column headers (from `seats`, already
  fetched independently of `run()` via `refetchSeats()`), every cell showing the
  existing hidden-cell state (`—`, per `DraftBoard.tsx`'s `hidden`/`visible` logic)
  since `revealedThrough` naturally starts at 0. This needs `teams`/`rounds` before
  a `result` exists — both are constant per league (`seats.teams` already fetched)
  and don't require a simulation to know.
- **Clicking "start" (renamed from "run" — same button, same handler, better label
  now that it isn't gated behind a separate wait) shows this empty grid
  immediately**, not the "Run ingest first" placeholder text and not a bare progress
  bar with nothing else on screen. The progress fraction still exists (SSE already
  sends it) — show it as a small, secondary status line near the grid ("computing...
  38%") rather than the only thing rendered.
- **The moment the real result lands, nothing about the reveal changes** —
  `runSeq` bumps exactly as today, `useRevealedBoard` starts ticking from 0 exactly
  as today. The only thing that changes is what was on screen *before* that moment:
  a real (if inert) board instead of a blank wait screen.
- **Rename, don't just relabel:** if "start" replaces "run" in the button text,
  make sure `PickPrompt`/`PlayerPicker`'s own copy and any other place that says
  "re-run" or "the run" stays consistent — a small text audit, not a functional
  change.

### Resolved: explicit start, and the pre-start screen is a real full-screen board

Allan's answer: "give me an explicit start. empty board that takes up whole screen
like sleeper and a start button to get the draft going." Keep the explicit click
(no auto-start on page load). One addition beyond the original proposal below: the
**pre-start screen is not just "the board renders instead of placeholder text
somewhere in today's three-band layout"** — it's a dedicated full-screen empty-board
state, matching the Sleeper reference screenshot `claude/board-redesign-pick-by-pick-
playercard.md` already used for the board's own visual language. Before a
simulation has ever run for this draft (`!result && !running`), `lower-grid` and
`seats-panel` don't render at all — the board panel alone fills the content area,
full width and height, exactly the dense full-bleed grid Sleeper's own draft room
shows before/as a draft starts, with a prominent "Start" call-to-action (not the
small control-bar button alone) placed over or beside it. Once `running` or
`result` exists, the normal three-band layout (board + availability + seats)
returns exactly as it works today — this full-screen treatment is specifically the
*empty, nothing-has-happened-yet* state, not a permanent layout change.

### Not needed for this

- Any change to `MonteCarloRunner`, `SimulationController`, or the SSE event
  contract — see the Scope decision above. Frontend-only rendering change.
- Changing `useRevealedBoard`'s tick pacing (450ms/pick) — already tuned, per
  `claude/your-pick-and-pacing.md`.
- A skeleton-loading animation more elaborate than the grid's own existing
  hidden-cell rendering — reuse what's there, don't invent a second "loading"
  visual language alongside it.

### Acceptance criteria

1. Clicking "start" shows the full, empty board grid immediately — no intermediate
   "Run ingest first" text, no screen with only a progress bar and nothing else.
2. The progress percentage is still visible somewhere on screen while computing,
   just not as the only content.
3. Once the simulation completes, the reveal begins exactly as it does today (same
   pacing, same pause-at-your-picks behavior) — this changes what's visible
   *before* that moment only.
4. Re-running (a second "start" click) while a board is already shown behaves
   exactly as `claude/reactive-resimulation.md`'s existing `run()` reset logic
   already specifies (clears `userPicks`, `openPick`, resim state, bumps `runSeq`) —
   no new reset bugs from rendering an empty board earlier than before.
5. No backend files touched, no new network calls, no change to `SimulationResult`
   or the SSE event contract.

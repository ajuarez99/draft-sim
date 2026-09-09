# Live pick names + your-team fit

Scoped and **built** 2026-09-09, same session. Allan took the v1 recommendations
(slot-based fit, announcement as the feed's newest row) without amendment, so
all three phases below shipped together. What follows is the plan as written;
"Status" at the bottom records what actually landed and what is still unverified.

Two features, one shared blocker.

* **A. Pick announcements** — when a pick lands in a live draft, say *who* went,
  *who took them*, and a line on how they fit that manager's roster.
* **B. Your team, on draft day** — the roster you have so far, mapped onto the
  league's starting slots, with what's still open and what your next pick would
  fill.

Both are UI features over data the app already has. Neither needs a new model.

## What already exists (do not rebuild)

| Piece | Where | State |
| --- | --- | --- |
| Pick rows: round.pick, pos rank, name, manager, position-run callout | `web/src/components/PickFeed.tsx` | Built, already rendered on the live page |
| Starting-slot fill / open slots / "Fills FLEX" text | `web/src/teamNeeds.ts` | Built, used by `PlayerPicker` + `OnTheClockPickInput`, **not** by `LiveDraftView` |
| League roster template + sport, on the live page | `SeatsResponse.rosterPositions`, `.sport` | Already fetched in `LiveDraftView` |
| Landed picks with players and managers | `result.board` below `picksMade` | Available, but see the blocker |
| The picks themselves, server-side | `draft_pick` rows the poller writes; `DraftRepository.picks()` | Available, already read by `liveStream` to synthesize the first frame |

So B is mostly wiring. A is wiring **plus** the blocker below.

## The blocker: names lag reality

`LiveState` (backend record ↔ `web/src/api.ts:317`) carries counts only —
`picksMade`, `lastPickNo`, `onTheClockSlot`. No player, no manager. The live
page gets names from `result.board`, which is the *simulation* result:
`RESIM_DEBOUNCE_MS` 1500 + a 500-iteration run (measured ~5s in the mock room;
**unmeasured on the live stack**, per `LiveDraftView.tsx`'s own header).

Consequences today, both of which A cannot live with:

1. A pick that lands at 8:04:00 is nameable on screen somewhere around 8:04:07.
   An "announcement" that arrives seven seconds late, after the room has
   already reacted, is not an announcement.
2. Before the first projection returns there are **no** names at all — the feed
   is deliberately empty (`LiveDraftView.tsx`'s `landedPicks` comment says so).

The fix is small and belongs server-side: the SSE `state` frame should carry
the landed picks, or at least the last few, resolved to player + manager. The
poller already writes them, `liveStream` already reads them for the initial
frame, and `changeKey` already knows when something moved. That decouples
"what happened" from "what we think happens next" — which is the honest split
anyway: picks are facts, the board past `picksMade` is a projection.

## Phases

**Phase 1 — names on the state frame (backend, small).**
Add a `recentPicks` array to `LiveSnapshot`/`LiveState`: `pickNo`, `round`,
`draftSlot`, player (id/name/position/team/adp, the existing `PlayerRef`
shape), manager name from `slotToManager`. Cap it — last 5 is enough for the
feed and the announcement; the full board still comes from the projection.
Mirror the record in `api.ts` **in the same commit** (AGENTS.md rule).
Then `landedPicks` feeds from `live.recentPicks` instead of `result.board`, and
the feed is populated before the first simulation ever returns.

**Phase 2 — the announcement (frontend, small).**
`PickFeed`'s newest row is already special-cased (it owns the run callout).
Promote it: full name, manager, and one fit clause. Do **not** add a second
component and do **not** add a toast/banner — the room already has a place
where the newest pick lives, and a second one competing with it is the "flat
uniform cards" failure mode. The existing run callout and a fit clause compete
for the same slot; decide which wins on the newest row (suggest: the run, when
there is one — it is the rarer and more actionable signal).

**Phase 3 — your team, on draft day (frontend, small).**
Wire `computeTeamNeeds(sport, seats.rosterPositions, myLandedPlayers)` into
`LiveDraftView` and render the starting-slot strip next to
`AvailabilityPanel` — QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF with the player seated
in each and the open ones visibly open. `openPositions` then tags the
availability rows with `needLabel` exactly as `PlayerPicker` already does, so
"who survives to your next pick" and "who you actually need" read as one
thing. `draftedSoFar()` is close but takes `myPicks`/`pausedAt`/`userPicks` —
live has no reveal boundary and no user picks, so this is a straight filter of
the landed prefix to `mySlot`, gated on `slotKnown`.

Phase 3 is independent of Phase 1 and can ship first if the projection latency
proves acceptable for a strip that changes only on *your* picks.

## Where "fit" comes from — decide before building Phase 2

Two sources, and they are not the same claim:

* **Slot-based (`teamNeeds.ts`).** "Their second RB — RB2 was open." Cheap,
  deterministic, already correct for both sports, no backend work, and it is
  the same vocabulary Phase 3 uses. Honest about being a slot count and
  nothing more.
* **Engine-based (`SportRules.rosterNeed`).** The actual marginal
  starting-lineup value the simulator scores picks with. More truthful about
  *value*, but it is a hot-path function on a `RosterState` that exists only
  inside a run — exposing it means a new endpoint or a new field on the
  simulation result, and it is a number that needs explaining on screen.

Recommendation: slot-based for v1. It says something true, it costs nothing,
and it keeps the announcement's claim inside what the data supports — the
project's standing rule. Revisit only if the line reads as too thin in a real
room.

Second question, cheaper: does the fit clause cover **every** seat's pick, or
only picks by seats whose roster we care about? Every seat is the same code
(the landed prefix has all of them), so covering everyone costs nothing beyond
the render — take it.

## Decided

1. **Announcement placement** — the promoted newest row in `PickFeed`. No
   banner, no toast.
2. **Fit source** — slot-based (`teamNeeds`). The engine's `rosterNeed` stays
   where it is.
3. **Mock-room strip** — not done. `PlayerPicker` already shows your strip
   there, at the moment you are actually choosing; a second copy on the page
   behind it would be duplication, not parity. Still open if it reads as a gap.

## Status (2026-09-09)

Built, with one correctness fix that fell out of Phase 1 and was not in the
plan:

* **Phase 1.** `recentPicks` — the last 12 landed picks, resolved to player and
  manager — now rides on every `state` frame. `LeagueController.PickNaming` is
  the shared resolver, extracted from `realBoard`, which used to do this inline;
  it is built once per stream rather than per tick, because rebuilding it per
  tick would re-read the whole player pool every ten seconds per open tab.
  Mirrored in `api.ts` as `LiveState.recentPicks: RealPick[]` in the same
  commit.
* **The unplanned fix.** `LiveDraftView.landedPicks` was `result.board` filtered
  to `pickNo <= picksMade` — but `picksMade` moves the moment the poller sees a
  pick and `result` is the *last* simulation, so for every pick that landed
  since, that filter was promoting the engine's guess at those cells to a fact
  and printing it in the feed as one. `landedPicks` now merges `recentPicks`
  over that prefix, facts winning. This was the real cost of the blocker, and it
  was already on screen before any of this was built.
* **Phase 2.** Newest row: full name, manager, and one trailing clause — the
  position run when there is one, otherwise the fit (`Fills RB2` / `Depth`).
  The manager now survives a run, which it did not before. Left edge tinted in
  the player's own positional color, set inline from the position code so both
  sports are covered without eleven CSS rules.
* **Phase 3.** `TeamStrip` extracted from `PlayerPicker` unchanged and reused;
  the live room shows your starting lineup with an "n of 10 starters" count, and
  `AvailabilityPanel` takes an optional `openSlots` so its rows carry the same
  "Fills RB" tag the picker's do. `needLabel` and `fitSlot` both delegate to one
  new `openSlotFor`, so the sheet, the picker and the announcement cannot
  disagree about what counts as a need.

Honesty guards worth not undoing:

* The fit clause is suppressed unless the client holds the *whole* landed list
  (`landedPicks.length === picksMade`). Before the first projection returns
  there are only the last 12 picks, and a roster assembled from a partial
  history would state "Fills RB2" confidently while being wrong.
* The your-team strip and the availability need-tags are both gated on
  `slotKnown`, not on the `DEFAULT_SLOT` fallback — same rule the crimson board
  cells already follow.
* `fitSlot` numbers a slot by its position in the *template*, not by counting
  the roster. A team with three RBs fills FLEX2 next, not "RB4". Pinned by
  `teamNeeds.test.ts`.

**Verified:** full backend suite green, `tsc -b` clean, 139 frontend tests green
(new: 3 in `LeagueControllerLiveStreamTest`, 7 in `teamNeeds.test.ts`, 4 in a new
`PickFeed.test.tsx`, 6 in `pronounce.test.ts`, 10 in `useAnnouncer.test.tsx`).

**Not verified:** nothing has been run in a browser, and no live draft has
delivered a `recentPicks` frame. **No sound has ever actually been heard** —
jsdom has neither `speechSynthesis` nor `AudioContext`, so the announcer's
tests stub both and prove only which calls are made, never how any of it
sounds. The respellings in particular are unheard guesses until someone plays
them. Whether audio survives a backgrounded tab is also untested. The backend suite had to be run against a copy
of the tree in the scratchpad, because a concurrent session had
`LiveDraftPoller.java` mid-edit (a `Subscription` type referenced but not yet
written) and the shared tree would not compile; the copy used that file at HEAD
with every other change in place. Re-run the suite in the real tree once that
session lands, before trusting this.

## Phase 4 — spoken announcements (added after the fact)

Sound was written up as out of scope above; Allan asked for it in the same
session and took the free route, so it shipped alongside the rest. **No new
dependency**: `speechSynthesis` is built into every browser this app targets,
so `web/package.json` still has three runtime deps.

* `sound.ts` — speak / stop / chime / preference, and the reasons each is
  shaped the way it is.
* `pronounce.ts` — a short respelling table. This is the part that decides
  whether the feature sounds good rather than whether it works: the OS voices
  mangle `Ja'Marr`, `Achane`, `Nacua`, `Odunze`. Best-effort respellings, not
  IPA — judge them by ear on the machine you draft from, and edit freely.
* `useAnnouncer.ts` — the decisions about what *not* to say, which is all of
  the difficulty: `recentPicks` redelivers the same dozen picks every tick,
  enabling mid-draft must not replay the backlog, an autopick burst must not
  queue four announcements, and StrictMode double-invokes effects. One
  high-water pick number handles all four.
* A chime (synthesized, no asset) when the clock reaches your seat. Arguably
  the more useful half — it fires when you have looked away, which is exactly
  when a spoken pick announcement is least useful.

Two constraints not to design around later:

* **Autoplay.** Browsers refuse speech and WebAudio until the page has been
  interacted with, which is why enabling is a click and why `prime()` speaks a
  confirmation inside it. The preference survives a reload; the browser's
  permission does not, so after a fresh load nothing is audible until the user
  clicks something. Nothing in the app can fix that.
* **Cloud TTS is the upgrade path, not a fix.** ElevenLabs / OpenAI / Google
  voices are much better, but they are paid per character, need a backend proxy
  so the key is not in the browser, add a network round trip to something that
  should feel instant, and want an audio cache per player id. Only worth it if
  the free voices are actually tried and found wanting.

## Not in scope

OS-level notifications. Any change to reveal pacing (`useRevealedBoard`'s 450ms
tick is deliberate — see `claude/board-first-layout-and-pick-latency.md`). Any
change to what the engine simulates.

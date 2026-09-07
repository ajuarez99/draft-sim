# On the clock, the pick feed, and legible board cells

Built 2026-09-07. Steps 1–3 of a six-screen design review Allan asked for
("make this look less engineery"). The review itself is an artifact, not a file
in this repo; what it concluded, and what got built off it, is below.

The review's one-line diagnosis: **the screens are a faithful rendering of the
data model, one table per entity, and the parts of a draft worth simulating —
whose turn, what just went, what it costs you — never get a shape of their
own.** Steps 4–7 (home hero, mock-setup seat strip, availability survival
strips, manager comparison axis, narrow-viewport fix) are not built.

## What was measured, not eyeballed

Taken in the running app at 1440x900 against a fresh ingest of league
`1391509063170293760`, mock run to pick 28. The house-style header in
`styles.css` says measure rather than eyeball; these are those numbers.

| | Before | After |
|---|---|---|
| Board names truncated | **23 of 28** | **0 of 28** |
| `.pickno` | 8px @ 2.79:1 — fails AA | 10px @ 5.60:1 |
| `.team-code` | 9px @ 3.98:1 — fails AA | 10px @ 5.13:1 |
| `.col-head-name` | 9px @ 4.66:1, unreadable | 11px @ 7.99:1, 0 truncated |
| Unstyled `input[type=range]` in the sheet | 1 (browser-blue) | 0 |

## Step 1 — the board reads

Two changes, and the second one is the bigger half.

**`playerName.ts`.** Initial plus surname, because the surname is the identity
and the first name is what you can afford to lose: `Jahmyr Gi…` costs you the
player, `J. Gibbs` doesn't. Edge cases came from the real 845-entry board
rather than imagination — `Amon-Ra St. Brown` (particle belongs to the
surname), `John Michael Gyllenborg` (middle name goes), `J. Michael Sturdivant`
(already an initial, don't double-punctuate), and team defenses, whose "name"
is `Seattle Seahawks` and would abbreviate to the nonsense `S. Seahawks` — DEF
drops the city instead. Generational suffixes are kept and are the one
defensive case: none are in the current pool, but Sleeper carries them.

**`.board .name` lost its `padding-right: 20px`.** This is what actually got
truncation to zero. The gutter was reserving room for `.pickno`, which is
absolutely positioned against the *cell* and sits on the badge's line —
measured in the running app, its bottom edge clears the name's top by 11px, so
the name never shared a row with it and the reservation only ever cost width.
Abbreviation alone took 23 → 4; removing the dead gutter took 4 → 0.

The full name did not disappear: it is in the cell's `title` (which now leads
with it) and in PlayerCard. Every surface with real width — the availability
sheet, the picker, PickPrompt's buttons — still shows it in full. Only the
96px cell abbreviates.

## Step 2 — the room says what's happening

`RevealScrubber` is gone. It rendered `Pick 28 of 210 · skip · re-run`: a loop
counter and two operator buttons in the room's most valuable strip of pixels.

`OnTheClock` replaces it, and also replaces the mock room's old
`.turn-indicator` — two components answering "whose turn is it" in two visual
languages was the duplication. `TurnIndicator` survives as the mock room's
adapter over it; DraftView maps reveal state into it inline.

`PickFeed` is the genuinely new thing, and it cost no backend work at all:
every row is a pick the board already holds, and `pickRun.ts` is a read over
the last six of them. It fired on real data in both rooms on the first run
("4 of the last 6 were WR" at 2.13; "4 of the last 6 were RB" in the mock).
K and DEF are excluded from run detection on purpose — every draft ends with a
block of them, so "5 of the last 6 were K" is always true at pick 200 and never
news.

Two off-by-ones were found by looking at the running app, not by reasoning:

1. **The seat on the clock was the seat that had just picked.** `revealedThrough`
   is the last pick that has *landed*, so the open seat is the one after it.
   Reading it directly put the pick already sitting at the top of the feed back
   into the header as though it hadn't happened.
2. **The feed must cut at `decidedThrough`, not `revealedThrough`.** A pause
   stops *on* your pick with both equal, so the board's predicted player for
   your still-open pick would be announced as a pick you'd made. DraftView
   already computed `decidedThrough` for exactly this hazard.

`re-run` moved into the gear as **Simulate again** — it discards the board and
starts over, which belongs beside the settings it re-reads. `skip` stayed in
the strip, because it acts on the reveal you are watching right now.

**PickPrompt lost its header line.** With the crimson strip directly above it
saying `YOUR PICK / You — 2.14`, the banner's own `Your pick — Round 2.14
(pick 28)` was the same sentence twice in the same colour. It keeps the half
the strip doesn't have: what happens when you press one of the buttons.

**Cost, stated honestly:** the strip plus feed is ~150px where the scrubber was
~28px, and on your own turn PickPrompt stacks under both. The board scroller
still holds 7.6 rounds at 900px, so nothing is unreachable — but this is real
vertical budget spent, and it is the thing to revisit first if the room ever
feels cramped.

## Step 3 — voice

A copy pass, no components moved. `track` → `Follow`; `12/14 seats mapped` →
`Only 12 of 14 managers identified` (and the full house says so quietly, since
the incomplete case is the one worth noticing); `Availability at your picks` →
`Who's still there when you pick`; `Mock draft #7` → `10-team mock · through
2.09`, because a row you can tell from the next one beats an id the reader
never chose. Sentence case throughout.

Two of these changed behaviour rather than only wording, because copy that
promises something has to be true:

- **`league id` → `Sleeper league link or ID`**, with URL parsing behind it.
  `https://sleeper.com/leagues/1391509063170293760/team` is what is actually in
  someone's clipboard.
- **The availability sheet's depth slider became chips.** It was a bare
  `input[type=range]`, which takes none of this file's styling and so rendered
  in the browser's default blue — the only element in the app outside the
  palette. Capped at 6 rather than the slider's 8: every step is visible chrome
  now, and past ~4 picks out the numbers are compounding more model uncertainty
  than they look like they are.

And the review's loudest single finding: the pre-start overlay printed
`POST /api/ingest/all/1391509063170293760` for the reader to run themselves.
A curl command on the app's primary empty state. It now links to the picker.
React Router's bare `Not Found` got a real route with a way back, too.

## Watch out for

- **`TurnIndicator` no longer takes `round`.** OnTheClock derives it from
  `pickNo`/`teams` — one formula, not two that can disagree. Its tests assert
  the mapping now, not the markup.
- **Column handles can ellipsize at 11px** where they never did at 9px. That
  is the trade: fourteen headers you can read, against the longest handle
  occasionally clipping. The full name is in the header's `title` and in
  SeatPopover.
- **`positionRun` returns null until six picks exist.** "4 of the last 6" off a
  four-pick draft is a lie about the sample, and it was tempting to let it
  through.

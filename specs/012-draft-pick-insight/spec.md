# Feature Specification: Live draft pick insight

**Feature Branch**: `012-draft-pick-insight`

**Created**: 2026-09-30

**Status**: Draft

**Input**: User description: "ok lets enhance the draft experience. wheneever a user drafts lets make a modal pop up with how the player fits into team what they need left most likely player they will pick and maybe a summary of how they fit? any other ideas with whta data we have and what we can do?"

## Scope in one line

**The live draft room only**: the page that follows a real Sleeper draft as it
happens. The mock room and the pre-draft simulator are not touched (confirmed by
the user, 2026-09-30).

## Context: what the live room already knows

This feature presents data the live room already has, or can get from data the
app already stores. It must not invent a new model. Everything it shows comes
from one of these:

| Fact | Already available in | Honest limit |
| --- | --- | --- |
| Who was picked, by whom, at which pick | The live room's landed picks, pushed as soon as the poller sees them | A fact |
| Which starting slot the player fills, what is still open | The shared slot-fill logic behind "Fills RB2" / "Depth" and the team strip | A slot count, not a value judgement |
| Pick versus the player's ADP | The player's ADP on every board row | ADP is a single snapshot, not a consensus feed |
| The manager's drafting tendencies (reach, positional tilt, unpredictability) and where they came from (history, stated, neutral) | The seat profile the simulator already uses | One or two drafts of history at most; a neutral seat has none |
| The manager's likely next pick, with the share of simulations that agreed | The live room's projection: per-pick candidates and their probabilities | *Amended after planning:* the live room projects **once per seat, not per pick**, so this needs a new per-pick background re-projection (plan R1). Its live cost is unmeasured, and the server may refuse it under load (429) |
| Whether the model saw this pick coming | The projection that was current *before* the pick landed | Same projection limits |
| Players remaining at each position, and each one's chance of lasting to your next pick | The remaining player pool and the availability curves | The survival figures are projections, and only exist once your seat is known |

**Reversed decision (accepted by the user, 2026-09-30).** The design in
`claude/live-pick-names-and-team-fit.md` said "no banner, no toast": the newest
feed row was the only place the latest pick appeared. This feature adds a pop-up
card for every pick. The feed row stays; the card adds detail on top of it
rather than replacing it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See how a pick fits the team that made it (Priority: P1)

A pick lands in the live draft. A card appears showing the player, the manager who
took them, which starting slot the player fills on that manager's roster (or that
they are depth), the starting slots that manager still has open, and a one- or
two-sentence plain-language summary tying those together ("Their second RB — they
still need a TE and a FLEX, and they have not taken a QB yet").

**Why this priority**: This is the core of the request. It is built from data the
live room already computes for the feed row and the team strip, and it is useful
even before any projection has run.

**Independent Test**: Follow a live (or replayed) Sleeper draft. For each pick,
check that the slot the card names and the open slots it lists match that
manager's roster on the board.

**Acceptance Scenarios**:

1. **Given** a manager with RB1 filled and RB2 open, **When** they draft an RB, **Then** the card says the player fills RB2 and lists every starting slot that manager still has open.
2. **Given** a manager whose starting slots for that position are all filled, **When** they draft that position, **Then** the card says the player is depth / bench and does not claim a starting slot.
3. **Given** a basketball league, **When** a PG is drafted into a roster with PG filled but G open, **Then** the card names G (the more specific shared slot), exactly as the existing team strip would.
4. **Given** a manager's final pick, **When** it lands, **Then** the card shows the completed roster with no "still needs" list and says so plainly.

---

### User Story 2 - What they will probably do next (Priority: P2)

The card also shows the drafting manager's most likely next pick according to the
projection: the player, the share of simulations in which that manager took him
at that pick, and one or two runner-up candidates. It also says how much history
the prediction rests on (drafts observed, or "no history — neutral seat").

**Why this priority**: The user asked for it directly, and no other draft tool
can offer it. It depends on a current projection, so it adds to Story 1 rather
than replacing it.

**Independent Test**: After a pick, once the projection has refreshed, confirm
the card's "likely next" player and percentage match the projected board's cell
for that manager's next pick.

**Acceptance Scenarios**:

1. **Given** a current projection, **When** a pick lands, **Then** the card shows that manager's next pick number, the top candidate with its percentage, and up to two alternatives with theirs.
2. **Given** the projection is still re-running after the pick, **When** the card opens, **Then** the "likely next" section shows an updating state and fills in when the new projection arrives. It never shows the pre-pick projection's guess as the post-pick one.
3. **Given** a top candidate whose share is low (below 25%), **When** it is shown, **Then** the card says the pick is wide open instead of presenting a prediction.
4. **Given** no projection has completed yet, **When** the card opens, **Then** the "likely next" section is left out, with a short reason, instead of showing an empty or zero value.
5. **Given** that manager has no picks left, **When** the card opens, **Then** no "likely next" section is shown.

---

### User Story 3 - Was that a reach, and did anyone see it coming? (Priority: P2)

The card compares the pick with ADP ("14 picks before ADP" / "fell 9 picks past
ADP" / "right on ADP") and with the manager's own profile ("on brand: this
manager historically leans RB" or "off script"). If a projection existed before
the pick, it also says how likely the model thought this exact pick was ("the
model had this at 31%" / "a surprise — under 5% of runs").

**Why this priority**: It is cheap, uses existing numbers, and makes the card a
read on the room rather than a recap, which is what the product is for. It also
keeps the product honest: a visible "surprise" tag shows how often the model
misses.

**Independent Test**: For a pick taken well ahead of ADP, confirm the reach tag
and its pick count. If a projection existed, confirm the "model had this at N%"
figure equals that player's share at that pick in the pre-pick projection.

**Acceptance Scenarios**:

1. **Given** a player with ADP 30 taken at pick 16, **When** the card opens, **Then** it says the pick was 14 picks before ADP.
2. **Given** a player with no ADP (unranked), **When** the card opens, **Then** no ADP comparison is shown.
3. **Given** a neutral seat with no history, **When** the card opens, **Then** no on-brand / off-script claim is made.

---

### User Story 4 - Position scarcity meter (Priority: P2)

A panel that is always visible in the live room shows, for each position, how
many starter-quality players remain right now. Once your seat is known, it also
shows how many are projected to still be there at your next pick. When a run on
a position is draining it, that position is flagged. The meter updates with
every pick. The pick card links to it with a single line when the pick changes
the picture ("3rd TE in 5 picks — 2 starter-quality TEs left").

**Why this priority**: It is the most useful signal while waiting to pick, and
it uses data the room already has: the remaining player pool, positional ranks,
the league's starting slots, the availability curves and the existing run
detector.

**Independent Test**: At any point in a live draft, count the remaining players
at a position whose positional rank is within the starter-quality cutoff. The
meter's "now" figure must equal that count. With your seat known, its "at your
next pick" figure must equal what the availability curves imply for those
players.

**Acceptance Scenarios**:

1. **Given** a 12-team league with 10 starting slots, **When** the meter renders, **Then** "starter-quality" means the board's top 120 players (12 × 10), each position shows how many of its players in that top 120 are undrafted, and the meter says how the cutoff is defined. *(Amended after planning: this was a per-position cutoff by positional rank; see Amendments.)*
2. **Given** your seat is known and a projection is current, **When** the meter renders, **Then** each position shows both "left now" and "expected left at your pick N".
3. **Given** your seat is not known, **When** the meter renders, **Then** only "left now" is shown, with no projected figure.
4. **Given** the run detector flags a run at a position, **When** the meter renders, **Then** that position is visibly marked as running.
5. **Given** a basketball league, **When** the meter renders, **Then** it uses basketball positions, and positions eligible for shared slots (G, F, UTIL) are counted by the same rules as the team strip.

---

### User Story 5 - On-brand meter per manager (Priority: P3)

For each manager, a compact read shows how their draft so far compares with
their profile: positional mix against their historical lean, and average reach
against ADP compared with their historical reach. It is shown in the pick card
for the drafting manager, and the whole room can be seen at a glance from the
live room.

**Why this priority**: It turns fitted tendencies into something you can watch
play out, closing the "what we assume vs what they do" loop the managers page
started. It depends on profile history, which is thin, so it needs the most
careful caveats.

**Independent Test**: For a manager with fitted history, compute their average
(pick − ADP) over their picks so far and their position counts. Confirm the
meter shows those figures beside the profile's reach and lean.

**Acceptance Scenarios**:

1. **Given** a manager with history whose profile leans RB, **When** four of their first five picks are RBs, **Then** the meter reads as on brand for positional mix and shows the counts.
2. **Given** a manager who has made fewer than 3 picks, **When** the meter renders, **Then** it says it is too early to judge instead of giving a verdict.
3. **Given** a neutral seat (no history, nothing stated), **When** the meter renders, **Then** it shows the draft-so-far figures only, with no on-brand / off-script verdict.
4. **Given** a manager whose profile is stated, not fitted, **When** the meter renders, **Then** it labels the comparison as against the stated tendency.

---

### Edge Cases

- **Rapid picks**: a fast live room can land several picks within a few seconds.
  Cards must not stack into a queue the user has to click through. A newer pick
  replaces the open card, and earlier picks stay visible in the feed.
- **Your turn**: when you are on the clock, the card must not cover the
  on-the-clock status, your team strip or the availability panel.
- **Unknown seat** (your slot is not yet identified): Stories 1–3 and 5 work for
  every manager. Story 4 shows "left now" only.
- **Joining mid-draft / reconnecting**: picks that landed before the room opened
  are history. The room opens with no card and shows cards only for new picks.
  The meters are correct from the first render.
- **Picks that land while the tab is hidden**: on return, show only the most
  recent pick's card (or none), not a backlog.
- **Kickers / D-ST and other late-only positions**: the fit line and the scarcity
  meter use the same slot rules as the team strip, with no special wording.
- **A drafted player not on our board** (off-board player): the card shows the
  player and manager, and leaves out the ADP, projection and scarcity lines.
- **Draft completes**: no "likely next" section, and the meters freeze at their
  final state.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST show a pick insight card for every manager's pick, yours included, in the live draft room, and only there. The mock room and the pre-draft simulator are unchanged.
- **FR-002**: The card MUST show the player (name, position, team), the drafting manager with their avatar, and the pick number and round.
- **FR-003**: The card MUST state which starting slot the player fills on that manager's roster, or that the player is depth. It MUST use the same slot rules as the existing team strip and "Fills …" tags, so the three can never disagree.
- **FR-004**: The card MUST list the starting slots the drafting manager still has open after this pick.
- **FR-005**: The card MUST include a short plain-language summary built from the facts above (fit, open slots, notable gaps). The summary MUST NOT claim anything the card's own figures do not support.
- **FR-006**: Where a current projection exists, the card MUST show the manager's most likely next pick with its share of simulations and up to two alternatives, labelled as a projection. It MUST also show where the manager's profile comes from (history with its draft count, stated, or neutral).
- **FR-007**: The card MUST NOT show a projection computed before this pick as though it were computed after it. While a refresh is pending, the sections that depend on the projection MUST show an updating state.
- **FR-008**: When the player has an ADP, the card MUST compare it with the pick number and state the difference in picks.
- **FR-009**: Where a projection existed before the pick, the card MUST show the share of simulations in which this player went at this pick.
- **FR-010**: A newer pick MUST replace an open card rather than queue behind it. The user MUST be able to dismiss the card with one action, and the card MUST close itself after a short period.
- **FR-011**: The card MUST NOT cover the on-the-clock status, your team strip or the availability panel while you are on the clock.
- **FR-012**: Users MUST be able to turn the cards off (and back on) on each device, and the choice MUST persist across visits. Turning the cards off does not hide the meters.
- **FR-013**: When a section's data is unavailable, it MUST be left out with at most a one-line reason. It must never be shown as zero, empty, or a placeholder number.
- **FR-014**: The card and both meters MUST work for football and basketball leagues, using each sport's own positions and slots.
- **FR-015**: The card and both meters MUST be usable on a phone-width screen without horizontal scrolling.
- **FR-016**: The scarcity meter MUST show, for each starting position in the league, how many starter-quality players remain. It MUST state the starter-quality cutoff it uses, which is derived from the league's own starting slots and team count.
- **FR-017**: When your seat is known and a projection is current, the scarcity meter MUST also show the expected number of those players still available at your next pick, labelled as a projection.
- **FR-018**: The scarcity meter MUST mark a position that the existing run detector currently flags.
- **FR-019**: The on-brand meter MUST show, for each manager, their positional mix so far and their average picks-versus-ADP so far, beside their profile's lean and reach. It MUST name the source of the profile (fitted from N drafts, or stated).
- **FR-020**: The on-brand meter MUST NOT give an on-brand / off-script verdict for a neutral seat, or for a manager with fewer than 3 picks.

### Key Entities

- **Pick insight**: one landed pick and everything worked out from it: player,
  manager, pick/round, slot filled, open slots after, ADP difference, pre-pick
  projected share, and the summary sentence.
- **Manager outlook**: the drafting manager's next pick number, their top
  projected candidates with shares, and where their profile comes from.
- **Position scarcity**: for each position, the starter-quality cutoff, the count
  left now, the expected count at your next pick (optional), and whether a run
  is flagged.
- **On-brand read**: for each manager, the positional counts and average ADP
  difference so far, the profile's lean and reach, where the profile comes from,
  and the verdict (or the reason there is none).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Across a full live (or replayed) draft, every card's "fills" slot and open-slot list matches that manager's roster column on the board, with zero mismatches.
- **SC-002**: The card's fit section appears within 1 second of the pick appearing in the feed.
- **SC-003**: No card or meter ever shows a projected figure taken from the wrong side of a pick. This is checked in a live-verification pass by comparing each shown figure with the projection it claims to come from.
- **SC-004**: When 3 or more picks land within 10 seconds, the user never has to dismiss more than one card.
- **SC-005**: At every pick, the scarcity meter's "left now" counts match a hand count of the remaining pool against the stated cutoff.
- **SC-006**: The card and both meters are readable without horizontal scrolling at 375px wide, in both sports.
- **SC-007**: With cards turned off, a full draft shows no cards, the meters still update, and the setting survives a page reload.

## Assumptions

- **Card, not a blocking modal.** It appears over the room but never locks the
  page (confirmed by the user).
- **Slot-based fit, not engine value.** Fit uses the same slot counting as the
  team strip and feed, per the 2026-09-09 decision. The engine's internal
  roster-need score stays internal.
- **Hand-set display constants, labelled as such rather than fitted:** the 25%
  "wide open" threshold (Story 2), the "under 5%" surprise threshold (Story 3),
  the 3-pick minimum for an on-brand verdict (Story 5), and what counts as on
  brand versus off script.
- **Starter-quality cutoff:** *(amended after planning)* the board's top
  teams × starting-slots players, across all positions. Shared slots (FLEX,
  G/F/UTIL) are then allocated by the board's own order rather than by a split
  rule we would have had to invent. The meter shows the cutoff it uses.
- **No new data sources.** No new ingest and no external news or rankings.
  Everything comes from boards, profiles and projections the app already has.
- **Out of scope:** the mock room, the pre-draft simulator, spoken read-outs of
  the card (the existing pick announcer already speaks picks), and the other
  items under Future ideas.

## Amendments after planning (2026-09-30)

Planning ([research.md](research.md)) found three things this spec assumed wrongly.
They are corrected in place above and listed here so the change is visible:

1. **"Out of date until the projection re-runs after the pick"** assumed a re-run
   that does not happen. Since `41b9426` (2026-09-11) the live room projects once
   per seat. Stories 2 and 3's model share, and Story 4's projected column, need a
   per-pick background re-projection, which the plan adds off the critical path
   (R1). The server's simulation permits (≥ 2 concurrent, 3s wait, then 429) mean a
   busy room will sometimes get "projection busy" on the card instead. That is
   honest, and it is measured in quickstart Q4.
2. **The starter-quality cutoff** changed from a per-position slot-demand rule to
   "the board's top teams × starters" (R6). The spec's version needed an invented
   split of shared slots, which in basketball (three shared slot types) would have
   decided most of the answer.
3. **"Expected left at your next pick"** appears only once the undrafted starter
   pool fits inside the engine's 75-deep availability snapshot, which is about
   pick 46 in a 12 × 10 league. Before that the figure would be silently
   truncated, so it is left out (R7).

## Clarifications

### Session 2026-09-30

- Q: Which picks raise a card? → A: Every manager's pick. *Amended the same day:* in the live draft room only, not the mock room or the pre-draft simulator. The first answer had said all three rooms.
- Q: Blocking modal or passive card? → A: A passive card. It closes itself, a newer pick replaces it, and it stays clear of your on-the-clock view.
- Q: Which extra ideas are in this feature? → A: Reach / surprise tags (Story 3), the position scarcity meter (Story 4) and the on-brand meter (Story 5). "Any other ideas" meant the draft experience in general, not only the per-pick card.
- Q: Is reversing the "no banner, no toast" decision acceptable? → A: Yes.

## Future ideas (not in this feature)

Kept here so they are not lost. None is committed to.

- **What that pick did to you**: before/after survival to your next pick for players at positions you need ("62% → 40%"). It needs two projections compared.
- **Model scorecard for the draft**: a running count of how often the pre-pick projection's top candidate was the actual pick.
- **End-of-draft recap**: each roster's filled and unfilled starters, the biggest reaches and falls, and the most surprising picks.
- **The same card in the mock room**: this was excluded by choice. The mock room holds no projection today.

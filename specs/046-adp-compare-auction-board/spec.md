# Feature Specification: ADP compare + live auction board

**Feature Branch**: `046-adp-compare-auction-board`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "this was pretty useful for drafts in general can we add this feature to site". The source brief is `claude/adp-compare-and-auction-board.md`. The working prototype is `claude/prototypes/auction-board.html`, used live on 2026-10-21.

## Context (measured before writing, 2026-10-21 → 2026-10-23)

- **A real league now runs an auction.** "Legacy Ball Knowers" (Sleeper NBA 2026, league
  `1415422393706455040`) drafted on 2026-10-21:
  - format: 10 teams, $200 budget, 23 roster spots (19 roster + 4 taxi), dynasty
  - result: 230 sales, $1,982 of $2,000 spent, 120 of them at $1

  `claude/auction-drafts.md` had said "don't build unless a real league switches to auction". That
  trigger has now happened, and the doc carries a dated amendment note.
- **The prototype was used live, and Allan asked for it in the site.** It's a static page that
  compares ADP sources and polls the Sleeper draft from the browser.
- **Measured against the real auction (one draft):**
  - The three price guides put players in nearly the same order as their real prices (rank
    correlation): Yahoo price 0.911, dynasty-ordered price 0.935, blend 0.950.
  - The top end sold 25–50% above every guide. Wembanyama went for $112 against a guide of $81,
    Jokić for $100 against $67–77.
  - The guides came from mostly 12-team, ~13-spot leagues. This league had 10 teams and 23 spots,
    so the same money was concentrated in fewer contested players.
- **Sources (each verified by calling it):**
  - **Sleeper's season projections** already carry a dynasty ADP and a redraft ADP per player
    (835 NBA players with a real dynasty ADP). The app fetches this payload today and ignores both
    fields.
  - **Yahoo's public read-only API** gives redraft ADP and average auction price ($200 budget) for
    186 NBA players. It needs no login, but it can't be called from a browser (no cross-origin
    access). **Yahoo publishes no dynasty ADP.**
  - **Sleeper auction picks** carry the sale price. This was confirmed on the real 230-pick draft.
- **What the app has today** (`origin/main` `5512cd7`):
  - Drafts store `draft_type`, but **no code reads it to filter anything**.
  - Picks have no price column, and the pick mapper never reads one.
  - The live draft poller (2s while drafting) and the multi-source ADP snapshot table both exist.

## Clarifications

### Session 2026-10-23

- No open questions. Allan asked for the prototype's behaviour in the site. The brief, written
  before this spec, settled the scope cuts and the design choices.

### Amended after review (2026-10-23)

The adversarial plan review (`plan-review.md`, 17 findings) changed these parts of the spec. None
changes what the feature is.

- **US2 AS2 was false as written (F6).** A Yahoo-favoured player's blended **rank** can get later
  as trust rises, because bigger-gap players overtake him: 20 players at default settings on
  2026-10-23 data, with a counterexample in research R6. It's restated as a property of his
  blended **value**. AS1 gains a tie-break.
- **The gap-tint cutoff was the wrong rule (F7).** "Past Yahoo's deepest ADP" hid 100%-drafted
  veterans (LeBron: Yahoo 38.9, dynasty 157.3). It's replaced by a Yahoo reliability rule: a Yahoo
  value counts only when at least 50% of Yahoo drafts took the player. This is hand-set and
  labelled. It applies to the tint and the blend alike.
- **Football (F11).** NFL is out of v1. The page is NBA-only, and the edge case is reworded.
- **Unknown sold players and unmatched Yahoo players (F4, F5)** need stored names. That's a data
  change, not a scope change.
- **Blend $ labels (F10)** now say what the rescale does and doesn't do. Yahoo's date reads
  "fetched on". Yahoo's leagues being mostly category leagues is stated as assumed.
- **US4 (F1–F3)**: the readers to refuse were wrong. The real completed-draft board stays, and
  auction leagues are routed to this page instead. The refused paths are the simulator, forking a
  draft into a mock, and manual pick entry. The stats leaderboard stops showing nomination numbers
  as draft slots.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Compare ADP sources for my league before a draft (Priority: P1)

Before any draft, snake or auction, a league member opens an ADP compare view for their league. It
lists every relevant player with:

- Sleeper dynasty ADP
- Sleeper redraft ADP
- Yahoo ADP
- Yahoo average auction price

Rows where Yahoo and Sleeper disagree by a large gap are tinted. Green means Yahoo drafts the
player earlier. Red means Sleeper dynasty drafts him earlier. The member can search, filter by
position, show only rookies, and sort by any column or by the biggest gap either way.

**Why this priority**: Allan said it's useful "for drafts in general", and it doesn't depend on the
draft format. On its own it's a usable pre-draft research tool.

**Independent Test**: Open the view for an NBA league with no draft in progress. Check that the
sources' numbers match the providers' own figures for a sample of players. Check that the
green/red tint follows the gap direction and the threshold.

**Acceptance Scenarios**:

1. **Given** an NBA league, **When** the member opens ADP compare, **Then** each player shows all
   four values, or a visible "not ranked" for a source that doesn't rank him. A player is never
   dropped because one source is missing.
2. **Given** the gap threshold is 20 picks, **When** Yahoo's ADP for a player is 53 picks earlier
   than Sleeper dynasty's, **Then** his row is tinted green and labelled with the gap ("Yahoo
   higher by 53"). The reverse case is tinted red.
3. **Given** the member raises the threshold, **When** a player's gap falls below it, **Then** his
   row loses its tint.
4. **Given** the view is open, **Then** it states, next to the data:
   - Yahoo's figures are redraft
   - the date each source was captured
   - how many players each source ranks

---

### User Story 2 - A price guide that leans toward Yahoo when sources disagree (Priority: P2)

The member sets how much to trust Yahoo when the two sources disagree, and the gap at which that
trust applies fully. The view gives each player a blended position and a blended price ("Blend
$"), rescaled to this league's teams, budget and roster spots. At 0% trust the blend is pure
Sleeper dynasty.

**Why this priority**: This was the feature Allan asked for mid-draft ("prioritize Yahoo rankings
if there is a big disparity"). It only means something on top of US1.

**Independent Test**: Set trust to 0% and check that the blend order equals Sleeper dynasty order.
Raise trust and check that a player Yahoo favours moves up, never down.

**Acceptance Scenarios**:

1. **Given** trust 0%, **Then** blended order equals Sleeper dynasty order exactly. Ties break by
   redraft ADP, then name, the same key in both orders.
2. **Given** a player Yahoo ranks earlier than Sleeper dynasty, **When** trust increases, **Then**
   his blended **value** moves earlier or stays put, and he never loses ground to a player Yahoo
   doesn't favour. *Amended after review (F6)*: his blended **rank** may still get later, because
   other Yahoo-favoured players with bigger gaps move faster. This is stated so nobody "fixes" the
   test by weakening it.
3. **Given** a 10-team, $200, 23-spot league, **Then** the Blend $ values over the players that
   league would roster sum to its total budget ($2,000), within $0.50 × 230 of whole-dollar
   rounding.
4. **Given** a player Yahoo doesn't rank, **Then** his blended position equals his Sleeper dynasty
   position, and the view says why.
5. **Given** the view is open, **Then** Blend $ is labelled as a derived guide, not market data,
   and both trust settings are labelled as hand-set.

---

### User Story 3 - Follow a live auction draft (Priority: P2)

When the league's draft is an auction, the view shows a live mode while the draft runs:

- one card per team with money left, spots left, and max bid (money left minus $1 for each other
  open spot)
- a latest-sales feed: player, price, buyer, and price compared with Blend $
- sold players marked or hidden in the player list, with their sale price and buyer

It updates on its own as players sell, at the cadence the app already uses for live snake drafts.

**Why this priority**: This is what the prototype was built for, and what Allan used live. It
needs US1's player list to hang off.

**Independent Test**: Replay the recorded 2026-10-21 auction (230 picks) through the live path.
After each pick, check every team's money left, spots left and max bid against values computed by
hand from the pick list.

**Acceptance Scenarios**:

1. **Given** an auction draft with no picks, **Then** every team shows $200 left, 23 spots and a
   $178 max bid.
2. **Given** the user's team buys a player for $84, **Then** within one poll cycle the team shows
   $116 left, 22 spots and a $95 max bid, and the player shows "Sold $84" with the buyer.
3. **Given** the recorded 230-pick auction is replayed, **Then** the final money left per team
   matches Sleeper's own totals, which add up to $18 left across the league.
4. **Given** a sold player the app has no record of, **Then** the sale still shows in the feed,
   using the name Sleeper sends, and still counts toward the buyer's budget.
5. **Given** a snake draft, **Then** no budget cards or price columns for sales appear.

---

### User Story 4 - Auction history doesn't corrupt snake-draft models (Priority: P1)

When an auction draft is ingested, nothing that models snake draft order uses its picks:

- manager reach bias
- positional tilt
- the observed-order board blend
- draft grades built on pick order

The order of an auction's picks is nomination order, not preference order.

**Why this priority**: Without this, ingesting "Legacy Ball Knowers" would quietly feed 230
nomination-ordered picks into manager profiles as if they were snake picks. That's the exact
"looks more certain than it is" failure this project exists to avoid. It must ship with US3,
because US3 is what makes ingesting auction picks routine.

**Independent Test**: Ingest the auction draft into a league that also has a snake draft. Check
that every manager's fitted profile is identical, field for field, to the profile fitted without
the auction.

**Acceptance Scenarios**:

1. **Given** a league with one snake and one auction draft, **When** profiles are fitted, **Then**
   only the snake draft's picks count, and each profile's sample size says so.
2. **Given** a manager whose only history is auction drafts, **Then** his profile shows as having
   no fitted snake history. It is never fitted from auction picks.

---

### Edge Cases

- **A source fails to refresh** (Yahoo down, or its response shape changes): the view shows the
  last good capture and its date. It never shows a blank column as if the source ranked no one.
  If no capture exists, the column says "unavailable".
- **Name matching**: a Yahoo player who can't be matched to a known player is listed as unmatched
  with his Yahoo values. He is never silently dropped (the prototype matched 186/186).
- **Yahoo's thinly drafted tail**: Yahoo averages a player's ADP only over drafts that took him,
  so its tail is squeezed. On 2026-10-23, ranks 100–185 sat between ADP 103 and 124, 57 players
  were under 50% drafted, and 19 had a $0 cost. A Yahoo value counts, for both the tint and the
  blend, only when the player's percent drafted is at least the reliability threshold (default
  50%, hand-set). Below it the row shows Yahoo's numbers greyed, with "drafted in N% of Yahoo
  leagues". *Amended after review (F7)*: this replaces a "deepest Yahoo ADP" cutoff that hid
  100%-drafted veterans.
- **Football leagues**: out of scope for v1. The page and its rail entry are NBA-only. Neither
  source was verified for NFL. *Amended after review (F11).*
- **A Yahoo player with no Sleeper dynasty ADP**: shown with his Yahoo values and "no dynasty ADP".
  He has no blend. (There were none on 2026-10-23.)
- **Auction draft paused or reset to pre-draft**: budgets return to the start and the sales feed
  empties. This follows the existing reset handling (spec 028).
- **A sale with no price** (a non-auction pick, or missing data): it's treated as $0. It's counted
  and shown as "price missing", never hidden.
- **Keepers and taxi**: roster spots come from the draft's own round count, which in the real
  league included taxi spots, not from the league's starting lineup.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST capture, per player and per capture date, Sleeper dynasty ADP and
  Sleeper redraft ADP from the season projections it already fetches. Players Sleeper marks as
  undrafted are stored as unranked, not as a number.
- **FR-002**: The system MUST capture Yahoo redraft ADP, average auction price and percent drafted
  for NBA, server-side, at most once a day. Each capture is recorded with its date, its source, and
  that it's redraft.
- **FR-003**: The system MUST match Yahoo players to known players using the existing player
  matcher, and MUST list unmatched players instead of dropping them.
- **FR-004**: The system MUST offer a league-scoped ADP compare view showing, per player: Sleeper
  dynasty ADP, Sleeper redraft ADP, Yahoo ADP, Yahoo average price, rookie flag, positions, team
  and years of experience.
- **FR-005**: The view MUST let the member search by name or team, filter by position, filter to
  rookies, and sort by each value and by gap in either direction.
- **FR-006**: The view MUST tint a row green when Yahoo's ADP is earlier than Sleeper dynasty's by
  at least the gap threshold, and red the other way. The threshold is user-adjustable, with a
  default of 20 picks. Every tinted row also shows the gap as text, so the meaning doesn't depend
  on colour alone. Only Yahoo values at or above the reliability threshold (percent drafted ≥ 50%
  by default, hand-set, labelled) take part, in both this rule and FR-007.
- **FR-007**: The view MUST compute a blended position per player. Yahoo's weight ramps linearly
  from 0 at no gap to the "trust" setting at a gap of "full trust gap" or more. Defaults are 75%
  and 30 picks, labelled as hand-set. Players Yahoo doesn't rank keep their Sleeper dynasty
  position.
- **FR-008**: The view MUST compute Blend $ by giving each blended rank a price from Yahoo's price
  curve, **rescaled to the league's teams × budget × roster spots**. The rescaled values over the
  players the league would roster MUST sum to the league's total budget. It MUST be labelled as
  derived, not market data.
- **FR-009**: The system MUST store each auction pick's sale price when ingesting picks, both live
  and in batch replay.
- **FR-010**: For drafts whose type is auction, the view MUST show live team budget cards (money
  left, spots left, max bid), a latest-sales feed, and per-player sale price and buyer. It updates
  through the existing live draft tracking at its existing cadence, without a second
  browser-to-Sleeper poll.
- **FR-011**: Max bid MUST equal budget − spent − (spots left − 1), with a floor of $0. Spots come
  from the draft's own round count.
- **FR-012**: Manager profile fitting, the observed-order board blend and any pick-order statistic
  MUST exclude picks from auction drafts. Profiles MUST report sample sizes that reflect the
  exclusion.
- **FR-013**: The view MUST show each source's fetch date ("fetched on") and ranked-player count,
  and MUST say in plain words:
  - Yahoo figures are redraft, and its leagues are mostly category leagues (assumed, not verified)
  - Blend $ is a derived guide
  - the blend settings and the reliability threshold are hand-set
  - Blend $ is Yahoo's price curve scaled to add up to this league's total budget, and it does
    **not** model how fewer teams and deeper rosters push money to the top. In the one auction
    measured, the top sold 25–50% above it.

  *Amended after review (F10).*
- **FR-014**: The view MUST be reachable from the league's navigation for NBA leagues, and from a
  league's draft room. It MUST work for leagues whose draft is snake, auction or complete.
- **FR-015** *(added after review, F1–F2)*: For an auction league, every entry point that leads to
  a snake-order page MUST lead to this view instead:
  - the rail's Draft board and Follow live
  - LeagueHome's draft links
  - the draft picker
  - old `/drafts/:id` and `/drafts/:id/live` links, which redirect

  "Mock it" MUST NOT be offered for an auction league.
- **FR-016** *(added after review, F3)*: The simulator, forking a draft into a mock, and manual pick
  entry MUST refuse auction drafts with a clear message. The stats leaderboard MUST NOT show an
  auction's nomination number or round as a draft slot.

### Key Entities

- **ADP capture**: one source's view of one player on one date: source (Sleeper dynasty, Sleeper
  redraft, Yahoo), sport, ADP or unranked, and for Yahoo, average price and percent drafted. It
  extends the existing multi-source ADP snapshot.
- **Auction sale**: an existing draft pick plus its sale price. It's linked to the draft, the buyer
  and the player, or to Sleeper's name when the player is unknown.
- **Team budget (derived, not stored)**: per team in an auction draft: budget, spent, spots left
  and max bid, computed from the draft's settings and its sales.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a sample of 20 players, every value in the compare view matches the provider's
  own figure on the same capture date.
- **SC-002**: Replaying the recorded 230-pick auction gives, for every team after every pick,
  money left, spots left and max bid equal to values computed by hand from the pick list (230 × 10
  checks, all equal).
- **SC-003**: During a live auction, a sale appears in the view within one poll cycle of the
  existing live tracking. That's 2 seconds while drafting, and is measured, not assumed.
- **SC-004**: Fitting manager profiles for a league with an ingested auction draft produces
  profiles identical to fitting without it.
- **SC-005**: At default settings, Blend $ over the league's rosterable players sums to the
  league's total budget, within $0.50 × rosterable spots (whole-dollar rounding). *Amended after
  review (F9)*: this is an identity check on the rescale, not evidence that the guide is right.
- **SC-006**: The compare view for an NBA league (about 400 players) is usable, with filters
  responding immediately, at phone width (375px) and desktop width.

## Assumptions

- **Yahoo is NBA-only for v1.** The football endpoint shape wasn't verified, so it's left out
  rather than guessed.
- **One Yahoo capture a day is enough.** ADP moves slowly. The prototype ran on a single capture
  pulled the night of the draft.
- **The rescaling method is a planning decision.** The default is proportional: scale Yahoo's
  curve so it sums to the league's total budget over the rosterable count. It's a stated, labelled
  choice, not fitted to the one real auction. Per the hard rules, nothing is tuned to make the
  2026-10-21 numbers match.
- **No nomination display.** Sleeper's public API doesn't expose the player currently up for bid,
  so updates arrive per sale.
- **No auction simulation.** No manager bid or nomination models, and no Monte Carlo for auctions:
  one auction in history is still pure prior (`claude/auction-drafts.md` items 2–3).
- **The blend weights are not fitted.** They're user settings with labelled defaults.
- **The database change is append-only.** It's the next migration after main's highest (V38 on
  2026-10-23, so V39), re-checked against in-flight branches before it's claimed.
- **The browser-only prototype stays as a reference.** Nothing about it is shipped.

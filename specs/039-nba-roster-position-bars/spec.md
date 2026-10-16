# Feature Specification: Basketball roster bars filled by position

**Feature Branch**: `039-roster-position-bars`

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "i would like for these bars to be filled by position like nfl" (screenshot: the Team strength page's "Roster projections" section for the NBA league "Ball Knowers" — every bar is a single empty grey track, while the PG/SG/SF/PF/C pills underneath are coloured).

## What is there now (read before the stories)

- The bar is already built as one segment per position group, each sized by that position's share of the roster's projected points, each carrying a `pos-<group>` class (`web/src/pages/LeagueAnalysis.tsx:441-455`).
- The stylesheet colours those segments for the six football positions only (`web/src/styles.css:3157-3163`: QB, RB, WR, TE, K, DEF). There is no rule for PG, SG, SF, PF or C, so on a basketball league each segment is present, correctly sized, and transparent — the grey seen in the screenshot is the empty track behind them.
- The same football-only list colours the "by position" rank matrix on the same page (`web/src/styles.css:3188-3193`, used at `LeagueAnalysis.tsx:723`), so basketball matrix cells draw no rank tint either.
- The basketball position colours already exist as theme tokens (`--pg`, `--sg`, `--sf`, `--pf`, `--c`, `styles.css:179-181`) and are what the pills (`styles.css:818`) and the draft board cells (`styles.css:949-953`) already use. Nothing new needs choosing.

This is the "two implementations of one rule" class from `claude/lessons.md`: the set of positions a sport has is known in several places, and two of the stylesheet's copies only ever listed football.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See a basketball roster's points split by position (Priority: P1)

A manager in a basketball league opens Team strength and reads the Roster projections bars the way a football manager already can: each bar split into coloured blocks, one per position, in the same colours as the PG/SG/SF/PF/C pills under it.

**Why this priority**: It is the whole request. Today the bar shows only length; the split it was built to show is invisible.

**Independent Test**: Open Team strength for the NBA league and confirm each bar is filled with coloured segments whose colours match the pills beneath it and whose widths follow the numbers beside those pills.

**Acceptance Scenarios**:

1. **Given** a basketball league with projections available, **When** the reader opens Team strength, **Then** every roster bar is filled end to end with segments, each in its position's colour, with no grey left inside a bar.
2. **Given** a roster with no projected points at a position (for example a roster with no SF, like rank 3 in the screenshot), **When** its bar renders, **Then** that position has no segment and its pill is absent from the row, as today.
3. **Given** a football league, **When** the reader opens Team strength, **Then** the bars look exactly as they do now.

---

### User Story 2 - Basketball rank matrix tinted by position (Priority: P2)

The by-position rank matrix on the same page tints each basketball cell by rank in that position's colour, as it already does for football.

**Why this priority**: Same root cause, same page, one line per position; leaving it would ship the half-fix the lessons file warns about.

**Independent Test**: Open the matrix for the NBA league; the rank-1 cell in each column carries the strongest tint of that column's colour and the last-ranked cell carries none.

**Acceptance Scenarios**:

1. **Given** a basketball league, **When** the matrix renders, **Then** each cell is tinted in its column's position colour with strength following rank.
2. **Given** a football league, **When** the matrix renders, **Then** nothing changes.

---

### Edge Cases

- A position group the stylesheet does not know (a future sport, or a "G"/"F"/"UTIL" group if one ever reached this list): the segment must still be visible rather than transparent, in a neutral colour, so a missing rule degrades to "unlabelled colour" and not "empty bar". Its width and tooltip are unaffected.
- ~~Light and dark themes~~ *(amended after plan review: the app has one theme — `styles.css` declares `color-scheme: dark` and has no light override — so there is only one track colour to read against.)* Segments must read against the dark track.
- The fallback colour must not match any real position's colour — a solid grey would pass for `--k` (kicker) on a football bar *(added after plan review)*.
- The bar is not readable by hue alone today (the numbers sit beside the pills); that stays true.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Each roster-projection bar segment for PG, SG, SF, PF and C MUST be filled with that position's existing colour — the same colour as its pill.
- **FR-002**: Each by-position matrix cell for PG, SG, SF, PF and C MUST be tinted with that position's colour at the rank-driven strength football cells already use.
- **FR-003**: A bar segment whose position has no colour rule MUST still render in a visible neutral colour distinct from the empty track.
- **FR-004**: Football bars and matrix cells MUST render unchanged.
- **FR-005**: An automated check MUST fail if any position the app treats as a real position (football or basketball) lacks a bar-segment or matrix-cell colour rule, so this gap cannot recur silently for one sport.
- **FR-006**: No data, request or backend change: segment sizes, totals, tooltips and pills already arrive correctly and stay as they are.

### Key Entities

- **Position group**: one of the sport's positions the projection is split by (football: QB, RB, WR, TE, K, DEF; basketball: PG, SG, SF, PF, C). Already sent by the page's data; this feature only colours it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the NBA league's Team strength page, 100% of roster bars show coloured segments and none shows grey inside the bar's filled length.
- **SC-002**: Each basketball segment's colour equals its pill's colour (checked by computed colour, not by eye).
- **SC-003**: On the NFL league's Team strength page, bar and matrix colours are identical before and after.
- **SC-004**: Removing any one position's segment colour rule makes the automated check fail.

## Out of scope

- Changing the position colours, the bar's layout, or the legend pills.
- Splitting multi-position basketball players differently — the bar already splits by each player's primary position, as the page's own "How this works" text says.
- Other pages' position colouring (the draft board already has the basketball rules).

## Assumptions

- "Like NFL" means the same treatment football already gets — segment fill in the position colour — not a new design.
- The basketball position groups this page receives are exactly PG, SG, SF, PF, C (seen in the screenshot and `ReplacementLevel.POSITIONS`); G/F/UTIL are lineup slots, not groups here, and are covered only by the FR-003 fallback.

# Feature Specification: Multi-position eligibility for basketball players

**Feature Branch**: `025-multi-position-eligibility`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description: "Count NBA players toward every position they are eligible for (multi-position eligibility)…" It was raised by spec 024's verification. That found the draft room's scarcity chips reading "SG 0/0 / SF 0/0", position filters under-listing SG and SF, and "Your team" reading "8 of 9 starters" after a full auto-draft. It builds on spec 024 (branch `024-draft-room-ux`).

## What is true today (measured 2026-10-09, before any change)

**1. The app knows only one position per player.** Each player the draft room sees carries a single position: the first entry of the player's stored position list. In the local database, 1,679 of 2,091 NBA players list more than one position, against 9 of 4,387 NFL players.

**2. That "first" position is alphabetical, not the player's real primary.**
- The position list comes from Sleeper's `fantasy_positions`, which Sleeper **always sorts alphabetically**: all 1,478 of the 1,478 active multi-position NBA players in Sleeper's payload.
- Sleeper also publishes the player's **primary** position separately. That matches the alphabetical-first entry for only **769 of 1,478 (52%)**.
- **Examples:**

  | Player | Shown today | Sleeper's primary | Eligible for |
  |---|---|---|---|
  | Anthony Edwards | PG | SG | PG, SG |
  | Devin Booker | PG | SG | PG, SG |
  | Jayson Tatum | PF | SF | PF, SF |
  | Kevin Durant | PF | SF | PF, SF |
  | LeBron James | PF | PF | PF, PG, SF |

- **The consequence:** SG can only appear "first" when it is a player's only position, and the same holds for SF whenever C, PF or PG is also listed.

**3. Measured on the top 36 NBA players by ADP:**

| | PG | SG | SF | PF | C |
|---|---|---|---|---|---|
| Counted by alphabetical-first (today) | 17 | **0** | **0** | 8 | 11 |
| Counted by eligibility (any listed position) | 18 | 8 | 7 | 17 | 11 |

The top-108 shapes include: PG/SG 20, PG 17, C 16, C/PF 14, PF/SF 14, PF/SF/SG 11, SF/SG 10.

**4. Two rules already disagree.** The backend's roster rules read **every** listed position: mock auto-pick, and the simulation's roster need. The draft room's team strip and "fills a need" tags read **only the first**. After a full auto-draft, the same room said "8 of 9 starters", for a roster the backend considered full. This is the "two implementations of one rule" bug class (AGENTS.md).

**5. The alphabetical-first position also feeds the simulation.** Each manager's fitted positional tilt and the board's position field are built from it. So changing what "primary" means for NBA players would change simulation numbers. The user's request rules that out unless it is decided explicitly.

## Clarifications

### Session 2026-10-09

- Q: Which position is "primary" for a multi-position NBA player, and may the simulation's own notion change? → A: **(C)** No single primary on labels. A multi-position player shows all eligible positions equally (e.g. "PG/SG"), and the simulation is unchanged. The simulation's alphabetical-first use (manager positional tilt, the board's position field) stays as it is and is recorded as a separate, measured follow-up.
- Q (from plan review): How should NBA pick runs be counted? → A: By family (Guard, Forward, Center). A pick counts only when all his positions sit in one family.
- Q (from plan review): What do compact board cells show for multi-position players? → A: A family code ("G", "F", "G/F", "F/C"), with full positions elsewhere and in the tooltip.
- Q: How does a multi-position player count in a scarcity pool? → A: **(A)** Fully, in every position he is eligible for. A PG/SG counts once in PG and once in SG, so pool totals can exceed the starter count, and the chips say "eligible" so nobody reads them as a partition.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scarcity that reflects who can actually fill a position (Priority: P1)

A manager in an NBA draft room glances at the position-scarcity chips to see how many starter-quality players are left at each position before their next pick. Every position that real players can fill should show a real count. SG and SF should never read zero, or vanish, just because of how a list is sorted.

**Why this priority**: This is the visible symptom that started it. The chips are on screen the whole draft, and today two of the five are hidden (since 024) or read 0/0.

**Independent Test**: Open the pre-draft live room for the 12-team NBA 2026 draft. Check that every basketball position shows a chip with a non-zero pool. Check that the counts follow the counting rule chosen below.

**Acceptance Scenarios**:

1. **Given** the 2026 NBA draft before it starts, **When** the scarcity chips render, **Then** PG, SG, SF, PF and C each show a chip, labelled as eligible counts, and no chip has a pool of zero.
2. **Given** a player eligible at two positions is drafted, **When** the chips update, **Then** the counts change exactly as the counting rule (FR-004) says, and a test reader can predict the new numbers from that rule alone.
3. **Given** a football draft, **When** the chips render, **Then** nothing changes from today. Football players are single-position, apart from 9 edge cases.

---

### User Story 2 - Position filters and labels that match eligibility (Priority: P1)

A manager filters the player list to SG to find a shooting guard. Every player eligible at SG should appear, including those who are also PGs or SFs. Each player's label should show the positions they can fill, not only one alphabetical pick.

**Why this priority**: Today the SG filter shows only players whose *alphabetically first* position is SG. In the top 108 that's almost nobody, so the filter is close to useless for basketball.

**Independent Test**: In an NBA draft room, filter the list to SG and to SF. Anthony Edwards and Devin Booker appear under SG, and Jayson Tatum and Kevin Durant appear under SF. Each also still appears under their other eligible position.

**Acceptance Scenarios**:

1. **Given** the NBA player list, **When** the user filters to SG, **Then** every listed player eligible at SG appears, including PG/SG and SF/SG players.
2. **Given** a player eligible at PG and SG, **When** his row, board cell, pick card or target chip renders, **Then** the label shows both positions equally, as "PG/SG", per FR-002. Neither is styled as primary.
3. **Given** the ALL filter, **When** the list renders, **Then** each player appears once. Multi-position players are never duplicated.

---

### User Story 3 - One answer to "does this player fill a need" (Priority: P2)

A manager's "Your team" strip and the "fills a need" tags should agree with how the room's own auto-pick and the simulation decide whether a player fits a lineup. A fully auto-drafted roster that the backend considers complete should not read "8 of 9 starters".

**Why this priority**: This is the documented rule disagreement. The fix is to make the room use eligibility the way the backend does, not to add a third rule.

**Independent Test**: Run a 12-team NBA mock and auto-finish it. "Your team" shows the starter count the backend's lineup rule produces for the same roster. Run it again for a hand-built roster with a G and an F slot filled by multi-position players.

**Acceptance Scenarios**:

1. **Given** a roster containing a PG/SG and an SF/SG player, **When** "Your team" renders, **Then** the slot assignment matches what the backend's lineup rule assigns for the same roster: same slots filled, same slots open.
2. **Given** an open SG slot, **When** a PG/SG player's row renders, **Then** it shows a "fills SG" tag. When only PG and UTIL are open, it shows "fills PG".
3. **Given** a completed 12-team NBA mock made entirely by auto-pick, **When** "Your team" renders, **Then** it reports the same filled-starter count as the backend lineup rule. That is 9 of 9 whenever the backend says 9.

---

### User Story 4 - Position runs that see every eligible position (Priority: P3)

The pick-run detector ("4 of the last 6 were PF") should count a run by eligibility, so a run of PF/SF forwards reads as the forward run it is.

**Why this priority**: It's a smaller signal than scarcity and filters, but it should use the same rule.

**Independent Test**: Feed six recent picks, four of them eligible at SF (PF/SF and SF/SG players). The detector reports an SF run under the counting rule.

**Acceptance Scenarios**:

1. **Given** six recent picks, four of them single-family forwards (SF, PF or SF/PF), **When** the detector runs, **Then** it reports a Forward run. An SG/SF pick counts toward no family. *(Amended after plan review.)*

### Edge Cases

- **A player with no stored positions,** such as a free agent with sparse data. He is shown without a position badge and counts toward no pool. He is never silently given a default position.
- **NBA payload entries whose only "position" is DEF,** an archive artifact. These are already dropped at ingest and stay dropped.
- **Football's 9 multi-position players.** Football keeps its single-position behaviour unless a rule below says otherwise. A football player's label is unchanged.
- **Older backend, split deploy.** A frontend that receives no multi-position data falls back to today's single position, with no crash and no blank badge.
- **A player whose stored position list lacks the Sleeper primary,** a data mismatch. The label shows what is stored. *(Amended after review: the promise to "log at ingest" is dropped. No task built it, and FR-002 = C doesn't use the Sleeper primary.)*
- **Positional rank labels ("PG2").** *Amended at plan time (2026-10-09). The first version of this bullet said these are "Sleeper's own rank and keep their current meaning". That was wrong.* The app computes the number itself, as a running count within each player's **alphabetical-first** position (research R3). A multi-position NBA player's badge therefore shows his eligible positions with **no rank number** ("PG/SG"). Single-position players keep "C2" and similar. No rank is invented per eligible position.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every player sent to the draft rooms MUST carry the full list of positions they are eligible for, in addition to the single position sent today. The extra field MUST be optional, so a frontend talking to an older backend keeps working.
- **FR-002** *(clarified: C)*: Draft-room labels MUST NOT present any one position of a multi-position NBA player as his primary.
  - All eligible positions are shown equally, in one fixed basketball order (PG, SG, SF, PF, C): e.g. "PG/SG", "SF/PF", "SG/SF/PF".
  - The existing single position remains on the wire for the simulation and for older clients. Labels just stop treating it as the player's primary.
  - The simulation's use of the alphabetical-first position is unchanged, and is recorded as a follow-up.
- **FR-003**: Position filters in the draft rooms MUST match a player when the filter's position is any of the player's eligible positions. The ALL view lists each player once.
- **FR-004** *(clarified: A)*: The position-scarcity pools MUST count each player **fully in every position he is eligible for**.
  - A pick removes him from every pool he was in.
  - The chips MUST say they count *eligible* players (e.g. "SG 8 eligible / 8"), and the starter-pool note MUST say that one player can count at more than one position. That way no one reads the chips as adding up to the starter count.
- **FR-005**: "Your team", "fills a need" tags and the open-slot logic in the draft rooms MUST use eligibility, and MUST agree with the backend's lineup rule for the same roster (US3). The two MUST NOT be two independently written rules that can drift. Either they share one rule source, or a parity test pins them to identical answers on a shared fixture set.
- **FR-006** *(amended after plan review, the user's decision)*: For NBA, the pick-run detector MUST count runs by **family**: Guard (PG/SG), Forward (SF/PF), Center.
  - A pick counts toward a family only when all his positions are in that family.
  - The copy reads "4 of the last 6 were guards".
  - Football is unchanged.
  - Measured on the 2025 draft: 26 of 163 windows, with 0 ties. Per-position eligibility gave 108 windows with 17 ties.
- **FR-007**: Labels for multi-position players MUST show their eligible positions (board cells, list rows, pick cards, target chips, the pick feed), following FR-002.
  - **NBA badges show no rank number at all** *(amended after review: the rank counts alphabetical-first positions only, e.g. Banchero "PF4" is 9th among PF-eligible players)*.
  - **Compact board cells** show a family code for multi-position players ("G", "F", "G/F", "F/C"), with the full list in a tooltip *(the user's decision)*.
  - **Colour:** no colour (pill, cell tint or lead hue) may single out one position of a multi-position player.
- **FR-008**: Football behaviour MUST be unchanged, except where a football player genuinely lists more than one position and the rule applies naturally.
- **FR-009**: This feature MUST NOT change simulation numbers or `config/weights.yml`. FR-002 is (C), so the simulation's inputs are untouched.
- **FR-010**: Spec 024's "hide 0-pool chips" note MUST remain correct. With eligibility counting, a hidden chip should become rare. The note's copy changes, because it currently says "no starter-pool players list these first".

### Key Entities

- **Player positions**: the ordered set of positions a player is eligible for (from Sleeper), plus Sleeper's stated primary position. Today only the alphabetical-first entry reaches the draft room.
- **Scarcity pool**: for each position, how many starter-quality players are eligible there, and how many remain, under FR-004's rule.
- **Lineup rule**: the one definition of which open roster slot a player can fill, shared by the backend and the room (FR-005).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** *(baseline amended after review)*: On the 2026 NBA draft (12 teams, pre-draft), all five basketball positions show a scarcity chip with a pool greater than 0, counted by eligibility. Expected: PG 38, SG 41, SF 37, PF 45, C 31 on the top 108. The baseline today is 4 of 5: only SG is hidden. The "SG and SF hidden" observation came from the 4-team draft.
- **SC-002**: The SG filter, on the top 108 NBA players by ADP, lists every player eligible at SG. A test checks this against the stored eligibility data and gets 100% recall. Today it is near 0%.
- **SC-003**: For 100% of a fixed set of at least 20 NBA rosters, including every one from a completed auto-drafted mock, the room's "Your team" filled-slot count equals the backend lineup rule's count.
- **SC-004**: Simulation numbers for an unchanged draft are identical before and after this feature: the same board and the same survival numbers for a fixed seed.
- **SC-005**: No football draft-room screen changes. A screenshot comparison of a football live room before and after shows no position-related differences.

## Assumptions

- **Scope.** This covers the three draft rooms (live, projection, mock) and the components they share. Other pages that show a player position (stat leaderboard, superlatives, weekly report, player page, roster management) keep their current single position. They are noted as a follow-up, not changed here.
- **Data source.** The full eligible list already exists in the database, so **no migration is needed** (FR-002 is C). Sleeper's stated primary is not stored and is not needed here.
- **Follow-up, not built here.** The simulation's alphabetical-first use (manager positional tilt in profile fitting, the board's position field) is wrong for 48% of multi-position NBA players. Fixing it would change simulation numbers, so it belongs in its own spec, with a before/after measurement.
- **Positional rank:** see the amended edge case. It is dropped from multi-position NBA badges and kept for single-position players.
- **Branch.** Built on spec 024's branch. It is not merged before spec 024, and not deployed before the 2026-10-10 NBA draft.

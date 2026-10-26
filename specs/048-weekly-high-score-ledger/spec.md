# Feature Specification: Weekly High-Score Ledger

**Feature Branch**: `048-weekly-high-score-ledger`

**Created**: 2026-10-25

**Status**: Draft (clarified 2026-10-25)

**Input**: User description: "ok i want a weekly spending tracker. we have a tradation that highest scorer gets a dollar from each leaguemate so i want a tracker to see who owes who at the end of the season and also a nice sheet to show who won and whos up."

## The rule being tracked

A league side bet, not a Sleeper setting: **each week, the manager with the highest
score collects a fixed stake (default $1) from every other manager in the league.**
Over a season that produces a running balance per manager and, at the end, a set of
debts between managers. The app already stores every roster's score for every week,
so the ledger is derived entirely from data the app has; nobody types in a result.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the season sheet: who won each week and who's up (Priority: P1)

A league member opens the ledger for their league and season and sees, at a glance:
a standings sheet ordering every manager by net balance (money won minus money
owed), with how many weeks each has won; and a week-by-week list showing that
week's winner, their score, and how much they collected.

**Why this priority**: This is the "nice sheet" — the thing people look at every
week and screenshot into the group chat. It is useful on its own even before anyone
settles a debt.

**Independent Test**: With a league that has the ledger turned on and at least one
decided week, open the ledger page and check that each decided week names the
highest scorer, and that the standings balances add up to zero across the league.

**Acceptance Scenarios**:

1. **Given** a 12-team league with 6 decided weeks, **When** a member opens the
   ledger, **Then** they see 6 week rows, each naming that week's top scorer and
   their score, and a standings sheet where each weekly winner is credited
   11 × stake for that week and every other manager is debited 1 × stake.
2. **Given** the same league, **When** the standings are summed across all
   managers, **Then** the total is exactly $0.00.
3. **Given** a manager who has won two weeks and lost four, **When** they view the
   sheet, **Then** their row reads 2 weeks won and a net of (2 × 11 − 4) × stake = +$18
   at a $1 stake.

---

### User Story 2 - Settle up: who owes who (Priority: P1)

At the end of the season (or any time during it), a member opens the settle-up view
and sees the list of payments that clears every balance: "Alex pays Jordan $3",
and so on.

**Why this priority**: This is the other half of the explicit ask ("who owes who at
the end of the season") and is what the tradition actually needs to close out.

**Independent Test**: Take the settle-up list for a season, apply every payment to
the standings balances, and check every manager ends at $0.

**Acceptance Scenarios**:

1. **Given** a season with decided weeks, **When** a member opens settle-up,
   **Then** every listed payment goes from a manager with a negative balance to one
   with a positive balance, and applying all of them brings every balance to $0.
2. **Given** a manager whose balance is exactly $0, **When** they view settle-up,
   **Then** they appear in no payment.
3. **Given** a season still in progress, **When** a member opens settle-up, **Then**
   it is clearly labelled "as of week N" and not presented as final.

**Settle-up shows both** (clarified 2026-10-25): a **pairwise** view, where what Alex
owes Jordan is netted from exactly the weeks between them (Jordan's collections from
Alex minus Alex's collections from Jordan), and a **fewest-payments** shortcut that
clears every balance with as few transfers as possible. The page says plainly that the
two lists clear the same balances and differ only in how many payments it takes, and
that the shortcut can route money between two managers who never collected from each
other.

4. **Given** a season, **When** a member compares the pairwise view and the
   fewest-payments list, **Then** both bring every manager to $0, and the
   fewest-payments list has no more payments than the pairwise one.

---

### User Story 3 - The commissioner turns the ledger on and sets the stake (Priority: P2)

Not every league has this tradition. The league's commissioner turns the ledger on
for their league and sets the stake (defaulting to $1). Until it is on, the league
shows no money figures anywhere.

**Why this priority**: Without it the app would print debts for leagues that never
agreed to the bet, which is exactly the kind of false confidence this project
avoids. It is P2 only because the P1 views can be built and tested against a league
with the setting forced on.

**Independent Test**: As the commissioner, turn the ledger on with a $2 stake and
confirm every figure doubles relative to $1; as a non-commissioner, confirm the
control is not offered.

**Acceptance Scenarios**:

1. **Given** a league with the ledger off, **When** any member visits the league,
   **Then** no ledger figures or ledger navigation appear, apart from the
   commissioner's control to turn it on.
2. **Given** the commissioner sets the stake to $2, **When** anyone views the sheet,
   **Then** every amount reflects $2 per payer per week.
3. **Given** a member who is not the commissioner, **When** they view the ledger,
   **Then** they cannot change whether it is on or the stake.

---

### User Story 4 - Who's leading this week (Priority: P3)

While a week is still being played, the sheet shows who currently has the highest
score, clearly labelled as not yet decided, and that week does not move anyone's
balance until it is final.

**Why this priority**: Nice for mid-week trash talk, but the tradition only pays on
decided weeks, so it is the least essential slice.

**Independent Test**: During an in-progress week, open the sheet and confirm the
current leader is shown as provisional and the standings match the previous decided
week exactly.

**Acceptance Scenarios**:

1. **Given** week 7 is in progress, **When** a member opens the sheet, **Then** it
   shows "leading week 7 (not final)" with the current top scorer, and the standings
   and settle-up exclude week 7.
2. **Given** week 7 becomes final, **When** the sheet is next loaded, **Then** week 7
   moves into the decided list and balances update.

### Edge Cases

- **Tie for the week's high score** (clarified 2026-10-25): **co-winners split the
  stake.** Every manager who is not a co-winner still pays one stake in total, divided
  evenly among the co-winners; co-winners pay each other nothing. The weekly pot is the
  same size as an untied week. Amounts are kept to the cent; when a stake does not
  divide evenly (a $1 stake across 3 co-winners), the leftover cent goes to co-winners
  in a fixed, stated order, so balances still sum to exactly zero. That ordering is a
  hand-picked convention, and the sheet labels it as one.
- **Which weeks count** (clarified 2026-10-25): **regular season only.** Playoff weeks
  move no money and are not listed as ledger weeks; the sheet states the regular-season
  week range it covers.
- **A roster with no manager (orphaned/unowned) for some weeks.** It still scores, so
  it can still win or owe; it is shown under the roster's name with a note that it
  had no manager, rather than silently dropped (dropping it would change everyone
  else's numbers).
- **A manager change mid-season.** Balances follow the roster's owner for each week
  the week was played, so the outgoing manager keeps what they won or owed while
  they held the team.
- **A week where every score is zero / no data was ingested.** That week is not
  decided and moves no money; it is listed as "no scores recorded" rather than
  crowning an arbitrary winner.
- **Stat corrections after a week is final.** The ledger is recomputed from stored
  scores on every view, so a corrected score that changes the week's winner changes
  the ledger too; there is no frozen copy to go stale.
- **Stake changed mid-season.** One stake applies to the whole season; changing it
  re-prices every week. (Assumption, see below.)
- **Odd league sizes / leagues of any size.** An untied winner collects (N − 1) × stake
  where N is the number of rosters that scored that week.
- **Past seasons.** A past season can be viewed once the ledger is on, computed the
  same way, labelled with the season.

## Clarifications

### Session 2026-10-25

- Q: When managers tie for the top score, how is the week paid? → A: Co-winners split
  each payer's stake; the pot is the same size as an untied week.
- Q: Which weeks count? → A: Regular season only.
- Q: How is "who owes who" shown? → A: Both — pairwise netted debts and a
  fewest-payments shortcut.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The ledger MUST be off by default for every league and MUST show no
  money figures for a league until it is turned on.
- **FR-002**: Only the league's commissioner MUST be able to turn the ledger on or
  off and set the stake; the stake defaults to $1.00 and MUST be a positive amount in
  whole cents.
- **FR-003**: For each decided week of a season, the system MUST identify the
  highest-scoring roster from the stored weekly scores, using the same "decided week"
  rule the rest of the app uses.
- **FR-004**: The weekly winner MUST be credited stake × (number of other rosters
  that scored that week), and each of those other rosters MUST be debited one stake.
- **FR-005**: The system MUST show a standings sheet listing every manager in the
  league for the season with weeks won, total collected, total paid, and net balance,
  ordered by net balance, highest first.
- **FR-006**: The net balances across all managers in a season MUST sum to exactly
  zero.
- **FR-007**: The system MUST show a week-by-week list with, per decided week: the
  winner, their score, the runner-up's score (margin), and the amount collected.
- **FR-008**: The system MUST show settle-up two ways: (a) pairwise — for each pair of
  managers, the netted amount one owes the other from the weeks between them; and (b)
  fewest payments — a list of transfers (payer, payee, amount) that brings every
  manager to zero using no more transfers than the pairwise view. Both MUST clear the
  same balances.
- **FR-009**: Settle-up and standings MUST state which week they are current
  through, and MUST say whether the season is final or still in progress.
- **FR-010**: An in-progress week MUST NOT move any balance; it MAY be shown as a
  provisional "currently leading" line, labelled as not final.
- **FR-011**: When two or more rosters tie for the high score, each non-winner's
  single stake MUST be split evenly among the co-winners, to the cent, with any
  leftover cent assigned in a fixed documented order; the sheet MUST mark the week as a
  tie and name every co-winner.
- **FR-012**: Only regular-season weeks MUST count; playoff weeks MUST move no money,
  and the sheet MUST state the week range included.
- **FR-013**: The ledger MUST be available for both NFL and NBA leagues, using each
  sport's own week structure.
- **FR-014**: Any league member MUST be able to view the ledger once it is on; it is
  scoped to that league like every other league page.
- **FR-015**: The ledger MUST be derived from stored weekly scores on read; it MUST
  NOT require anyone to enter weekly results by hand.

### Key Entities

- **Ledger setting (per league)**: whether the ledger is on, and the stake per payer
  per week. Owned by the commissioner.
- **Weekly result (derived)**: season, week, winning roster(s), winning score,
  runner-up score, number of payers, whether it was a tie, whether it is decided.
- **Manager balance (derived)**: per manager per season — weeks won, collected,
  paid, net.
- **Pairwise debt (derived)**: for each pair of managers, the net amount one owes the
  other from the weeks between them.
- **Payment (derived)**: payer, payee, amount — one line of the fewest-payments list.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every decided week checked by hand against Sleeper's own weekly
  scores, the ledger names the same winner (100% agreement on at least one full real
  season of a real league).
- **SC-002**: Net balances sum to exactly $0.00 for every season of every league
  where the ledger is on.
- **SC-003**: Applying every settle-up payment — pairwise or fewest-payments — leaves
  every manager at exactly $0.00.
- **SC-004**: A league member can find this week's winner and their own current
  balance within 10 seconds of opening the ledger page, on a phone.
- **SC-005**: A league with the ledger off shows no dollar amounts anywhere in the
  app.

## Assumptions

- The stake is in US dollars and one stake applies to a whole season; changing it
  re-prices every week of that season rather than only future weeks (simplest rule,
  matches how a group chat would recompute it).
- The app does not move or collect money and does not track whether a debt has been
  paid; it only computes who owes what. Marking debts as paid is out of scope for
  this spec.
- "Highest scorer" means the highest weekly total for the roster's starting lineup
  as Sleeper scores it — the same figure the app already uses for weekly records and
  the record book — not bench points.
- "Commissioner" means the same commissioner role the app already uses for
  commissioner-only actions (awards list, power-ranking ballots).
- Leagues imported from other providers get the ledger only to the extent their
  weekly scores are stored the same way; no provider-specific work is in scope.
- No backtesting or prediction of who will win future weeks is in scope.

## Amended during planning (2026-10-25)

Checked against the code (research.md); these win over the text above.

- **P1 (R6) — manager change mid-season.** The edge case "balances follow the roster's
  owner for each week" can't be met: the app stores one manager per roster per season,
  not per week. The ledger follows the **roster**, labelled with its season manager, the
  same attribution the record book uses.
- **P2 (R10) — navigation when off.** FR-001's "no ledger navigation when off" is dropped.
  The rail is declared per sport and the commissioner needs a page to turn the ledger on,
  so "Weekly pot" is always in the rail; when off, the page shows no money, only a
  one-line explanation and (for the commissioner) the turn-on control. SC-005 is
  unchanged: no dollar amounts anywhere while off.
- **P3 (R5) — "fewest payments".** A provably minimal list is NP-hard in general. The
  shortcut is a short list that **never uses more payments than the pairwise view** (FR-008
  and US2 scenario 4 read that way).
- **P4 (R3) — league with no recorded playoff start.** Every final week is counted and the
  page says so, rather than hiding the ledger.

## Amended after review (2026-10-25)

From plan-review.md; plan.md's A1–A15 carry the detail.

- **R1 (F7) FR-001 / SC-005**: "no money figures" means no **weekly-pot** amount (stake, balance,
  pot, payment) while the ledger is off, except the commissioner's own stake control. The app
  already shows FAAB and auction dollars for every league; those are untouched.
- **R2 (F8) US3 independent test**: a $2 stake doubles every figure for **untied** weeks; a tied
  week follows the cent rule (a 3-way tie is 34/33/33¢ at $1 and 67/67/66¢ at $2).
- **R3 (F1) US2 scenarios 1–2** apply to the shortcut list. In the pairwise view a roster with a
  positive balance can still owe another, and a $0 roster can appear.
- **R4 (F9) ESPN leagues are out of scope for v1**: the page shows "The weekly pot follows Sleeper
  leagues only", like Expected wins and Awards. This replaces the Assumption "Leagues imported from
  other providers get the ledger only to the extent...".
- **R5 (F5) FR-005**: every roster of the season is listed from week 1, with zeros before any
  decided week.
- **R6 (F6) Weeks not yet final or missing** are listed with that status, not dropped; "through
  week N" counts only an unbroken run of decided weeks.

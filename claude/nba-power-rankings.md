# Power rankings for basketball: the two opinion modes

Status: **built and verified live 2026-09-15** on branch
`nba-opinion-power-rankings`. Every fact marked "measured" was read off the live
Sleeper API or the local DB that day. Read
[what live verification found](#9-what-live-verification-found) before trusting
the plan above as a description of what shipped -- three things moved.

**Scope, decided with Allan: league-member ballots and the commissioner
ordering. Nothing computed.** Box score, the week-0 preseason baseline and
playoff odds are explicitly out — see [§6](#6-what-stays-dark-and-why-thats-fine).

That scope is a genuinely good fit, for a reason worth stating: **both opinion
modes are orderings of managers and need no roster data at all.** They do not
read the board, the players, the lineup model or a single scored week. Which is
why basketball can have them *today*, five weeks before the NBA season starts
and before its draft has even happened.

## 1. The one-paragraph version

Two opinion modes, both already sport-blind in their own code
(`MemberRankingService`, `RankingBallotRepository`, `Ranker`, `RankBoard` —
none of them mentions `Sport`). Three `Sport.NFL` gates stand in front of them,
and all three exist for one reason, which each of them states in its own
comment: `PowerRankingService.nflState()` hardcodes `sleeper.state("nfl")` on a
single line. Fix that line, drop the three gates, remove one frontend gate, and
**re-ingest the NBA chain** — without the last one, all twelve managers are
shown a vote board of anonymous "roster 1…12" chips and told voting is closed.

## 2. Measured: the blocker is data, not code

| league | sport | season | `league_member` | `roster_season` |
|---|---|---|---|---|
| 212 | nba | 2024 | **0** | 12 |
| 211 | nba | 2025 | **0** | 12 |
| 210 | nba | 2026 | **0** | 12 |
| 5 | nfl | 2025 | 12 | 12 |
| 4 | nfl | 2026 | 12 | 12 |

`league_member` is empty for every NBA league, because that chain's ingest last
ran **before V9 added the table**. It is written by ingest and only by ingest.
Both opinion modes read it, and they read it differently, which produces a
specific and quite misleading broken state. Traced through the actual code:

- `MemberRankingService.members()` walks **live Sleeper rosters** and joins
  `league_member` only for the display name, avatar and team name. So the board
  renders twelve rows — but `manager`, `avatarId` and `teamName` are all null,
  and `teamName` falls back to `"roster " + rosterId`. **Twelve anonymous chips.**
- `isMe` still resolves (it goes through `manager.sleeper_user_id`, and the
  `manager` table *is* populated), so `ballotBlockState`'s `amMember` check
  passes.
- `canSubmit` calls `leagueMembers.isMember(...)` → **false for all twelve**.
- `canCommission` calls `leagueMembers.isCommissioner(...)` → false, and
  **fails closed**, so nobody but the configured app owner can save a
  commissioner ranking. `commissionerKnown` comes back false, and the page
  correctly says "no commissioner detected for this league — re-run league
  ingest".

Net: `ballotBlockState` returns **`'voting-closed'`** — every NBA manager is
shown a board of unnamed rosters and told voting is shut. Nothing in that
sentence is a code bug; it is all one missing ingest.

**So step one is `POST /api/ingest/league-history/1339351318115946496`** (the
2026 chain head; `leagueChain` walks back to 2025 and 2024 on its own). It fills
`league_member` for all three seasons, including `is_commissioner` — read as
`Boolean.TRUE.equals(u.get("is_owner"))`, because the flag is **absent**, not
false, on the eleven non-commissioners.

## 3. Measured: `/state/nba` works, and says `week: 0`

```
GET /state/nba → {"week":0,"leg":0,"season":"2026","season_type":"off",
                  "season_start_date":"2026-10-20","display_week":0}
GET /state/nfl → {"week":2,"leg":2,"season":"2026","season_type":"regular",
                  "season_start_date":"2026-09-09","display_week":1}
```

All three fields `nflState()` reads are present and correctly typed for `nba`,
and `SleeperClient.state(String sport)` **already takes the sport as a
parameter** — the hardcoding is one string literal at the call site.

But `week: 0` is the whole ballgame for this scope, because **both opinion modes
are gated to the current week only**, deliberately, so nobody backdates a
ranking after seeing how the games went:

```java
// submitBallot
if (body.week() != state.week()) return 400;   // "current week only"
// commissioner
if (!body.week().equals(state.week())) return 400;
```

On basketball today the current week *is* 0. So: **if week 0 is not allowed,
neither mode can be used at all until 2026-10-20**, and the feature ships dead
for five weeks.

### The decision: allow it, and say why in the code

A commissioner ordering and a member ballot are *stated opinions*. They need no
data to be honest — which is exactly why this scope works before the NBA draft
— and a preseason power ranking is a thing people actually want. Concretely:

- `unique (league_id, season, week, kind)` already keeps a `COMMISSIONER` row at
  week 0 from colliding with the reserved `COMPUTED_REALIZED` preseason
  baseline. Different `kind`, no conflict.
- `ranking_ballot` is keyed `(league_id, week, manager_id)` with no `kind` at
  all. Nothing to collide with.
- The UI **already renders week 0 correctly**: `weekPhrase(0)` → "Preseason",
  `weekShort(0)` → "preseason", `weekTitle(0)` → "Preseason", and
  `sinceLabel` guards `tableWeek > 0`. This was built for the baseline and
  costs nothing to reuse.

So the rule to write down is: *the current week is whatever that sport's state
says, including 0; the anti-backdating gate is "equals the current week", never
"is at least 1".*

### Trap: week 0 is falsy

Every guard in this path must be a `null`/`!=` check, never a truthiness check.
Verified clean on the two that exist today —
`week != null ? week : state.week()` in `ballot()`, and
`week == null ? '' : '?week=' + week` in `getBallot()` — **but any new code
written for this feature can reintroduce it in one character.** A `if (!week)`
or `week || currentWeek` anywhere in the ballot path silently sends basketball
to the wrong week all offseason, and football will never fail on it, because
football is never at week 0 during a season. This is the same shape as the
plain-snake default in `claude/lessons.md` #16: a football-shaped assumption
that nothing fails on.

## 4. The four changes

| # | Where | Change |
|---|---|---|
| 1 | `PowerRankingService.java:66-67` | `NflState` → `SportState`; `nflState()` → `sportState(Sport)`; body becomes `sleeper.state(sport.code())`. **Four call sites**, all in `LeagueHistoryController` (`:229`, `:372`, `:482`, `:588`), and every one already holds the `LeagueRow` — the sport is in scope at all four with nothing to thread through. No behaviour change for football: `Sport.NFL.code()` *is* `"nfl"`. |
| 2 | `LeagueHistoryController.java:378` | `canSubmit`: drop `&& row.sport() == Sport.NFL`. |
| 2 | `LeagueHistoryController.java:463` | `POST /ballot`: drop the `sport != NFL` → 400. |
| 2 | `LeagueHistoryController.java:235` | `GET /power`: drop the `if (row.sport() == Sport.NFL)` around the MEMBER entries. **Required, not cosmetic** — without it the votes are collected and then never rendered. |
| 3 | `LeagueRailSection.tsx:60,131`, `DraftPicker.tsx:446` | Remove the `sport === 'nfl'` gate so the page is reachable at all. |
| 4 | `api.ts:778,795`, `PowerRankings.tsx:491,512` | Wire field `nflState` → `sportState`. Two reads in the page, one type. |

The commissioner endpoint (`:588`) has **no sport gate of its own** — it is
already sport-blind apart from `nflState()`, so change 1 finishes it.

Each of the three gates in change 2 carries a comment explaining that it exists
because `nflState()` hardcodes the sport. Change 1 removes the premise, so the
comments go with the gates rather than being left behind to lie.

## 5. Two things that fall out of unlocking the page

**5a. History rides along, free.** The rail and home card gate History and Power
rankings behind *one* `sport === 'nfl'` check covering both links. Removing it
unlocks both; splitting it to unlock only one is strictly more code. The
`/history` endpoint is already fully sport-agnostic and the NBA 2024/2025
standings are already in the DB, so this is a freebie, not scope.

**5b. The Compute button will be sitting on a page it can damage.** Not a goal
of this scope, but unavoidable once the page is reachable, so it needs five
lines. Measured live: `GET /league/1339351318115946496/rosters` returns all
twelve 2026 NBA rosters with **`players: []`** — the league is `pre_draft`. Walk
`computePreseasonBaseline` against that: every roster scores `0.0`,
`rankDescending` returns twelve non-empty entries tied at rank 1, and it saves.
`computeWeek0IfMissing` then — correctly, by its own design — **never overwrites
week 0 again**. One press of Compute permanently freezes this league's preseason
baseline as a twelve-way tie at zero, unrecoverable through the UI.

Guard: refuse to write week 0 when no roster has a single board-eligible player,
and return the reason on the compute response the way `realizedSkipped` already
is (`week0Skipped: "every roster is empty — this league has not drafted yet"`).
Same discipline `PlayoffOddsService` already applies to zero scored weeks: *"the
honest answer before week 1 is scored is no answer."*

Write it sport-neutrally. A football league reached before its draft has exactly
the same shape; basketball is merely the first to get there, because its season
starts five weeks later.

## 6. What stays dark, and why that's fine

On an NBA league the computed modes have nothing to show, and the page handles
that without special-casing:

- `teamCount` derives from `data.entries`, which will be empty → the hero,
  ladder and bump chart render empty until the first ballot lands. After that
  they fill from MEMBER entries alone, which is exactly the mode Allan asked
  for.
- `ballotSeed` falls back through `seedOrder(...)`: stored ballot → box score →
  **the member list**. With no box score it seeds the drag board from the live
  member list, which is the correct behaviour and already written.
- `season` falls back to `Number(sportState.season)` when entries are empty →
  `2026`, which matches league 210's own season. Correct.

Explicitly **not** in scope: box-score rankings for NBA, the preseason baseline
as a feature (only the guard above), playoff odds, and any per-sport tuning of
the odds model.

## 7. Acceptance criteria

1. After the re-ingest, `league_member` holds 12 rows for each NBA season and
   **exactly one** carries `is_commissioner`.
2. `GET /api/leagues/{nbaId}/ballot` returns twelve members with **real names
   and avatars**, not `"roster 1".."roster 12"`.
3. A signed-in NBA league member gets `canSubmit: true` and
   `week: 0` — i.e. `ballotBlockState` is `'ok'`, not `'voting-closed'`.
4. That member submits a ballot; it persists; re-submitting **replaces** rather
   than duplicating (the `(league, week, manager)` unique constraint).
5. A non-member gets 403, an anonymous caller gets 401. Same two answers
   football gives.
6. The NBA commissioner — and only they, plus the configured app owner — can
   save a commissioner ordering at week 0.
7. Once ≥1 ballot exists, `GET /power` returns MEMBER entries for that week and
   the page renders the league-vote ladder from them.
8. **Football is unchanged**: the (Foot) Ball Knowers page shows the same weeks,
   ranks, ballots and odds as before the branch, and its ballots still land at
   the real NFL week (2 as of writing), not 0.

## 8. One stale comment to delete on the way

`Sport.java:7` still reads `NBA("nba");   // seam only; not implemented in v1`.
That has been false since the multi-sport merge and will mislead anyone who
greps for what basketball supports.

## 9. What live verification found

Backend 384 tests / 0 failures / **0 skipped** (integration tests really ran --
check the skip count, not the word SUCCESSFUL), frontend 215/215, then the whole
path driven in a browser against the real Ball Knowers NBA league.

**The guard had to be narrowed, and a test caught it.** §5b as planned refused
when no roster had a *board-eligible* player. That broke
`aPlayerNotOnTheBoardIsExcludedAndNotedRatherThanCrashing`, correctly: "this
app's board does not cover these players" is a different state from "nobody has
drafted", with a different fix, and only the second is the refusal's business.
The shipped guard counts **rostered** players, before the board and injury
filters. The refusal message would otherwise have told a league with an
out-of-date board to go and draft.

**The guard then got verified by accident, which is the best kind.** A misclick
hit "Recompute" on the real pre-draft NBA league through the actual UI -- the
exact gesture that, before this change, would have frozen a twelve-way tie at
zero into a write-once row. Checked immediately after: `week0: 0`, a reason on
the response, and **no `COMPUTED_REALIZED` row in the table**.

**Two "week 0" strings survived the first pass, because they only exist in
states you have to be in to see.** The page already rendered "Preseason"
everywhere the plan looked, so the rename read as done. It was not: the compute
button interpolated `Recompute week ${currentWeek}` and the ballot modal's title
interpolated `Your week ${currentWeek} ballot` -- the second only visible with
the modal open, which a page-text scan does not do. Both now go through the same
helper (`weekIn` / `weekPhrase`), which is also what the backend's `weekLabel`
does, so a save confirmation and a server refusal word it the same way.

**The rail gate was hiding a third thing.** `LeagueRailSection`'s `{nfl && ...}`
wrapped History, Power rankings **and "Mock it"** in one fragment -- so the rail
had been hiding the mock-draft entry point for basketball ever since NBA mocks
shipped, while the home card offered it. Removing the gate fixed that too.

**Measured end to end on the real league** (all verified, then the two rows the
verification itself created were deleted so nobody inherits a fabricated
ranking):

| Check | Result |
|---|---|
| `league_member` after re-ingest | 12 per NBA season, exactly 1 commissioner each |
| `GET /ballot` | 12 members with real names, avatars and team names |
| `canSubmit` / `canCommission` / `week` | `true` / `true` / `0` |
| Ballot submitted at week 0 | saved; resubmit **replaced** (1 ballot, 12 entries) |
| Backdating to week 1 | `"ballots can only be submitted for the preseason -- no backdating"` |
| Anonymous / non-member | 401 / 403, unchanged |
| Commissioner save at week 0 | `{"saved":12}`; week 3 refused with the preseason wording |
| `GET /power` | 12 MEMBER entries at week 0, rendered on the page |
| Compute on the undrafted league | `week0: 0` + reason, nothing written |
| Football (`1346366555759341568`) | week 2, `canSubmit: true`, all prior snapshots intact, button still reads "Recompute week 2" |

## 10. The rule this feature must not break

**Do not add a `sport` request parameter to any of these endpoints.** Every one
is addressed by a Sleeper league id, and `league.sport` is the answer. A
defaulted `?sport=` on a league-scoped route asserts a *rule* ("this is
football") while looking like it asserts a value — the exact bug that wrote a
basketball seat's note onto that manager's football row
(`claude/merge-review-multi-sport.md`). The sport is derived from the league
row, never passed.

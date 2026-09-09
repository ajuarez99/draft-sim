# Power rankings: member ballots and a drag-to-rank board

Requested 2026-09-09. Two things, one surface:

1. **Give both hand-made modes a real input.** Today the commissioner ranking is a
   table of `<input type="number">` boxes ([PowerRankings.tsx:412-433](../web/src/pages/PowerRankings.tsx)).
   Replace it with a drag board: the league's members appear as chips in a tray,
   you drag them into numbered slots.
2. **Build mode 2 — league-member ballots** — the thing `claude/league-suite.md`
   deferred to "Phase B" behind auth that has since arrived. Every member submits
   their own ordering with the same drag board; the page shows the room's average.

The second is the feature. The first is what makes both of them worth opening.

---

## What is there now

**Backend.** `power_ranking` / `power_ranking_entry`
([V5__league_history_and_power_rankings.sql:44-70](../backend/src/main/resources/db/migration/V5__league_history_and_power_rankings.sql))
store one snapshot per `(league, season, week, kind)` for three kinds:
`COMMISSIONER`, `COMPUTED_MARKET_VALUE`, `COMPUTED_REALIZED`.
`PowerRankingService.saveCommissionerRanking`
([PowerRankingService.java:180](../backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java))
takes an ordered `rosterIds` list and writes ranks 1..n.
`LeagueHistoryController` exposes `GET /power`, `POST /power/compute`,
`POST /power/commissioner` — all scoped by `LeagueMembership.canSee`, none
attributed to a person.

**Frontend.** [PowerRankings.tsx](../web/src/pages/PowerRankings.tsx) — a
hand-rolled SVG bump chart (rank on y, week on x, `hueFor()` per manager,
`segmentsOf()` already breaks lines across missing weeks), a three-way mode
segmented control, an all-teams/one-team view toggle, and the number-input
commissioner editor.

**Identity, which is new since `league-suite.md` was written.** `X-Sleeper-User`
([api.ts:193](../web/src/api.ts)), `currentUserId()` ([user.ts](../web/src/user.ts)),
and `LeagueMembership` ([LeagueMembership.java](../backend/src/main/java/com/ballknowers/draftsim/store/LeagueMembership.java))
— the single definition of "which leagues are this manager's". `league-suite.md`'s
auth-split table says mode 2 needs "magic-link auth"; it does not any more. It
needs exactly what shipped: an unverified-but-attributed Sleeper user id, and the
same honesty about what that is worth.

**Two things already available and unused.** `SleeperClient.leagueUsers()`
([SleeperClient.java:39](../backend/src/main/java/com/ballknowers/draftsim/ingest/SleeperClient.java))
has no caller. Sleeper's `/league/{id}/users` carries `is_owner` per member —
which is the commissioner flag — and `metadata.team_name`. Both are a read away.

**A limitation worth naming before designing around it.** The current
commissioner editor renders only `if (standings && standings.length > 0)`, and
`standings` comes from `roster_season`, which is populated by league-history
ingest. A league whose history has never been ingested has *no way to rank
anything*. The new board must not inherit that: its member list comes from
`sleeper.rosters()` + `leagueUsers()`, which work on a league created yesterday.

---

## Storage

### Ballots (new, V9)

```sql
create table ranking_ballot (
    id            bigserial primary key,
    league_id     bigint not null references league (id) on delete cascade,
    season        int    not null,
    week          int    not null,
    manager_id    bigint not null references manager (id) on delete cascade,
    submitted_at  timestamptz not null default now(),
    unique (league_id, season, week, manager_id)
);

create table ranking_ballot_entry (
    id         bigserial primary key,
    ballot_id  bigint not null references ranking_ballot (id) on delete cascade,
    roster_id  int    not null,
    rank       int    not null,
    unique (ballot_id, roster_id),
    unique (ballot_id, rank)
);
```

One ballot per manager per week, enforced in the schema rather than the service —
`league-suite.md`'s own rule, and the reason the upsert is a delete-and-reinsert
of entries under an `on conflict do update` on the ballot, exactly as
`PowerRankingRepository.save` already does for snapshots.

**`unique (ballot_id, rank)` is doing real work.** It makes a ballot a strict
total order at the database level: no ties, no gaps a partially-saved ballot
could leave behind. The drag board can only produce one chip per slot, so this is
the schema saying the same thing the UI promises — which is the point of putting
it here instead of trusting the client.

### Aggregates are **not** stored

Member rankings are computed on read from ballots, never written to
`power_ranking`. So `power_ranking_kind_check` is untouched and there is no
`MEMBER` row anywhere in that table.

This is a deliberate split from the computed modes, and the reason is that the
two have opposite failure modes. A computed ranking is a snapshot because its
*inputs move underneath it* — recomputing week 4 next month gives a different
answer than week 4 gave, and that is a trap worth designing out. A ballot
aggregate's inputs are frozen the moment the week ends: the ballots themselves
are the snapshot. Storing a second copy of the average would be a cache that can
disagree with its source.

### The commissioner flag

```sql
alter table league add column commissioner_sleeper_user_ids text[] not null default '{}';
```

Sleeper ids, not `manager.id`, because the thing being compared is the
`X-Sleeper-User` header — no join, and no missing-`manager`-row case. A plural
column because `is_owner` is true for every co-commissioner, and leagues really
do have two.

**This column *wants* to be overwritten by ingest, which is the opposite of the
`adp_at_time` trap.** That footgun (`draft.adp_at_time`, and later
`reversal_round` needing its own `_override` column) was ingest clobbering
*user-entered* data with Sleeper's. Here Sleeper is the source of truth, and a
re-ingest picking up a commissioner change is correct behaviour. The rule that
distinguishes them: **derived-from-Sleeper columns are rewritten on every ingest;
user-entered ones need a separate column ingest never touches.** `ranking_ballot`
is squarely the second kind, and nothing in the ingest path may ever write to it.

---

## The drag board

`web/src/components/RankBoard.tsx`, used twice — once for the commissioner
ranking, once for a member ballot. Same component, different `onSubmit` and
heading. That is what "commissioner is a bit simpler" comes out as in code: the
commissioner's ordering is stored as *the* ranking; a member's is one ballot
among twelve.

**Layout: tray above, slots below.** All members start as unplaced chips in the
tray. Drag a chip into a numbered slot; drag between slots to reorder; drag back
to the tray to unplace. The ballot cannot be submitted until every slot is
filled, and the tray is what makes "who haven't I ranked yet" visible without a
counter — a pre-seeded list cannot distinguish "I put him 7th" from "I never
touched him", which is the whole reason not to seed one.

**Chips are the app's color-initials avatar**, not Sleeper's image — the same
`.avatar` treatment `SeatPopover`, `ManagerHistory` and `App.tsx` already use,
with `hueFor(managerId)` so a manager is the same color here as on the draft
board and on the bump chart above. Your own chip carries `--crimson`, per the
house rule that crimson means you.

### Implementation: pointer events, hand-rolled

No DnD library. `web/package.json` has three runtime dependencies, and this repo
already chose a hand-rolled SVG over a charting library for the same reason — a
library's default look has to be fought back to house style, and the surface here
is genuinely small.

**Pointer Events, not HTML5 drag-and-drop.** HTML5 DnD does not fire on touch
without a polyfill, and a fantasy league ranks its teams on a phone. Concretely:

- `pointerdown` on a chip → `setPointerCapture`, record the origin. A movement
  threshold (~6px) before it counts as a drag, so a tap is still a click.
- `touch-action: none` on chips, or the page scrolls out from under the drag.
- Slot rects measured **once at drag start** into an array, not per `pointermove`
  — a `getBoundingClientRect()` per slot per move, at 12 slots and 60fps, is the
  easy way to make this feel bad on a phone.
- A floating ghost chip (`position: fixed`, `transform: translate3d`) follows the
  pointer; the origin slot shows an empty outline. The hovered slot gets a tinted
  fill, not a shadow — grid tiles are on the plane (house style).

**A click/keyboard path is mandatory, not a fallback.** Every chip is a real
`<button>`: click to select it, click a slot to place it. A focused chip takes
`ArrowUp`/`ArrowDown` to move a slot and `Escape` to deselect. This is the
accessibility requirement, and it doubles as the escape hatch for anyone whose
drag is fighting a small screen. `aria-live` announces "Placed 4th of 12".

---

## Aggregation

Computed in a new `MemberRankingService`, on read.

**Aggregate rank is average ballot rank, ascending.** With complete orderings,
Borda and average rank produce the same ordering under a linear transform, so
average rank wins on being the number people can read: 3.42 means "the room puts
you about third and a half".

**Ties resolve by the one existing rule.** `PowerRankingService.rankDescending`
already implements standard competition ranking (1, 2, 2, 4) at
[PowerRankingService.java:207](../backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java).
Extract it into a shared ranker taking a comparator, so member aggregates
(ascending, lower is better) and computed scores (descending) resolve ties
identically. Two implementations of one rule is this repo's named failure mode.

**Spread is a first-class output, not a stat somebody could derive.** Per roster
per week:

| field | meaning |
| --- | --- |
| `rank` | competition rank on `avgRank`, ascending |
| `score` | `avgRank`, e.g. `3.42` — mode 2 finally has a number for the tooltip |
| `bestRank` / `worstRank` | the range any single ballot put them in |
| `stdev` | σ of the ranks — the divisiveness measure |
| `ballotCount` | how many ballots this week actually had |
| `note` | `avg 3.42 · 1–8 · σ 2.1 · 7 of 12 ballots` |

**The most divisive team** is surfaced by name (max σ), per `league-suite.md`'s
"show the disagreement, not just the mean".

### Self-ranking: kept, and shown

A manager ranks all twelve teams including their own, and their own rank counts
in the average. The bias is then surfaced as its own output:

```
selfRankBias = myRankOfMyTeam − roomAvgRankOfMyTeam
```

Negative means you rank yourself higher than the room does. Rendered as a
"Homers" table, most self-flattering first. This is the explicit stated rule AC5
asks for; the alternative (silently excluding self-ranks) buys a marginally more
defensible average at the cost of the most entertaining thing on the page.

Caveat that must appear on screen: with twelve ballots, one manager's own vote
moves their own average by a fraction of a rank, so the bias number is
commentary, not a correction being applied.

### Coverage: no carry-forward

A week has only the ballots submitted *that* week. Nothing carries forward.

- Zero ballots → the week does not exist for mode 2, and `segmentsOf()` already
  breaks the line across it. No interpolation (AC7).
- Below half the members → the segment renders **dotted** (`stroke-dasharray`),
  and the per-point tooltip says `4 of 12 ballots`. This is new chart code:
  `SeriesPoint` gains `ballotCount`, and `segmentsOf` splits on a coverage change
  as well as on a week gap, so a thin stretch is visibly a different stroke from
  a full one.
- `ballotCount` rides on `PowerRankingEntry` as an optional field rather than
  being parsed back out of `note` — the note is prose for humans.

**Ballots can only be submitted for the current week.** Backfilling last week's
opinion after seeing how the games went is precisely the dishonesty this project
designs out everywhere else. Re-submitting *this* week's ballot overwrites your
own, which is a different thing and is fine.

---

## API

```
GET  /api/leagues/{sleeperId}/ballot?season=&week=
     -> { season, week, canSubmit, canCommission, memberCount, ballotCount,
          members: [{ rosterId, managerId, manager, teamName, isMe }],
          mine: { rosterIds: [...], submittedAt } | null }

POST /api/leagues/{sleeperId}/ballot
     body { season, week, rosterIds: [...] }     -- rosterIds[0] is 1st

GET  /api/leagues/{sleeperId}/power              -- unchanged route; entries[] now
                                                    also carries kind "MEMBER"
```

`members` comes from `sleeper.rosters()` joined to `leagueUsers()`, so it works
before any history ingest — the limitation named at the top.

**`POST /ballot` rejects anonymous callers, and this is a deliberate exception.**
`LeagueMembership.anonymous()` returns `true` — a request with no
`X-Sleeper-User` is let through everywhere, on purpose, because that is the
pre-identity contract and the curl-driven draft-night escape hatch. A ballot
cannot work that way: it is a write *attributed to a person*, and there is no
honest `manager_id` to attach to an anonymous one. So this endpoint checks the
header itself and 401s without it, rather than teaching `LeagueMembership` a
second mode. The check lives in the controller with a comment saying why, because
a reader who knows the anonymous rule will otherwise read it as a bug.

Two more rules on that endpoint:

- **`manager_id` is derived from the header, never read from the body.** There is
  no field in `POST /ballot` that names whose ballot it is.
- **Non-members are refused**, via `LeagueMembership.leagueIdsFor` — you cannot
  vote in a league you do not play in.

`canCommission` is `X-Sleeper-User ∈ league.commissioner_sleeper_user_ids`, plus
`APP_OWNER_SLEEPER_USER_ID`. `POST /power/commissioner` gains the same check.
**This is scoping, not security** — `X-Sleeper-User` is an unverified claim and
Sleeper ids are public, exactly as `LeagueMembership`'s own header says. What it
buys is that the app stops letting any visitor overwrite the commissioner's
opinion by accident. It does not stop anyone determined.

---

## Page shape

The mode segmented control goes from three segments to four: Market value,
Realized, Commissioner, **The room**. Everything above it — chart, view toggle,
highlight, one-team transpose — is unchanged and gets mode 2 for free.

Below the chart, when mode is `MEMBER`:

1. **Your ballot** — the `RankBoard`, or, if you have already submitted, your
   ordering rendered read-only with an "Edit ballot" button. Absent entirely if
   you are signed out, replaced by a line pointing at the sign-in gate.
2. **The room** — the aggregate table: rank, team, avg, a spread bar (min–max
   range with a dot at the mean), σ, ballot count. The spread bar is what makes
   "half the league has him 1st and half has him 8th" legible at a glance, which
   a column of numbers does not.
3. **Homers** — self-rank bias, most self-flattering first.

When mode is `COMMISSIONER` and `canCommission`, the same `RankBoard` replaces
today's number-input table.

---

## Not building

- **Generic polls.** `league-suite.md` positions ballots as the first poll;
  everything after it stays unbuilt until this one is used by real people.
- **Magic-link auth / `manager_session`.** `X-Sleeper-User` is what exists, and
  its limits are stated on the page rather than papered over.
- **Ballot reminders or notifications.** No mail path in this app.
- **A close time per week.** "Current week only" covers the same ground with no
  new state to get wrong.
- **Sleeper avatar images.** Color-initials, per the existing house choice
  ([App.tsx:90](../web/src/App.tsx)).
- **Carry-forward ballots**, and **backfilling past weeks** — both rejected above.
- **A "consensus vs computed" delta view.** It is the most interesting thing this
  page could show next, and it wants its own pass once mode 2 has real data in
  it, rather than being designed against an empty table.

---

## Acceptance criteria

1. A signed-in league member can rank every team by dragging, on desktop **and**
   on a phone — verified by driving the real page at a mobile viewport, not by a
   test asserting a handler fired.
2. The same board is fully usable with **no dragging at all** — click-to-select,
   click-to-place, arrow keys — and announces placements to a screen reader.
3. A ballot cannot be submitted with an empty slot, and the schema refuses a
   duplicate rank even if a client tries.
4. Submitting twice in one week replaces your ballot; it never creates a second.
5. `POST /ballot` with no `X-Sleeper-User` is refused; with a non-member's id it
   is refused; and a body naming someone else's manager is impossible by
   construction.
6. The aggregate shows spread and ballot count alongside the average, and the
   most divisive team is named — not left for the reader to derive.
7. A week with fewer than half the members' ballots draws **dotted**, a week with
   none draws **broken**, and neither is interpolated across.
8. Self-ranks are included in the average and the resulting bias is displayed;
   the page states this rule in words.
9. Ties in a member aggregate resolve identically to ties in a computed ranking,
   because both call the same ranker.
10. Only a Sleeper commissioner (or the app owner) sees or can save the
    commissioner ranking, and the page says plainly that this is scoping rather
    than security.
11. The drag board works on a league with **no ingested history** — the case
    today's number-input editor silently cannot render.
12. Nothing in the ingest path writes to `ranking_ballot`; re-running league
    ingest after voting leaves every ballot byte-identical.

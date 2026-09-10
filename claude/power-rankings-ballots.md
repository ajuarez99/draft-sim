# Power rankings: member ballots and a drag-to-rank board

Requested 2026-09-09. Two things, one surface:

1. **Give both hand-made modes a real input.** Today the commissioner ranking is a
   table of `<input type="number">` boxes
   ([PowerRankings.tsx:415-455](../web/src/pages/PowerRankings.tsx), the inputs
   themselves at 434-441). Replace it with a drag board: the league's members
   appear as chips in a tray, you drag them into numbered slots.
2. **Build mode 2 — league-member ballots** — the thing `claude/league-suite.md`
   deferred to "Phase B" behind auth that has since arrived. Every member submits
   their own ordering with the same drag board; the page shows the room's average.

The second is the feature. The first is what makes both of them worth opening.

> **Amended after review, 2026-09-09** — `claude/plan-review-power-rankings-ballots.md`
> read this cold and returned 11 blocking findings and 11 non-blocking. Every
> amendment is marked in place below rather than silently folded in. The five
> that changed the design rather than correcting a detail:
>
> - **`leagueUsers()` already has two callers**, not zero, and both are
>   `upsertManagers` loops iterating the exact payload the commissioner flag
>   rides on. The original doc framed capturing `is_owner` as new work. It is not
>   — and writing a third users-walk would be this repo's named failure mode.
> - **Membership could not identify a member of a new league at all** — the exact
>   case AC11 promised. Resolved by a `league_member` table (below), which also
>   replaced the `text[]` commissioner column entirely.
> - **`season` on `ranking_ballot` was redundant and breakable.** Dropped.
> - **The shared-ranker extraction was a sign-inversion trap**, and the AC as
>   written would have passed while every roster came back rank 1.
> - **"The chart gets mode 2 for free" was false** in four specific ways.

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
auth-split table says mode 2 needs "magic-link auth"; it does not any more.

**~~Two things already available and unused.~~ Amended after review (finding 1):
they are available and already *used*, which changes where the code goes.**
`SleeperClient.leagueUsers()` has **two** callers, not none —
`LeagueIngestService.java:141` and `LeagueHistoryIngestService.java:70`, both
`upsertManagers` loops already walking the exact payload that carries `is_owner`
and `metadata.team_name`. So the commissioner flag and the member list are not "a
read away": **the read already happens twice**, and the real risk is writing a
third copy of the users walk — `LeagueMembership`'s own class header and
`claude/multi-sport-landmines` both name that as this project's recurring failure.
Everything new here hangs off those two existing loops.

**`is_owner` is not a boolean.** Measured live against (Foot) Ball Knowers 2025
(`1254190892974084096`), 12 users: `popsharky` has `is_owner = true`; the other
eleven have **no such key**, not `false`. `(boolean) u.get("is_owner")` NPEs.
`Boolean.TRUE.equals(u.get("is_owner"))` is the only safe read. Same shape as
`claude/lessons.md` #12.

**A limitation worth naming before designing around it.** The current
commissioner editor renders only `if (standings && standings.length > 0)`, and
`standings` comes from `roster_season`, which is populated by league-history
ingest. A league whose history has never been ingested has *no way to rank
anything*. The new board must not inherit that — see `league_member` below, which
is how AC11 is actually paid for rather than merely asserted.

---

## Storage

### `league_member` (new, V9) — the membership fix

**Added after review (finding 3).** The original doc put a
`commissioner_sleeper_user_ids text[]` column on `league` and assumed membership
was already solved. It was not. `LeagueMembership.leagueIdsFor` resolves a
manager to a league only through `roster_season` (needs history ingest) or
`draft.slot_to_manager` (empty until the commissioner sets the draft order —
`lessons.md` #15). On a league created yesterday, both are empty: the board would
render, you would drag twelve chips, and `POST /ballot` would 403.

```sql
create table league_member (
    id               bigserial primary key,
    league_id        bigint  not null references league (id) on delete cascade,
    manager_id       bigint  not null references manager (id) on delete cascade,
    is_commissioner  boolean not null default false,
    team_name        text,
    unique (league_id, manager_id)
);
create index league_member_manager_idx on league_member (manager_id);
```

**Populated inside the two `upsertManagers` loops that already exist**, in the
same pass that already upserts the `manager` rows — no third walk, no extra
Sleeper call, no new ingest entry point. This is the whole reason finding 1
matters: the correction turned six scattered edits into two, in loops that are
already holding the right object.

**This replaces the `text[]` column on `league` entirely**, which deletes finding
2's problem rather than solving it: no `on conflict do update set` enumeration to
extend ([LeagueRepository.java:39-47](../backend/src/main/java/com/ballknowers/draftsim/store/LeagueRepository.java)),
and no second `createArrayOf` — the trap `claude/lessons.md` #4 names by hand.

**And it becomes a third arm of the membership walk.** `LeagueMembership`'s
`MINE_AND_CHAIN` gains:

```sql
union
select lm.league_id from league_member lm join me on me.id = lm.manager_id
```

One definition of "which leagues are mine", extended — not a second one written
beside it. `roster_season` catches a league with no ingested draft,
`slot_to_manager` catches a draft ingested before any scored season, and
`league_member` catches a league with neither, which is every league on the day
it is created.

**Ingest rewrites this table, and that is correct.** Sleeper is the source of
truth for who is in a league; a re-ingest picking up a new member or a handed-over
commissionership is the behaviour you want. The rule that separates this from the
`adp_at_time` footgun (and from `reversal_round` needing its own `_override`
column): **derived-from-Sleeper rows are rewritten on every ingest; user-entered
ones need storage ingest never touches.** `ranking_ballot` is the second kind, and
nothing in the ingest path may ever write to it.

**Co-commissioners: unverified.** `is_commissioner` is per-row so several members
can carry it. Whether Sleeper actually sets `is_owner` for a co-commissioner is
**a guess** — the one league with data here has exactly one. The plural shape
costs nothing and is kept, but it is not a measured fact.

### Ballots (V9)

```sql
create table ranking_ballot (
    id            bigserial primary key,
    league_id     bigint not null references league (id) on delete cascade,
    week          int    not null,
    manager_id    bigint not null references manager (id) on delete cascade,
    submitted_at  timestamptz not null default now(),
    unique (league_id, week, manager_id)
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

**Amended after review (finding 4): `season` is gone.** `league` is per-season in
this schema ([V1__init.sql:47](../backend/src/main/resources/db/migration/V1__init.sql)),
so `league_id` already implies the season — and because `season` would have been
client-supplied, two ballots per manager per week could slip past the unique key
under different season values, breaking AC4 directly. The season is read from
`league.season` server-side and any value in the request body is ignored.

**Amended after review (finding 5): the constraint justification was wrong, and
the pattern being copied is not transactional.** The original doc claimed
`unique (ballot_id, rank)` leaves "no gaps a partially-saved ballot could leave
behind." Measured against the local PG 17.11:

| probe | result |
| --- | --- |
| delete all entries, reinsert the same ranks in a new order, one transaction | **accepted** — the intended upsert order is safe |
| reinsert a rank without deleting first | rejected, `duplicate key (ballot_id, rank)=(1,1)` |
| delete then insert ranks **1, 2, 7** | **accepted** |

So it stops duplicate ranks. It does nothing about gaps. And
`PowerRankingRepository.save` — the pattern being copied — carries **no
`@Transactional` anywhere**, so a partially-written ballot is a real state, not a
hypothetical. **The ballot upsert is `@Transactional`**, and contiguity is
enforced by the server-side coverage check below, not by the schema.

**Amended after review (finding 14): the server validates coverage.**
`saveCommissionerRanking` accepts any `List<Integer>` and writes ranks over it;
the controller checks only non-empty. There is no FK from
`ranking_ballot_entry.roster_id` to anything (deliberately — roster ids are
per-league ints, not global keys). So a missing roster or a roster from another
league is caught by nothing. `POST /ballot` checks that `rosterIds` is **exactly**
the league's roster-id set, as a set, before writing. AC3's "cannot be submitted
with an empty slot" was client-side-only as originally written.

### Aggregates are **not** stored

Member rankings are computed on read from ballots, never written to
`power_ranking`. So `power_ranking_kind_check` is untouched and there is no
`MEMBER` row in that table.

This is a deliberate split from the computed modes, and the reason is that the
two have opposite failure modes. A computed ranking is a snapshot because its
*inputs move underneath it* — recomputing week 4 next month gives a different
answer than week 4 gave. A ballot aggregate's inputs are frozen the moment the
week ends: the ballots themselves are the snapshot. A stored average would be a
cache that can disagree with its source.

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
touched him", which is the reason not to seed one.

**Chips carry the app's color-initials avatar**, not Sleeper's image — the same
`.avatar` treatment `SeatPopover`, `ManagerHistory` and `App.tsx:99-107` use.

> **Amended after review (finding 17): the consistency claim was overstated.**
> The original doc said `hueFor(managerId)` makes a manager "the same color here
> as on the draft board and on the bump chart." True for the chart, `DraftBoard`,
> `SeatPopover`, `LeagueHistory`, `ManagerHistory` and `ManagerTendencies` — all
> seed off the manager id. **Not** true of the header avatar in `App.tsx:101`,
> which seeds `hueFor(user.username)`. So the header avatar is *already* a
> different colour from the same person's line on the chart. That is a
> pre-existing inconsistency this feature does not fix and must not repeat: chips
> seed off `String(managerId)`, matching the chart. A member with no `manager`
> row cannot occur any more (`league_member`'s FK guarantees one), which removes
> the fallback-seed divergence the review flagged.

Your own chip carries `--crimson`, per the house rule that crimson means you.

**Team names (finding 19).** Measured on the same league: 3 of 12 users have no
`metadata.team_name`, and one has the literal string **`"TBD"`**. Fallback chain
is `team_name` → `display_name` → `roster N`, with `"TBD"` treated as absent.

### Implementation: pointer events, hand-rolled

No DnD library. `web/package.json` has three runtime dependencies, and this repo
already chose a hand-rolled SVG over a charting library for the same reason.
**Verified by the review: there is zero existing pointer/drag vocabulary in
`web/src`** — no `onPointer*`, `setPointerCapture`, `draggable`, `dataTransfer`,
`touch-action` or `user-select` anywhere. This is all new ground.

**Pointer Events, not HTML5 drag-and-drop.** HTML5 DnD does not fire on touch
without a polyfill, and a fantasy league ranks its teams on a phone.

- `pointerdown` on a chip → `setPointerCapture`, record the origin. A ~6px
  movement threshold before it counts as a drag, so a tap is still a click.
- `touch-action: none` on chips, or the page scrolls out from under the drag.
- A floating ghost chip (`position: fixed`, `transform: translate3d`) follows the
  pointer; the origin slot shows an empty outline. The hovered slot gets a tinted
  fill, not a shadow — grid tiles are on the plane (house style).

> **Amended after review (finding 10) — this section was the thinnest part of the
> original doc, and one item in it is a blocker before AC1 can even be attempted.**
>
> **This page has no vertical scroller.** `.app` is `height: 100vh`
> (styles.css:160-165); `.content` is `flex: 1 1 auto; min-height: 0` and has
> **no `overflow`** (763); `.panel-body` is the scroller (750) and
> `PowerRankings` does not use it — it puts `<section className="panel">` straight
> into `.content`. This design adds *three* sections below the chart. On a phone
> that is several viewport-heights inside a container that cannot scroll: the
> bottom of the page is simply unreachable. **Deciding which element scrolls, and
> putting `min-height: 0` at every level of the flex chain, is step one of this
> build, not a polish item.**
>
> **Caching slot rects once at drag start is unsafe here, for a reason beyond
> perf.** `getBoundingClientRect()` is viewport-relative, so whatever scroller
> ends up wrapping the board invalidates every cached rect the moment it moves —
> and a 12-slot board on a phone *will* scroll mid-drag. `touch-action: none`
> stops the dragging pointer from scrolling; it stops nothing else. Cache, but
> re-measure on `scroll` and `resize`, **and after every placement** — the tray
> shrinks by one chip when a chip lands, and if it reflows to fewer rows every
> slot below it moves for the rest of the same drag.
>
> **Four pointer cases that each strand the ghost, none of them in the original:**
> `pointercancel` (OS interruption — a call, a system gesture — leaves a `fixed`
> ghost pinned to the screen with no dismissal); `lostpointercapture` (capture is
> released implicitly when the captured element unmounts, which it will, because
> React re-renders the chip list on every placement — key chips stably and put
> capture on a container); `user-select: none` (there is no such rule anywhere in
> `styles.css` — a slow desktop drag selects text across the whole board and
> paints it blue behind the ghost); and a non-primary button or `contextmenu`
> starting a drag.
>
> **The ghost's z-index goes in the house header, not inline.** `styles.css`'s
> STACKING ladder is scoped to `.board-stage` and this page is not one. The only
> other `position: fixed` layer is `.modal-backdrop` at `z-index: 100` (1112).
> `.panel` creates no stacking context, so a fixed ghost escapes it correctly —
> but the number is chosen against 100 and written into the header.
>
> **Touch targets.** `.avatar` is 24×24 (styles.css:147) — under half the 44px
> floor. The chip's own hit area is ≥44px; the avatar is its ornament.
>
> **Mobile layout for this page is in scope.** The entire stylesheet has one
> `@media (max-width: 700px)` block (1503-1515) with two rules, both scoped to
> `.avail-sheet`. AC1 is the first serious mobile work in this app, and the plan
> should stop pretending otherwise.
>
> **The ordering logic is extracted as a pure function (finding 18).** jsdom
> implements neither `setPointerCapture` nor layout — `getBoundingClientRect()`
> returns zeros — so no slot-hit test can run in the existing vitest harness.
> `place(order, chip, slot)`, `move(order, from, to)`, `unplace(order, chip)` live
> in `web/src/rankOrder.ts` as array operations with real unit tests; only the
> pointer plumbing is browser-only.

**A click/keyboard path is mandatory, not a fallback.** Every chip is a real
`<button>`: click to select, click a slot to place. A focused chip takes
`ArrowUp`/`ArrowDown` to move a slot and `Escape` to deselect. This is the
accessibility requirement and the escape hatch for a small screen. `aria-live`
announces "Placed 4th of 12" — **and is the first `aria-live` in this codebase**
(finding 22), so there is no existing pattern to copy.

**`.rank-input` (styles.css:1289) is deleted in the same commit** — it exists only
for the number-input editor this replaces.

---

## Aggregation

Computed in a new `MemberRankingService`, on read.

**Aggregate rank is average ballot rank, ascending.** With complete orderings,
Borda and average rank produce the same ordering under a linear transform, so
average rank wins on being readable: 3.42 means "the room puts you about third
and a half".

**Ties resolve by the one existing rule — but extracting it is a trap.**

> **Amended after review (finding 6).** The original doc said to extract
> `rankDescending` ([PowerRankingService.java:206](../backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java))
> into "a shared ranker taking a comparator". That is a sign-inversion waiting to
> happen: it detects ties with `rounded < prevRounded` — **direction-coupled, not
> equality-based**. Reused ascending for member averages, `rank` never advances
> and **every roster comes back rank 1**. Structurally perfect, pointed backwards
> — `claude/lessons.md` #1 verbatim. The shared ranker's tie test must be
> `!rounded.equals(prevRounded)`, with direction living only in the comparator.
> And AC9 as originally written ("both call the same ranker") asserts the
> mechanism, not the result — it would pass while every rank was 1. The AC now
> asserts **ordering direction** against known input.

**Spread is a first-class output.** Per roster per week:

| field | meaning |
| --- | --- |
| `rank` | competition rank on `avgRank`, ascending |
| `score` | `avgRank`, e.g. `3.42` |
| `bestRank` / `worstRank` | the range any single ballot put them in |
| `stdev` | population σ of the ranks — the divisiveness measure |
| `ballotCount` | ballots that ranked **this roster** |
| `note` | `avg 3.42 · 1–8 · σ 2.1 · 7 of 12 ballots` |

> **Amended after review (finding 7): "fits the existing `PowerRankingEntry`
> shape" was false.** It needs **four** new wire fields, not the one the original
> doc admitted to — a spread bar cannot be drawn from `note` prose, by the doc's
> own argument. `bestRank`, `worstRank`, `stdev` and `ballotCount` go into
> `api.ts`'s type and the Java response record **in the same commit**, which is
> `AGENTS.md`'s hardest rule and now squarely in play.

### Thin coverage has rules, because thin coverage is the launch state

> **Added after review (finding 8).** "Average ballot rank" is only fully
> specified when every ballot ranks every roster, and the design explicitly plans
> for the opposite. Three cases had no answer:

- **A roster ranked by very few ballots.** `avgRank` is per-roster, so a roster
  ranked 1st by the only manager who bothered gets `1.00` and **wins the week
  outright** over a roster averaging 1.4 across seven ballots. Per-roster
  `ballotCount` exists for exactly this; a roster ranked by fewer than half the
  week's ballots is rendered with the thin-coverage treatment and does not compete
  for rank 1.
- **σ at n=1 and n=2.** Population σ at n=1 is `0.0`, which renders as *perfect
  consensus from a single vote* — the most misleading output available. σ is
  **population** σ, **suppressed (`—`) below three ballots**, and a roster below
  three is excluded from "most divisive".
- **Ballots covering different roster sets** (a member joins mid-season). A roster
  absent from a ballot is **skipped, not imputed** — never treated as last.

**The most divisive team** is surfaced by name (max σ, among rosters with ≥3
ballots), per `league-suite.md`'s "show the disagreement, not just the mean".

### Self-ranking: kept, and shown

A manager ranks all twelve teams including their own, and their own rank counts
in the average. The bias is surfaced as its own output:

```
selfRankBias = myRankOfMyTeam − roomAvgRankOfMyTeam
```

Negative means you rank yourself higher than the room does. Rendered as a
"Homers" table, most self-flattering first. This is the explicit stated rule AC5
asks for; silently excluding self-ranks buys a marginally more defensible average
at the cost of the most entertaining thing on the page.

On-screen caveat: with twelve ballots, your own vote moves your own average by a
fraction of a rank, so the bias is commentary, not a correction being applied.

**Co-owners and orphans (finding 21).** Measured on this league: 12 rosters, zero
null `owner_id`, zero co-owners — so neither case is live. Sleeper models both.
Stated rather than discovered: **co-owners each get their own ballot, both ranking
the roster they share; an orphan roster is rankable but has no voter.**

### Coverage: no carry-forward

A week has only the ballots submitted *that* week. Nothing carries forward.
Zero ballots → the week does not exist for mode 2. Below half the members → the
segment renders dotted and the tooltip says `4 of 12 ballots`.

**Ballots can only be submitted for the current week.** Backfilling last week's
opinion after seeing how the games went is precisely the dishonesty this project
designs out. Re-submitting *this* week's ballot overwrites your own, which is
different and fine.

---

## The chart is not free

> **Amended after review (finding 9).** The original doc said everything above the
> new sections "is unchanged and gets mode 2 for free." Four things are not.

**(a) The x-axis is ordinal, not linear.** `xOf` is
`marginLeft + weeks.indexOf(week) * xStep` (PowerRankings.tsx:109) over the
sorted *distinct* weeks present for that mode. For the computed modes, which have
every week, ordinal and linear coincide and this never showed. For MEMBER —
ballots in weeks 2 and 5 — the two points sit one `xStep` apart, labelled "2" and
"5". AC7 passes, because nothing is interpolated; the chart is instead
*compressing four missing weeks into one column*, which is `lessons.md` #5 ("a
statistic can be correct and still be the wrong thing to display"). **`BumpChart`
gets a linear x-scale over `min(week)..max(week)`.**

**(b) Single-point segments draw nothing.** `segmentsOf` (77-89) splits on
non-consecutive weeks, and `<polyline points="120,60">` renders no stroke — only
the `<circle>` survives. For MEMBER, the mode most likely to have exactly one
ballot week at launch, the chart is a scatter of dots. Pre-existing, but this
feature makes it the common case, so single-point segments get an explicit marker.

**(c) Splitting `segmentsOf` on coverage opens a hole.** Two adjacent segments
that do not share an endpoint leave week 3 → week 4 undrawn — and an undrawn gap
means "no data" everywhere else on this chart. **The boundary point is duplicated
into both segments**, and `segmentsOf`'s return type carries the coverage flag
(it currently returns bare `SeriesPoint[][]`, and the renderer at 141 has no way
to vary the stroke).

**(d) There are three `as PowerRankingKind[]` casts, not one** — at
[270](../web/src/pages/PowerRankings.tsx) (the per-team transpose's mode list),
[307](../web/src/pages/PowerRankings.tsx) (the mode selector) and
[399](../web/src/pages/PowerRankings.tsx) (the per-team legend). The
`Record<PowerRankingKind, …>` maps — `KIND_LABEL`, `KIND_HUE`, `KIND_CAVEAT` —
*will* fail to compile until MEMBER is added. The three array literals are casts
and **will not**. So the default outcome is a mode selector that gains "The room"
while the per-team transpose silently keeps showing three lines under hard-coded
copy reading *"across all three modes"* (line 396). Adding a mode to a page whose
whole pitch is comparing modes, and having it not appear in the comparison view,
is the worst available half-build. **Replace all three with one exported
`ALL_KINDS = [...] as const`**, so the next mode is a one-line change and a missed
site is a type error.

**The fourth hue is 130.** `KIND_HUE`'s documented constraint (34-38) is clear of
`--crimson` ~25 and `--teal` ~175, and distinct from 60 / 210 / 290. 130 gives
spacings of 70 / 80 / 80 / 130 — the most even option available.

**Default mode (finding 20).** `refetch()` picks the default from
`nflState.started` (line 189). Measured today: `/state/nfl` returns
`season_start_date: 2026-09-09`, and today *is* 2026-09-09, so `started` flipped
`true` and the page now defaults to `COMPUTED_REALIZED` — which has zero snapshots
until someone clicks Compute. Pre-existing, not this feature's doing. Stated
regardless: **MEMBER is never the default**, and sits last in segment order.

---

## API

```
GET  /api/leagues/{sleeperId}/ballot?week=
     -> { season, week, canSubmit, canCommission, commissionerKnown,
          memberCount, ballotCount,
          members: [{ rosterId, managerId, manager, teamName, isMe }],
          mine: { rosterIds: [...], submittedAt } | null }

POST /api/leagues/{sleeperId}/ballot
     body { week, rosterIds: [...] }     -- rosterIds[0] is 1st; season ignored

GET  /api/leagues/{sleeperId}/power      -- unchanged route; entries[] now also
                                            carries kind "MEMBER" plus the four
                                            new spread fields
```

`members` comes from `league_member` joined to `sleeper.rosters()` for the
roster-id mapping, so it works before any history ingest.

**`POST /ballot` rejects anonymous callers.**

> **Amended after review (finding 11): the original doc argued this against the
> wrong precedent.** It reasoned against `LeagueMembership.canSee` and never
> mentioned the closer one — **`OwnerSlot.mayActAsSlot`
> ([OwnerSlot.java:87-97](../backend/src/main/java/com/ballknowers/draftsim/engine/OwnerSlot.java))
> is this app's only existing attributed write, and it allows anonymous**, with a
> javadoc explicit about why (the pre-identity contract, DEPLOY.md's curl escape
> hatches). A reader who knows that method reads the ballot 401 as an
> inconsistency unless this doc says why a ballot differs. It does, and the
> argument is available: **a pick has an unambiguous owner in `slot_to_manager`
> that the request itself names, so anonymous is *recoverable*; a ballot has no
> owner at all without the header, so anonymous is *unattributable*.**
> `mayActAsSlot` also settles the adjacent case this doc left open — a caller who
> sends a header but has no `manager` row is refused (line 95). Same here.

- **`manager_id` is derived from the header, never read from the body.**
- **`APP_OWNER_SLEEPER_USER_ID` is explicitly NOT a fallback for `POST /ballot`.**
  If it were, a header-less curl would silently submit *Allan's* ballot — strictly
  worse than a 401.
- **Non-members are refused**, via the extended `LeagueMembership` walk.

### The commissioner gate, and the regression it ships

`canCommission` is true when the header names a `league_member` row with
`is_commissioner`, or equals `APP_OWNER_SLEEPER_USER_ID`. Anonymous is false.

**This is a deliberate regression and it needs saying out loud.** Today
`POST /power/commissioner` accepts an anonymous call
([LeagueHistoryController:238](../backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java)
scopes via `visibleLeague`, and `canSee` returns `true` for a blank header). After
this it does not. **Decided: fail closed.** An empty `league_member` set for a
league means only the app owner can commission, and the page says *"no
commissioner detected for this league — re-run league ingest"* (`commissionerKnown:
false` on the wire). A gate that silently does nothing on every existing league is
the kind of control that looks like it works and doesn't; and the app owner is
never locked out of their own instance.

**The commissioner ranking gets the current-week gate too (finding 11.3).**
Today `commissioner()` validates only that `season`, `week` and `rosterIds` are
non-null — any week is accepted. The doc's own argument against backdating twelve
opinions applies verbatim to one signed opinion, so the same gate applies. This
tightens an existing endpoint; that is intentional.

**This is scoping, not security** — `X-Sleeper-User` is an unverified claim and
Sleeper ids are public, exactly as `LeagueMembership`'s own header says.

**Commissioner rankings on a no-history league (finding 16).**
`saveCommissionerRanking` resolves `managerByRoster` from
`rosterSeasons.forLeague` (line 182-183). On the very league AC11 targets that map
is empty, every entry gets `manager_id = null`, and the chart legend renders
`roster 3`. It reads from `league_member` instead — fixing the board while leaving
the readback broken would have been a half-fix.

---

## Page shape

Segments go three → four: Market value, Realized, Commissioner, **The room**.

Below the chart, when mode is `MEMBER`:

1. **Your ballot** — the `RankBoard`, or your submitted ordering read-only with an
   "Edit ballot" button. Absent when signed out, replaced by a line pointing at
   the sign-in gate.
2. **The room** — rank, team, avg, a spread bar (min–max with a dot at the mean),
   σ, ballot count. The spread bar is what makes "half the league has him 1st and
   half has him 8th" legible at a glance.
3. **Homers** — self-rank bias, most self-flattering first.

When mode is `COMMISSIONER` and `canCommission`, the same `RankBoard` replaces
today's number-input table.

---

## Not building

- **Generic polls**; **magic-link auth / `manager_session`**; **ballot reminders**;
  **a per-week close time** ("current week only" covers it with no new state).
- **Sleeper avatar images.** Color-initials, per the existing house choice.
- **Carry-forward ballots**, and **backfilling past weeks**.
- **A "consensus vs computed" delta view.** The most interesting thing this page
  could show next, and it wants its own pass once mode 2 has real data in it.
- **Multi-sport. Mode 2 is NFL-only, by construction** — added after review
  (finding 15). `PowerRankingService.nflState()` hardcodes `sleeper.state("nfl")`
  (line 64) and the response field is literally named `nflState`;
  `DraftPicker.tsx:359-365` already hides both league-suite links for
  `sport !== 'nfl'`. So containment exists today — but "current week only"
  promotes a cosmetic wrongness into a hard **write** gate, and a basketball
  league reached by typing the URL would stamp a ballot with an NFL week. Given
  that basketball is the actual target, this is stated, not left to inference:
  **mode 2 stays NFL-only until `/power` takes a sport.**

---

## Acceptance criteria

1. A signed-in league member can rank every team by dragging, on desktop **and**
   on a phone — verified by driving the real page at a mobile viewport, not by a
   test asserting a handler fired.
2. **The page scrolls.** Every section below the chart is reachable at 375px wide
   with a full ballot board rendered.
3. The board is fully usable with **no dragging at all** — click-to-select,
   click-to-place, arrow keys — and announces placements to a screen reader.
4. An interrupted drag (`pointercancel`, or the captured chip unmounting) leaves
   no ghost on screen.
5. `rankOrder.ts`'s place/move/unplace are unit-tested in vitest; the pointer
   plumbing is not, and the plan says so rather than pretending.
6. A ballot cannot be submitted with a missing roster, an extra roster, or a
   roster from another league — **checked server-side**, not only in the client.
7. Submitting twice in one week replaces your ballot; it never creates a second.
   Verified with the `season` field removed from the request.
8. `POST /ballot` with no `X-Sleeper-User` is refused, and is **not** rescued by
   `APP_OWNER_SLEEPER_USER_ID`; a non-member's id is refused; a body naming
   someone else's manager is impossible by construction.
9. **A one-ballot week renders honestly** — σ shows `—`, the single voter's
   favourite does not take rank 1 on one vote, and the coverage label says
   `1 of 12`. This is the state the feature launches in.
10. Ties in a member aggregate resolve **in the correct direction** — asserted
    against known input, not by asserting that both modes call the same function.
11. The **per-team transpose view shows four lines and says "four modes"**, and
    `ALL_KINDS` is the only place the mode list is written down.
12. A mode with ballots in weeks 2 and 5 draws them **four columns apart**, not
    adjacent; a coverage boundary leaves no undrawn gap.
13. Only a Sleeper commissioner (or the app owner) can save the commissioner
    ranking; a league with no detected commissioner says so on the page and names
    the fix. The page states plainly that this is scoping, not security.
14. The drag board **and the readback** work on a league with no ingested history
    — the chart legend shows manager names, not `roster 3`.
15. Nothing in the ingest path writes to `ranking_ballot`; re-running league
    ingest after voting leaves every ballot byte-identical, while `league_member`
    correctly picks up a changed commissioner.

---

## Verification pass, 2026-09-10

Built and driven for real. **Verified** below means executed; nothing in this
section is inferred.

**Suites.** Backend 355 tests, **0 skipped, 0 failures**, against real Postgres
(compose, `localhost:5433`). The zero-skipped matters: an earlier run of the same
suite reported 355/0 failures with **52 skipped** because no database was
reachable, and every one of those skips was an `*IT` -- including
`LeagueMembershipIT`, the class that covers the new membership arm. A green suite
with the DB down proves nothing about this feature. Frontend 180 tests, 20 files,
all passing.

**V9 applied** via Flyway on a live database. `league_member` populated from the
existing `upsertManagers` loops: both (Foot) Ball Knowers leagues carry 12 members
and exactly **1** commissioner -- matching the review's live measurement that
`is_owner` is present only on `popsharky` and absent (not `false`) on the other
eleven. Leagues ingested before V9 show 0 members, which is the
`commissionerKnown: false` path, not a bug.

**Gates, by curl.** Commissioner: `canCommission true`. Ordinary member:
`canSubmit true, canCommission false`. Anonymous `POST /ballot`: **401**.
Non-member: **403**. Backdated week, incomplete ballot, and duplicated roster:
**400** each. Resubmitting the same member's ballot twice left **2 ballots, 2
distinct managers** -- replace, never duplicate (AC7), checked in the table.

**Aggregation, on four genuinely disagreeing ballots.** Rosters 10 and 12 both
averaged 4.75, both received **rank 2**, and the next roster received **rank 4**.
That is standard competition ranking applied ascending -- the sign-inversion trap
in finding 6 would have returned rank 1 for every roster, and did not. σ populated
at n=4, suppressed at n≤2, and ranges spanned 1-12 on the divisive rosters.

### Two bugs live verification found that no test would have

**1. Pointer capture killed the entire click path.** `RankBoard` took
`setPointerCapture` on the container in `pointerdown`, before the drag threshold.
While a pointer is captured the synthesized `click` is dispatched to the *capture
target*, so every chip's `onClick` was retargeted to the container and never
fired: dragging worked perfectly and click-to-select did nothing at all. Capture
now starts in `pointermove`, at the moment the threshold is crossed -- the first
point at which it is a drag rather than a click. This is the exact shape of
`claude/lessons.md` #1: structurally correct, and pointed one event too early.
The click path is AC3, so half the accessibility story was dead while the suite
was green -- jsdom cannot fire a pointer sequence at all (finding 18), so no test
in this repo could have caught it.

**2. The fourth segment was unreachable on a phone.** `.segmented` is
`flex-shrink: 0` with `white-space: nowrap` segments, so adding "The room" did not
shrink, wrap or scroll the control -- it simply overflowed a 375px viewport, and
the new mode could not be tapped. Fixed by wrapping to two rows under 700px,
rather than `overflow-x: auto`: a horizontally scrolling four-item control hides
options behind a gesture with nothing to advertise it. Invisible at desktop width,
where all four fit.

**Mobile, measured at 375×812:** minimum chip hit area **44px** (the floor, met
exactly), minimum slot 52px, page does not scroll sideways, and `.content.scrolls`
reports 2674px of content in an 812px viewport and genuinely scrolls -- AC2, the
unreachable-bottom problem finding 10 raised, confirmed fixed.

### Not verified

- **Real touch input.** The drag was driven by mouse-derived pointer events. The
  touch path (`touch-action: none`, a real finger, mobile Safari) has not been
  exercised on a device.
- **A week with partial coverage on the chart.** Every ballot so far is week 1, so
  the dotted/broken segment logic is covered by unit tests
  (`PowerRankings.chart.test.ts`) and by the one-ballot lone-point rendering, but
  no multi-week ballot history exists yet to look at.
- **Co-owned and orphan rosters.** Still not present in any league this app has
  data for, so the stated rule remains stated, not observed.

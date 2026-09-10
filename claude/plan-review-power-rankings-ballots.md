# Plan review — power rankings: member ballots and a drag-to-rank board

Adversarial review of `claude/power-rankings-ballots.md`, done before any code
exists, per this repo's pipeline convention (`AGENTS.md`, "This repo's convention
for building anything feature-sized"). Reviewed 2026-09-09 against `9ece975`,
working tree clean.

**No code was written or changed as part of this review.** Where a claim below is
labelled *measured*, it was executed — against the local Postgres on 5433, the
live Sleeper API, or by reading the file at the line cited. Where it is a guess,
it says **guess** in that word, per this repo's standing rule.

## Verdict: GO, with amendments — but more of them than the doc's confidence implies

The product argument is right, the storage split (ballots stored, aggregates
computed on read) is right and well-argued, and the honesty instincts —
self-rank bias shown rather than silently excluded, coverage on the line, "this
is scoping not security" — are all in keeping with the rest of the repo. The
drag board is a good call and it is genuinely new ground here (verified: there is
no pointer/drag vocabulary anywhere in `web/src`).

What the doc is wrong about is almost entirely **the seam between this feature
and what already exists**. Three of its load-bearing "already exists" claims are
false, its two "nearly free" claims are both false, and the single most important
one — that a member can be identified well enough to attribute a ballot — is
blocked by the exact case its own AC11 says must work.

**11 findings must change the plan. 11 more are worth knowing.**

---

# Findings that must change the plan

## Finding 1 — `SleeperClient.leagueUsers()` has two callers, and `is_owner` is not a boolean

**Severity: factual error, changes where code goes.**

The doc's "Two things already available and unused" says
`SleeperClient.leagueUsers()` ([SleeperClient.java:39](../backend/src/main/java/com/ballknowers/draftsim/ingest/SleeperClient.java))
"has no caller."

It has two:

    LeagueHistoryIngestService.java:70    for (Map<String,Object> u : sleeper.leagueUsers(sleeperLeagueId))
    LeagueIngestService.java:141          for (Map<String,Object> u : sleeper.leagueUsers(sleeperLeagueId))

Both are `upsertManagers` loops already iterating the exact payload the
commissioner flag rides on. This is not a pedantic correction — it changes the
design. The doc frames capturing `is_owner` as new work ("both are a read away");
in fact the read already happens twice, and the risk is the opposite one: writing
a *third* copy of the users walk is this repo's own named failure mode
(`LeagueMembership`'s class header, `claude/multi-sport-landmines`' "two
implementations of one rule").

**And `is_owner` is not `true`/`false` per member.** Measured live against
(Foot) Ball Knowers 2025 (`1254190892974084096`), 12 users:

    popsharky          is_owner = True
    the other eleven   is_owner = None   (key absent)

So the field is present-and-true on the commissioner and **absent** on everyone
else. `(boolean) u.get("is_owner")` NPEs; `Boolean.TRUE.equals(...)` is the only
safe read. Same shape as `claude/lessons.md` #12 — a value that looks like it
constrains what is safe to do, and doesn't.

**The plural-column justification is a guess presented as a fact.** The doc says
"`is_owner` is true for every co-commissioner, and leagues really do have two."
Measured on the only league this repo has data for: exactly one user carries it.
Whether Sleeper sets it for co-commissioners is **unverified**. The plural column
is still the right call (it costs nothing and the claim may well be true), but
per `AGENTS.md`'s rule 8 the doc must say so as a guess, not assert it.

**Amendment.** Capture the flag inside the two existing `upsertManagers` loops,
not in new code; read it as `Boolean.TRUE.equals(u.get("is_owner"))`; and label
the co-commissioner rationale as unverified.

## Finding 2 — the `league` column does **not** get rewritten by ingest, and every existing league locks out its own commissioner

**Severity: the doc's central claim about this column is false.**

The doc: *"This column **wants** to be overwritten by ingest ... derived-from-Sleeper
columns are rewritten on every ingest."* Read as a design rule that is correct.
Read as a description of what will happen, it is wrong three times over.

`LeagueRepository.upsert`
([LeagueRepository.java:32-67](../backend/src/main/java/com/ballknowers/draftsim/store/LeagueRepository.java))
enumerates its columns explicitly:

```sql
on conflict (sleeper_id) do update set
    season = excluded.season,
    previous_league_id = excluded.previous_league_id,
    ... roster_positions = excluded.roster_positions
```

A new column added by `alter table` is touched by **nothing** here. Concretely,
all of the following must change in the same commit or the column stays empty
forever:

1. the `insert` column list, the `values` list, and the `do update set` list;
2. a **tenth positional bind** inside the `jdbc.execute` `PreparedStatement`
   lambda — and it is a second `text[]`, so it needs its own
   `ps.getConnection().createArrayOf("text", ...)`. Handing pgjdbc a bare
   `String[]` is `claude/lessons.md` #4 by name; the existing lambda avoids it
   deliberately and the new bind must too;
3. `ROW_COLUMNS` (LeagueRepository.java:73), `mapRow` (78), and the `LeagueRow`
   record (69) — plus every construction site of `LeagueRow`;
4. **`LeagueMapper.upsert` cannot supply the value at all.** Its signature is
   `upsert(LeagueRepository leagues, Sport sport, Map<String,Object> league)` and
   `league` is the `GET /league/{id}` object. `is_owner` is on
   `/league/{id}/users`, which `LeagueMapper` never sees. So the write is either
   a threaded extra parameter through both ingest services, or a separate
   `leagues.setCommissioners(leagueId, ids)` called from both — the second copy
   problem again.

**And the day-one consequence the doc does not name.** The column's default is
`'{}'`. Every `league` row already in the database gets that default. `canCommission`
is then false for everyone except `APP_OWNER_SLEEPER_USER_ID` until a full
re-ingest runs. AC10 ("only a Sleeper commissioner ... sees or can save the
commissioner ranking") therefore ships as a **lockout on every existing league**,
including the one Allan uses. That is `claude/lessons.md` #15's question —
"what does this do the first time it is wrong?" — and the answer here is "the
feature that already worked stops working."

**Amendment.** Spell out the six edits above in the plan. Add an AC: after V9 and
before any re-ingest, the commissioner editor is still reachable for the app
owner, and the page says *why* it is hidden for everyone else rather than just
vanishing. Consider backfilling the column in V9 itself for the single known
league rather than relying on a re-ingest that nobody will remember to run.

## Finding 3 — a member of a freshly-created league cannot be identified, which is exactly the league AC11 targets

**Severity: the feature does not work in its own stated case.**

The doc makes two commitments that cannot both hold:

- AC11: "The drag board works on a league with **no ingested history** — the case
  today's number-input editor silently cannot render."
- API: "**Non-members are refused**, via `LeagueMembership.leagueIdsFor`" and
  "`manager_id` is derived from the header."

`LeagueMembership.leagueIdsFor` resolves membership through `MINE_AND_CHAIN`
([LeagueMembership.java:54-69](../backend/src/main/java/com/ballknowers/draftsim/store/LeagueMembership.java)):

```sql
mine as (
    select rs.league_id from roster_season rs join me on me.id = rs.manager_id
    union
    select d.league_id from draft d, me
     where exists (select 1 from jsonb_each_text(d.slot_to_manager) x
                    where x.value = me.id::text)
)
```

and `me` is `select id from manager where sleeper_user_id = ?`. So membership
requires **either** a `roster_season` row (written only by league-history ingest)
**or** a non-empty `draft.slot_to_manager` (written only by draft ingest) — and
in both branches a `manager` row must already exist.

Three verified facts close the loop:

- `manager` rows are created only by `LeagueIngestService.upsertManagers:141` and
  `LeagueHistoryIngestService:70`. Nothing else writes them.
- **The sign-in gate writes nothing.** `SleeperUserController`'s own class comment:
  *"nothing here writes to the database."* Signing in gives you a
  `sleeperUserId` in `localStorage` and no `manager.id`.
- `leagueIdsFor` on an unknown user returns the **empty set**, by explicit design
  (the method's javadoc says so) — not "everything".

And the worst sub-case is exactly the doc's motivating one. A league created
yesterday has no history and its draft order is unset — Sleeper leaves
`draft_order` null until the commissioner sets it, which is the whole reason
`LiveDraftPoller.refreshSeatMap` exists (`claude/lessons.md` #15). So
`slot_to_manager` is empty even *after* draft ingest. Membership is
unestablishable, `manager_id` is unavailable for the FK, and every `POST /ballot`
is refused.

Net: the drag board renders (its member list comes from Sleeper, as designed) and
the submit button 403s. That is a worse outcome than today's blank editor,
because the user has done twelve drags first.

**Amendment.** Pick one and write it down:

1. **Make the ballot endpoint create the `manager` row it needs** — upsert on
   `(sleeperUserId, displayName)` from the `leagueUsers()` payload it is already
   fetching for `members`, and treat "appears in this league's `leagueUsers()`"
   as the membership test for ballots, rather than `leagueIdsFor`. This is a
   *different* membership rule from `LeagueMembership`'s, which the doc must
   justify in the same breath it invokes the no-second-implementation rule.
2. Or **require ingest**, drop AC11's "no ingested history" promise, and make the
   page say "run league ingest first" instead of rendering a board that cannot
   submit.

Option 1 is the honest one, but it is a real design decision the doc currently
skips entirely.

## Finding 4 — `season` on `ranking_ballot` is redundant and makes the one-ballot-per-week rule breakable

**Severity: schema trap, breaks the doc's own AC4.**

`league` is **per-season** in this schema:
`V1__init.sql` gives it `season int not null` and `sleeper_id text not null unique`.
One `league` row = one Sleeper league id = one season. So in
`unique (league_id, season, week, manager_id)`, `season` is functionally
determined by `league_id` and carries no information.

That would be harmless duplication except that **nothing constrains
`ranking_ballot.season` to equal `league.season`**, and the doc's API takes
`season` from the client (`body { season, week, rosterIds }`). Two POSTs with the
same league, week and manager but `season: 2026` and `season: 2025` both insert.
The unique constraint does not fire. AC4 — *"Submitting twice in one week
replaces your ballot; it never creates a second"* — is then false, and the
aggregate double-counts that manager.

`power_ranking` has the same redundancy (`unique (league_id, season, week, kind)`,
V5:54), so this is precedent — but precedent for a latent bug is not a reason to
copy it into a table whose whole point is one-per-person.

**Amendment.** Drop `season` from `ranking_ballot` and make the key
`unique (league_id, week, manager_id)`. If it is kept for symmetry with
`power_ranking`, then the service must derive it from `league.season` and ignore
the body's value entirely — and the doc should say so in the same sentence it
says `manager_id` is derived from the header, never read from the body. Same
rule, same reason.

**Corollary, in the doc's favour:** the prompt's worry about `roster_id` being
reused across seasons is a non-issue *for the same reason* — `league_id` already
pins the season, so a bare `int roster_id` with no FK is correct here, exactly as
`power_ranking_entry` (V5:60) already does it.

## Finding 5 — `unique (ballot_id, rank)` does not do the work the doc credits it with, and the pattern being copied is not transactional

**Severity: the stated guarantee is measurably false.**

The doc: *"**`unique (ballot_id, rank)` is doing real work.** It makes a ballot a
strict total order at the database level: no ties, **no gaps a partially-saved
ballot could leave behind**."*

Measured on the local cluster (`localhost:5433`, PostgreSQL 17.11), against a
temp table with the doc's exact two unique constraints:

| probe | result |
| --- | --- |
| delete all entries then reinsert the same ranks in a new order, one transaction | **accepted** — so the intended upsert order is safe |
| reinsert a rank without deleting first | rejected, `duplicate key ... (ballot_id, rank)=(1,1)` |
| delete then insert ranks **1, 2, 7** | **accepted** |

The constraint stops duplicate ranks. It does nothing about gaps. Half the
sentence justifying its existence is wrong, and it is the half about partial
saves.

The partial-save risk is also real rather than theoretical, because
`PowerRankingRepository.save` — the pattern the doc says to copy "exactly" — is
**not transactional**. Verified: no `@Transactional` on the method
(PowerRankingRepository.java:26), none on the class, none on
`PowerRankingService`. So `delete from power_ranking_entry` commits, and each of
the twelve inserts commits separately. A failure at insert 7 leaves a 6-entry
ballot on disk with no error visible to the user's next read. The repo knows how
to do this — `BoardRepository:43/78` and `MockDraftService:79/146/304` are
annotated, and `MockDraftRepository:100-106` has a comment explaining that the
service owns the boundary.

**Amendment.** Keep `unique (ballot_id, rank)` (it is still worth having), but
correct the justification to what it actually buys. Put the ballot upsert under
`@Transactional` — the measurement above shows same-transaction
delete-then-reinsert works, so there is no reason not to. And enforce
completeness in the service (see finding 11), because the schema cannot.

## Finding 6 — extracting `rankDescending` into a comparator-driven ranker is a sign-inversion trap, and AC9 will not catch it

**Severity: `claude/lessons.md` #1, reproduced exactly.**

The doc: *"Extract it into a shared ranker taking a comparator, so member
aggregates (ascending, lower is better) and computed scores (descending) resolve
ties identically."*

`rankDescending`
([PowerRankingService.java:206-223](../backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java);
the doc cites 207, which is the first line of the body, not the declaration)
does not detect ties by equality. It detects them by direction:

```java
double rounded = Math.round(s.score() * 10000.0) / 10000.0;
if (prevRounded == null || rounded < prevRounded) {
    rank = seen;
    prevRounded = rounded;
}
```

Parameterise the *sort* with a comparator and leave that `<` in place, and the
ascending case inverts: sorted ascending, `rounded` increases, `rounded <
prevRounded` is never true after the first element, `rank` stays at 1, and
**every roster in the member aggregate gets rank 1**. Structurally perfect: right
count, no duplicates, no crash. Pointed backwards. This is `claude/lessons.md`
#1's shape verbatim.

AC9 as written — *"Ties in a member aggregate resolve identically to ties in a
computed ranking, because both call the same ranker"* — asserts the *mechanism*,
not the *direction*. A test of that AC passes while every rank is 1.

The 4dp rounding also survives being made generic only by accident: it is
rounding to `power_ranking_entry.score numeric(12,4)`'s stored precision (V5:63),
which is meaningful for a stored snapshot and meaningless for an aggregate that
is never stored. Harmless for an average rank, but the comment justifying it
("rounded to the stored precision") becomes false the moment the ranker is shared
with something that has no stored precision.

**Amendment.** Rewrite the tie test as equality on the rounded key
(`!rounded.equals(prevRounded)`), not `<`, so it is direction-free. Add an AC in
the shape `claude/lessons.md` #1 says these need: *the roster with the best
average ballot rank is rank 1, and the worst is rank n* — an assertion about
preference **ordering**, not about mechanism. Do the same for the descending
path so the refactor is guarded from both sides.

## Finding 7 — "fits the existing `PowerRankingEntry` shape" is false; it needs four new fields, not one

**Severity: the doc contradicts itself, and `AGENTS.md`'s hardest rule is in play.**

The doc's own aggregation table specifies six per-roster outputs: `rank`, `score`,
`bestRank`, `worstRank`, `stdev`, `ballotCount`, `note`. The real type
([api.ts:632-642](../web/src/api.ts)) has `season, week, kind, rosterId, managerId,
manager, rank, score, note` and nothing else. Only `rank`, `score` and `note` land.

The doc handles this by adding **one** field: *"`ballotCount` rides on
`PowerRankingEntry` as an optional field rather than being parsed back out of
`note` — the note is prose for humans."* That argument is correct and it applies
identically to the other three. Page shape item 2 asks for *"a spread bar (min–max
range with a dot at the mean)"* — you cannot draw a spread bar from prose. So
`bestRank`, `worstRank` and `stdev` must be on the wire too, and the doc's claim
that the aggregate "fits the existing shape" is wrong by four fields.

This is the one place `AGENTS.md`'s hardest rule bites: *"`web/src/api.ts` types
are hand-maintained and must mirror the Java records field-for-field, in the same
change that touches the backend record."* `claude/lessons.md` #6 is that rule's
post-mortem. Four optional fields added to a Java response and forgotten in
`api.ts` produce no error at build or runtime — the spread bar just renders
empty, which is `lessons.md` #6's exact failure ("their input displayed as an
absence of input").

One thing that *is* fine and worth recording: `LeagueHistoryController.snapshotRow`
builds a `LinkedHashMap` (line 207), not `Map.of`, so nullable new fields will not
trip `claude/lessons.md` #12. Keep it that way.

**Amendment.** Name all four fields in the doc, mark them nullable-for-non-MEMBER
kinds, and add an AC: *the four new fields appear in `api.ts`'s
`PowerRankingEntry` in the same commit as the Java change, and the aggregate
table renders a spread bar from `bestRank`/`worstRank`, not from `note`.*

## Finding 8 — the aggregate has no rule for the case it says is normal

**Severity: silent bad data, in the doc's own honesty terms.**

"Aggregate rank is average ballot rank, ascending" is fully specified only when
every ballot ranks every roster. The doc explicitly plans for the opposite ("4 of
12 ballots"), and three cases have no stated answer:

- **A roster that appears in few ballots.** Average rank is per-roster, so a
  roster ranked 1st by the only manager who bothered to rank him gets `avgRank
  1.00` and **wins the week outright** over a roster averaging 1.4 across seven
  ballots. The doc's `ballotCount` is per-*week* ("how many ballots this week
  actually had"), which cannot express this. It needs to be per-*roster* as well,
  and the ranker needs a minimum-coverage rule or a shrinkage toward the mean.
  Ranking a team 1st on one vote and calling it the room's consensus is precisely
  the "looks more certain than its inputs" failure the doc is otherwise careful
  about.
- **σ at n=1 and n=2.** Population σ at n=1 is `0.0` — which renders as *perfect
  consensus* from a single vote, the most misleading possible output. Sample σ at
  n=1 is a divide-by-zero. The doc never says which σ, and "the most divisive
  team is surfaced by name (max σ)" will happily crown a roster whose σ came from
  two ballots.
- **Ballots covering different roster sets** (a member joins mid-season; a ballot
  from a week the league had 10 teams). Nothing says whether a roster absent from
  a ballot is skipped, or treated as last, or invalidates the ballot.

**Amendment.** State: σ is population σ; it is suppressed (rendered `—`, and
excluded from "most divisive") below three ballots; `avgRank` carries its own
per-roster ballot count and a roster ranked by fewer than half the week's ballots
is shown with the thin-coverage treatment rather than competing for rank 1; a
roster absent from a ballot is skipped, not imputed. Add an AC for the one-ballot
week specifically — it is the state the feature launches in.

## Finding 9 — the bump chart is not "nearly free"; four concrete things break

**Severity: changes the design and the estimate.**

The doc: *"Everything above it — chart, view toggle, highlight, one-team
transpose — is unchanged and gets mode 2 for free."* Verified against
[PowerRankings.tsx](../web/src/pages/PowerRankings.tsx), four things are not.

**(a) A sparse mode draws its gaps as adjacent.** `xOf` is
`marginLeft + weeks.indexOf(week) * xStep` (line 109) over `weeks`, the sorted
*distinct* weeks present for that mode (258-260). The x-axis is **ordinal, not
linear in week number**. For the computed modes, which have every week, ordinal
and linear coincide and this never showed. For MEMBER — ballots in weeks 2 and 5
— the two points sit one `xStep` apart, labelled "2" and "5", with the line
broken between them by `segmentsOf`. The chart is not interpolating, so AC7
passes; it is compressing four missing weeks into one column's width, which is
`claude/lessons.md` #5 ("a statistic can be correct and still be the wrong thing
to display"). The fix is a linear x-scale over `min(week)..max(week)`, which is a
real change to `BumpChart`, not a free ride.

**(b) Single-point segments render as nothing.** `segmentsOf` (77-89) splits on
`p.week !== last.week + 1`, so a mode with non-consecutive weeks yields
one-element segments, and `<polyline points="120,60">` (142-148) draws no stroke.
Only the `<circle>`s at 150-158 survive. For MEMBER — the mode most likely to have
exactly one ballot week at launch — the chart is a scatter of dots and the
legend's colour is the only thing tying them together. Existing behaviour, but
this feature makes it the common case.

**(c) Splitting `segmentsOf` on a coverage change opens a visual hole.** The doc
wants a full-coverage stretch solid and a thin one dotted, split at the boundary.
Two adjacent segments that do not share an endpoint leave week 3 → week 4
undrawn — a gap that means "no data" everywhere else on this chart. The boundary
point must be duplicated into both segments, and `segmentsOf`'s return type has
to carry the coverage flag (it currently returns bare `SeriesPoint[][]`, and the
renderer at 141 has no way to vary the stroke). Neither is mentioned.

**(d) There are three `as PowerRankingKind[]` literals, not one.** The doc
mentions the segmented control going three→four. The casts are at **270** (the
per-team transpose's mode list), **307** (the mode selector), and **399** (the
per-team legend). A `Record<PowerRankingKind, …>` — `KIND_LABEL` (28),
`KIND_HUE` (39), `KIND_CAVEAT` (45) — *will* fail to compile until MEMBER is
added, which is good. The three array literals are casts and will **not**. So the
default outcome is: the mode selector gains "The room", and the per-team
transpose view silently keeps showing three lines, under the hard-coded copy
*"across all three modes"* (line 396). Adding a mode to a page whose whole pitch
is comparing modes, and having it not appear in the comparison view, is the worst
possible half-build.

`KIND_HUE` also needs a fourth hue, and the constraint is real and documented in
place (34-38): clear of `--crimson` ~25, `--teal` ~175, and the three existing
60 / 210 / 290. The doc does not pick one.

**Amendment.** Rewrite "gets mode 2 for free" as the four-item work list above.
Replace the three `as PowerRankingKind[]` casts with one exported
`const ALL_KINDS = [...] as const` so the next mode is a one-line change and a
missed site is a type error. Add an AC: *the per-team transpose view shows four
lines and says "four modes".*

## Finding 10 — the drag board's real failure modes, and the page it lands on cannot scroll

**Severity: AC1 is much larger than the doc treats it.**

Two things the doc gets right, verified: there is **no** existing pointer or drag
vocabulary in this frontend (`onPointer*`, `setPointerCapture`, `draggable`,
`dataTransfer`, `touch-action`, `user-select`, `cursor: grab` — zero hits across
`web/src`), and Pointer Events over HTML5 DnD is the correct call for touch.

What it misses:

**The page has no vertical scroller.** `styles.css`'s own LAYOUT header says the
app is one screen. `.app` is `height: 100vh` (styles.css:160-165);
`.content` is `flex: 1 1 auto; min-height: 0; min-width: 0` and **has no
`overflow`** (763). `.panel-body` is the scroller (750) and `PowerRankings`
does not use it — it puts `<section className="panel">` straight into `.content`.
The doc stacks *three* new sections below the chart (your ballot, the room,
Homers). On a phone that is several viewport-heights of content in a container
that cannot scroll. This is not a drag problem, it is a "the bottom of the page
is unreachable" problem, and it has to be solved before AC1 can even be attempted.

**Measuring slot rects once at drag start is unsafe here, for a reason beyond
perf.** `getBoundingClientRect()` is viewport-relative. Whatever scroller ends up
wrapping the board (it needs one, per the above) invalidates every cached rect
the moment it moves — and a 12-slot board on a phone *will* scroll during a drag,
either from an autoscroll-at-edge affordance or from a second finger.
`touch-action: none` on chips (which the doc has) stops the dragging pointer from
scrolling; it stops nothing else. Cache rects, but re-measure on `scroll` and
`resize`, or cache them relative to the scroll container and add its current
`scrollTop`.

**Placing a chip changes layout mid-drag.** The tray shrinks by one chip when a
chip lands in a slot. If the tray reflows (wraps to fewer rows), every slot below
it moves and the cached rects are wrong for the *rest of the same drag*. Give the
tray a fixed min-height, or re-measure after every placement.

**Unmentioned pointer-event cases, each of which strands the ghost chip:**

- `pointercancel` — fires on OS-level interruption (a phone call, a system
  gesture, the browser deciding this is a scroll). Not handled means a `position:
  fixed` ghost pinned to the screen with no way to dismiss it.
- `lostpointercapture` — capture is released implicitly if the captured element
  unmounts. Which it will, because React re-renders the chip list on every
  placement. Key the chips stably and put the capture on a container, or handle
  the loss.
- **`user-select`**. There is no `user-select` rule anywhere in `styles.css`
  (verified). A slow drag across chip labels on desktop selects text across the
  whole board and paints it blue behind the ghost. One line, invisible until
  someone drags slowly.
- Mouse `contextmenu` / a non-primary button starting a drag.

**The ghost's z-index is not in the documented ladder.** `styles.css`'s STACKING
header lists 0–6 and says "Add a layer here, not inline" — but it is scoped to
`.board-stage`, and this page is not one. The only other `position: fixed` layer
in the app is `.modal-backdrop` at `z-index: 100` (1112). `.panel` creates no
stacking context (no transform, no z-index — verified), so a fixed ghost will
escape it correctly, but it needs a number chosen against 100 and written into
the house header, not inline.

**Touch-target size.** `.avatar` is `24px × 24px` (styles.css:147). The doc says
chips are "the app's color-initials avatar". A 24px drag handle is under half the
44px touch-target floor. The chip presumably wraps the avatar with a name, so
this may be fine — but the doc should say the chip's own hit area, not just its
ornament.

**And there is effectively no mobile layout to build on.** The entire stylesheet
has one `@media (max-width: 700px)` block (1503-1515) containing **two rules**,
both scoped to `.avail-sheet`. AC1's "verified by driving the real page at a
mobile viewport" is therefore the first serious mobile work in this app.

**Amendment.** Add the scroller decision to the plan (which element scrolls, and
where `min-height: 0` goes in the chain). Add `pointercancel`,
`lostpointercapture`, `user-select: none`, and rect re-measurement to the
implementation notes. Pick the ghost's z-index and add it to `styles.css`'s house
header. Say explicitly that mobile layout for this page is in scope.

## Finding 11 — the anonymous exception is defensible but under-specified, and it silently regresses the commissioner path

**Severity: changes the plan; one sub-case is a real regression.**

The doc argues the 401 well, but it reasons against `LeagueMembership.canSee`
and never mentions the closer precedent: **`OwnerSlot.mayActAsSlot`
([OwnerSlot.java:87-97](../backend/src/main/java/com/ballknowers/draftsim/engine/OwnerSlot.java))
is this app's only existing attributed write, and it allows anonymous.** Its
javadoc is explicit about why — *"Same pre-identity contract every other route
keeps ... what DEPLOY.md's curl escape hatches run on"* — and it also settles the
adjacent case the doc leaves open: a caller who *does* send a header but has no
`manager` row is refused (`callerManagerId == null` → `false`, line 95).

A reader who knows `mayActAsSlot` will read the ballot 401 as an inconsistency
unless the doc says why a ballot differs. It does differ, and the argument is
available: a pick has an unambiguous owner in `slot_to_manager` that the request
itself names, so anonymous is *recoverable*; a ballot has no owner at all without
the header, so anonymous is *unattributable*. Say that.

**Three things the doc must add:**

1. **`APP_OWNER_SLEEPER_USER_ID` is explicitly NOT a fallback for `POST /ballot`.**
   The doc invokes the app-owner fallback for `canCommission` and is silent for
   ballots. If the fallback applied, a header-less curl would silently submit
   *Allan's* ballot — strictly worse than a 401. State the exclusion.
2. **`canCommission` regresses an existing capability.** Today
   `POST /power/commissioner` accepts an anonymous call (verified:
   `LeagueHistoryController.commissioner:242-256` scopes via `visibleLeague`,
   and `canSee` returns `true` for a blank header). After this change an
   anonymous caller cannot commission at all. Combined with finding 2's empty
   default, the first deploy has *nobody* able to save a commissioner ranking.
   Either keep anonymous permitted on that endpoint (consistent with today), or
   ship the backfill from finding 2 and say the regression is intentional.
3. **The commissioner ranking is exempt from the current-week rule, and the doc
   does not say so.** The doc's own argument — *"Backfilling last week's opinion
   after seeing how the games went is precisely the dishonesty this project
   designs out everywhere else"* — applies verbatim to the commissioner. Verified:
   `commissioner()` validates only that `season`, `week` and `rosterIds` are
   non-null; any week is accepted. Apply the same gate, or state why one signed
   opinion may be backdated and twelve may not.

---

# Findings worth knowing, but not blocking

## 12 — V9 is free right now, and that is a fact with a short shelf life

Verified: the highest migration on disk is
`V8__mock_draft_owner.sql`, and no worktree adds one — all seven worktrees under
`.claude/worktrees/` are clean and behind `main` (checked
`git worktree list`, `git status --porcelain` in each, and a filesystem-wide
`find` for `V*__*.sql`, which turns up only `build/resources` copies).

But `flyway_schema_history` on the local cluster shows **V8 was applied
2026-09-09 12:19** — earlier today, by another session. The doc will be built
after that. The concrete failure if two sessions both write a `V9__`: Flyway
validates checksums on startup, the second file to arrive against an
already-migrated database fails validation, and `bootRun` **refuses to start** —
which reads as "my unrelated change broke the app". Worse if both files apply on
different machines: the two databases diverge permanently, since migrations are
append-only.

**Suggestion.** Re-run `ls backend/src/main/resources/db/migration/` immediately
before writing the file, not at plan time, and re-check `git log --oneline -5`
for a migration commit landed since. (`adp-multi-source`, still checked out in a
worktree, tops out at V2 — if it is ever merged it will need renumbering, which
is a separate existing hazard, not this feature's.)

## 13 — three line references are off

- `PowerRankings.tsx:412-433` for "the commissioner editor" starts three lines
  early (411 is the chart's "Click a line to highlight it" footer) and stops
  before the `<input type="number">` it is naming. The editor section is
  **415-455**; the number inputs are **434-441**.
- `PowerRankingService.java:207` for `rankDescending` points at the first line of
  the body; the declaration is **206**.
- `App.tsx:90` for the color-initials avatar lands inside the comment block; the
  `className="avatar"` span is **99-107**.

Trivial individually. Worth fixing because this repo's docs are read as
navigation.

## 14 — nothing validates that a ballot covers the league

Verified: `saveCommissionerRanking` (PowerRankingService.java:180-192) accepts any
`List<Integer>` and writes ranks 1..n over it; the controller checks only
non-empty. The proposed ballot inherits this. `unique (ballot_id, roster_id)`
catches a duplicated roster and `unique (ballot_id, rank)` catches a duplicated
rank, but **a missing roster or a roster that is not in this league is caught by
nothing** — there is no FK from `ranking_ballot_entry.roster_id` to anything, by
design (see finding 4's corollary).

AC3's second half ("the schema refuses a duplicate rank even if a client tries")
is true and measured. Its first half ("a ballot cannot be submitted with an empty
slot") is client-side only as written. Add a server check that `rosterIds` is
exactly the league's roster-id set, and an AC for it.

## 15 — this ships NFL-only, by construction, and the doc never says so

`PowerRankingService.nflState()` hardcodes `sleeper.state("nfl")`
(PowerRankingService.java:64) and the response field is literally named
`nflState`. `DraftPicker.tsx:359-365` already hides the History and Power
rankings links for `d.sport !== 'nfl'`, with a comment saying exactly why. So the
containment is real today.

But the doc's "current week only" rule promotes a cosmetic wrongness into a hard
write gate: on a basketball league reached by typing the URL, a ballot would be
stamped with an NFL week. Given `claude/multi-sport-landmines` and that
basketball is the actual target, the doc's "Not building" list should say
**"mode 2 is NFL-only until `/power` takes a sport"**, rather than leaving a
reader to infer it.

## 16 — a no-history league saves a commissioner ranking with no manager names

`saveCommissionerRanking` resolves `managerByRoster` from
`rosterSeasons.forLeague(leagueId)` (line 182-183), i.e. from league-history
ingest. On the no-history league the doc's AC11 targets, that map is empty, every
entry gets `manager_id = null`, and `PowerRankingRepository.forLeague`'s
`left join manager` returns null names — so the chart legend and the tooltip both
render `roster 3`. The drag board would be fixed and the readback still broken.

If AC11 is kept, the same rosters+users join that feeds `members` has to feed
`saveCommissionerRanking` too.

## 17 — the chip hue will not match the header avatar, and may not match the chart

The doc: *"`hueFor(managerId)` so a manager is the same color here as on the draft
board and on the bump chart above."* Two problems.

`App.tsx:101` — the avatar the doc cites as the house treatment — seeds
`hueFor(user.username)`, not `hueFor(String(managerId))`. Everything else
(`DraftBoard:82`, `SeatPopover:82`, `LeagueHistory:34`, `ManagerHistory:48`,
`ManagerTendencies:102`, `PowerRankings:67`) seeds off the manager id. So the
header avatar is *already* a different colour from the same person's line on the
chart, and the doc's consistency claim is only true for the second group.

And the ballot board's member list comes from Sleeper (`rosters()` + `leagueUsers()`),
which yields Sleeper user ids — `managerId` will be null for anyone without a
`manager` row (finding 3). `hueFor` on a fallback seed diverges from the chart.

Seed the chips off `String(managerId)` and state what a null one does.

## 18 — jsdom cannot test the drag, so the orderable logic should be extractable

`web/package.json` runs `vitest` over `jsdom` ^29. jsdom implements neither
`Element.setPointerCapture` nor a real layout engine — `getBoundingClientRect()`
returns all zeros. So every slot-hit test is untestable in the existing harness,
and AC1 is right to insist on a real browser.

The consequence the doc misses: extract the *ordering* (place chip at slot k,
move chip from slot i to j, unplace, arrow-key move) as a pure function over an
array, so the part that can be unit-tested is. Otherwise the entire board is
verifiable only by hand, forever.

## 19 — Sleeper's `metadata.team_name` is often absent, and sometimes worse than absent

Measured, same league: 3 of 12 users have no `metadata.team_name` at all, and one
has the literal string **`"TBD"`**. The doc's `members[]` carries `teamName` with
no stated fallback. `"TBD"` rendered as a team name on a ranking board is worse
than the display name it displaced.

Say the fallback chain (`team_name` → `display_name` → `roster N`), and treat
`"TBD"` as absent.

## 20 — with four segments, the default mode is unstated

`refetch()` currently picks the default from `nflState.started`
(PowerRankings.tsx:189). Measured today: `/state/nfl` returns
`{"week":1,"season":"2026","season_start_date":"2026-09-09"}` and today **is**
2026-09-09, so `started` is now `true` and the page defaults to
`COMPUTED_REALIZED` — which has zero snapshots until someone clicks Compute.
That is a pre-existing state change, not this feature's fault, but the doc adds a
fourth segment without saying whether MEMBER can ever be the default, or where it
sits in the order. State it.

## 21 — co-owned and orphan rosters have no stated behaviour

Measured on this league: 12 rosters, zero null `owner_id`, zero non-empty
`co_owners`. So the case is not live here. Sleeper models both, though, and for
ballots the question is sharper than for standings: two co-owners of one roster
means two ballots, both ranking the roster they share, and one roster in the
`members` list mapping to two people.

Worth one sentence — "co-owners each get a ballot; an orphan roster is rankable
but has no voter" — rather than being discovered.

## 22 — `.rank-input` becomes dead CSS, and three vocabularies are brand new

`.rank-input` (styles.css:1289) exists only for the number-input editor the drag
board replaces. Delete it in the same commit.

And for scoping honesty: `touch-action`, `user-select` and `aria-live` appear
**nowhere** in this codebase today (verified across `web/src`). The doc treats
the `aria-live` announcement as a detail; it is the first one in the app, and
there is no existing pattern to copy.

---

## What checked out, and did not need changing

Credit where it is due — these were checked rather than assumed:

- **`X-Sleeper-User` is set at `api.ts:193`**, in the single `apiFetch` funnel.
  Exact.
- **`SleeperClient.leagueUsers()` is at `SleeperClient.java:39`.** Exact.
- **`saveCommissionerRanking` is at `PowerRankingService.java:180`.** Exact.
- **`segmentsOf()` already breaks lines across missing weeks** (PowerRankings.tsx:77-89),
  and the three-way mode control and all-teams/one-team toggle are all there as
  described.
- **The commissioner editor really is gated on
  `standings && standings.length > 0`** (PowerRankings.tsx:415) and `standings`
  really does come from `roster_season` via `getLeagueHistory`. The limitation the
  doc names at the top is real, and naming it before designing around it was the
  right instinct — the doc just did not follow the same thread into
  `LeagueMembership` (finding 3).
- **`web/package.json` has exactly three runtime dependencies** (`react`,
  `react-dom`, `react-router-dom`), so "no DnD library" is consistent with the
  house position, and **there is genuinely no existing drag interaction to be
  consistent with**.
- **`LeagueMembership.canSee` does let anonymous through**, deliberately and with
  a documented reason. The doc reads that rule correctly even where it decides to
  break it.
- **Not storing aggregates is right**, and the argument for it — computed
  snapshots freeze inputs that move, ballots *are* the frozen input — is the
  sharpest thing in the doc. `power_ranking_kind_check` (V5:52-53) is genuinely
  untouched by this design.
- **Same-transaction delete-then-reinsert of identical ranks works** (measured,
  PG 17.11), so the upsert order the doc proposes is sound — given finding 5's
  transaction.
- **`snapshotRow` uses `LinkedHashMap`**, so the new nullable fields will not hit
  `claude/lessons.md` #12.
- **Self-ranks kept and the bias shown**, with the caveat that twelve ballots make
  it commentary rather than a correction, is exactly this project's honesty
  convention applied correctly.

## Amendments, as a list

1. Capture `is_owner` in the two existing `upsertManagers` loops; read it as
   `Boolean.TRUE.equals(...)` (it is absent, not false, for non-commissioners);
   label the co-commissioner rationale a guess.
2. Spell out all six edits the new `league` column needs, including the second
   `createArrayOf`, and backfill it in V9 so AC10 does not lock every existing
   league out on day one.
3. Decide how a member of an un-ingested league is identified — upsert the
   `manager` row from `leagueUsers()`, or drop AC11. As written the two contradict.
4. Drop `season` from `ranking_ballot`, or derive it server-side from
   `league.season` and ignore the body.
5. Put the ballot upsert under `@Transactional`, and correct the
   `unique (ballot_id, rank)` justification — it stops duplicates, not gaps
   (measured).
6. Make the shared ranker's tie test equality-based, not `<`, and add an AC that
   asserts **ordering direction**, not just mechanism.
7. Add `bestRank`, `worstRank` and `stdev` to the wire type alongside
   `ballotCount`, in `api.ts` and the Java record in the same commit.
8. State the aggregation rules for thin coverage: population σ, suppressed below
   three ballots; per-roster ballot count; absent rosters skipped, not imputed.
9. Replace the three `as PowerRankingKind[]` casts with one `ALL_KINDS`; use a
   linear x-scale; duplicate boundary points across coverage splits; pick the
   fourth hue; fix the "all three modes" copy.
10. Decide which element scrolls on this page; handle `pointercancel`,
    `lostpointercapture` and `user-select`; re-measure rects on scroll and after
    each placement; put the ghost's z-index in the house header. Mobile layout is
    in scope.
11. Engage `OwnerSlot.mayActAsSlot` explicitly; state that
    `APP_OWNER_SLEEPER_USER_ID` is not a fallback for `POST /ballot`; decide
    whether the commissioner endpoint keeps anonymous access and whether it gets
    the current-week gate.

None of these change the phase boundary, the storage split, or the decision to
hand-roll the board. Findings 3 and 9 change what "done" looks like; finding 2 is
the one that breaks something that works today.

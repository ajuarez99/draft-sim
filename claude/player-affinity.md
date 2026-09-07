# Player affinity, embeddings, and the public-draft corpus

Design note, 2026-08-29. **Promoted from `ideas/` to a real plan 2026-09-07**, when
its step-1 blocker was actually measured instead of assumed. **Still nothing built** —
this is the planning half of the convention in `AGENTS.md`, where "nothing built" is a
legitimate outcome.

## The question

Can manager tendencies live in a vector database — notes and players in one space —
so that "players near each other" produces a per-round prediction conditioned on ADP?

Short answer: player similarity is genuinely valuable and slots into the existing
engine cleanly. Putting notes and players in a *shared* embedding space is the part
to avoid. And the round/ADP conditioning already exists.

---

## Measured 2026-09-07 — the feasibility question this doc was blocked on

The 2026-08-29 version said, of enumerating public Sleeper drafts at volume:
"**unverified**, and worth a small feasibility script before anything else here."
That script now exists (`claude/scripts/sleeper-corpus-feasibility.py` and
`sleeper-corpus-growth.py`, read-only, public endpoints, no auth) and has been run.
Both numbers below are **measured, not estimated**, seeded from (Foot) Ball Knowers
2025 (`1254190892974084096`).

### It works, and it scales — the corpus is reachable

| what | measured |
| --- | --- |
| seed league members whose league lists are readable **without auth** | **12 / 12** |
| leagues per user across 2025+2024 | min 1, max 4, **mean 1.9** |
| level 1: distinct leagues from one league's members | **11** |
| level 2: those 11 leagues → users → their leagues | **81 users (69 new) → 82 leagues (72 new)** |
| growth per level | **~6.5× leagues** (11 → 83), ~1.04 new leagues per new user |
| cost of two full levels | **214 HTTP calls, 43 s**, zero rate-limit errors |
| picks come back attributed | **120/120, 170/170, 180/180 carry `picked_by`** |

Extrapolating the measured 6.5× at constant branching puts level 3 around 500
leagues and level 4 in the low thousands — **single-digit minutes of crawling for a
corpus in the thousands of drafts**, which is the regime the archetype idea below
needs. Treat the extrapolation as an extrapolation: levels 3–4 were not run, and
real social graphs fold back on themselves, so the true number is a ceiling, not a
forecast.

Shape of what comes back, sampled over 40 newly-discovered leagues: 37/40 are
8–14 teams (12-team ×18, 10 ×12, 14 ×4, 8 ×3, with 16 ×2 and 6 ×1 outside the
supported set), and `scoring_settings.rec` is 1.0 (full PPR) for 28, 0.5 for 11,
0.75 for 1. So a corpus crawl needs a scoring filter, not just a team-count filter,
and roughly a quarter of what it finds is not PPR.

### And the *other* thing it measures is bad news for borrowed drafts

**`claude/borrowed-drafts.md`'s premise is weaker than that doc assumes, and this is
the first evidence either way.** Its idea is: a leaguemate is in more leagues than
yours, so ingest those and a manager's sample widens. Measured, over the 10 usable
drafts found at level 1, the number of *additional* drafts found per seed-league
member was:

    [4, 3, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1]     Allan himself: 1

So the median manager gains **one** extra draft. Going from one or two drafts to
two or three does not move a fit that `AGENTS.md` already calls "mostly noise" — it
is the same thin-data problem with a slightly larger n. Widening the crawl to level
2+ does not help this at all: those drafts belong to *strangers*, not to the
fourteen people being modelled.

**The conclusion this forces, and it is the useful one:** the corpus is worth
building for **archetypes** (learn behaviour classes from thousands of strangers,
then assign a thin manager to a class) and **not** worth building for **borrowed
individual history** (there is barely any). That splits `borrowed-drafts.md` in
half along a line it did not know was there — its normalization work already
shipped in Phase 1 and was worth it independently; its data-borrowing half should
be re-read against this measurement before anyone builds it.

---

## What already exists (do not rebuild this)

The scoring function already conditions on both round and ADP —
`PickScorer.java:12-15` documents the four live terms:

    score = w_adp  * valueDelta                                   PickScorer.java:103
          + w_pos  * (log P(position | round) + log positionalTilt) PickScorer.java:83
          + w_need * rosterNeed
          + w_run  * runPressure                                   PickScorer.java:114

So "predict per round depending on ADP" is the scorer as written. The missing term
is affinity, and the design doc's §5 already reserves `w_aff * affinity(player, team)`.

`player` (`V1__init.sql:4-16`) already stores four of the seven features the vector
below wants: `positions`, `team`, `age`, `years_exp`.

## The rejected option: one embedding space for notes and players

**Not a live hazard in this plan — it is the road not taken, kept written down so a
future session doesn't rediscover it as a good idea.** Neither half of the design
below embeds text: notes become structured attributes, players become feature
vectors. Recorded here for the same reason `next-features-roadmap.md` §3.4 records
the schema merge not to do.

Embedding `"drafts his own Bengals"` and `"Ja'Marr Chase, WR, CIN"` and taking the
distance produces a number that moves in plausible directions. It is measuring
lexical and semantic overlap — both strings mention Cincinnati — not preference.

There is no way, from the score alone, to tell whether it captured a real tendency or
a shared token. That is the specific failure this project keeps trying to avoid:
output that looks more principled than it is, with no signal that it has gone wrong.
Text-embedding a manager and a player into a shared space is not a preference model.

### Where that failure actually lives now — added 2026-09-07

Structured attributes solve the *lexical overlap* half and **not** the *fabricated
confidence* half, and step 2 below reintroduces the second one a layer up. Read the
plan's own example again:

    "drafts his own Bengals"  ->  {teamAffinity: "CIN", strength: 0.8}

Nobody said 0.8. Allan said "drafts his own Bengals." An LLM invented the magnitude,
and once it is a float in `manual_json` it is indistinguishable in a payload from
`reachBias`, which a human actually typed, or from a fitted tilt, which came off real
picks. Same disease as the embedding version — a number that looks principled with no
signal when it's wrong — carried by a different vector.

Three constraints on step 2 that fall out of this, and they are cheap:

1. **Extraction emits the categorical attribute, not the magnitude.** `teamAffinity:
   "CIN"` is a reading of what the sentence says. `strength: 0.8` is not in the
   sentence. If a strength is wanted, it comes from a control next to the note that
   Allan sets himself — the same "stated is a prior, not an override" shape the
   tendencies UI already uses — or from a single hand-set constant in
   `weights.yml`, declared arbitrary like every other number in that file.
2. **The extraction is shown, not just applied.** The seat card displays what the
   note was read to mean, and lets it be corrected or dismissed. An attribute
   nobody can see is an attribute nobody can catch being wrong.
3. **Provenance says `STATED`, never `FITTED`.** An attribute derived from a
   sentence Allan typed is his opinion, machine-parsed. It is not evidence, and the
   existing `NEUTRAL`/`STATED`/`FITTED`/`BLENDED` display must not blur that — an
   extraction failure should degrade to "no attribute", not to a confident one.

Today none of this is live: `note` is stored, rendered on the seat card, and read by
nothing (`ManualTendencies.java:18` — "not used by the engine"). Step 2 is the moment
it stops being inert, which is the moment these constraints start mattering.

## The version that works

**Player vectors built from features, not text.** Computed at ingest from what makes
players substitutable:

    position (one-hot), ADP / positional rank, age, years_exp,
    team, depth_chart_order, and — when a stats source exists —
    target share, snap share, usage rate

In that space, proximity means "these two are interchangeable for a drafter", which
is the property affinity needs.

**Measured caveat on `depth_chart_order` (2026-09-07):** it is present in Sleeper's
player dump (Ja'Marr Chase: `depth_chart_order: 1`, `depth_chart_position: "LWR"`)
but **not persisted** — `PlayerIngestService` keeps team/status/injury/age/years_exp
and drops it (`PlayerIngestService.java:60-61`). And it is only populated for
**1,513 of the 2,719 rostered players** in the dump (56%). It is a real feature, it
is free to start persisting, and it needs a null story for nearly half the pool.
Do not write a distance function that silently treats missing as zero.

**Revealed preference as a centroid.** A manager's drafted players give a centroid
(or better, a direction: centroid minus the ADP-expected centroid, so it measures
deviation from consensus rather than just "drafts good players"). A candidate scores
on cosine similarity to that direction.

**Notes as structured attributes, not vectors.** One LLM extraction pass per note:

    "drafts his own Bengals"        -> {teamAffinity: "CIN", strength: 0.8}
    "never takes a QB before 8"     -> {positionFloor: {QB: 8}}
    "panics after a run"            -> {runSensitivity: 1.6}

The scorer reads these directly. There are fourteen notes; finding similar ones is
not a problem anyone has. The note itself already exists as a free-text field on
the stated half of a profile (`ManagerProfile.java:32`, persisted in
`manager_profile.manual_json`, `V1__init.sql:97`), so extraction adds a sibling
structured field there rather than a new table.

**Corrected 2026-09-07: all three examples above target levers the engine does not
have**, and the correction is the useful part of this section. Checked against
`PickScorer.score()` (`PickScorer.java:57-73`), which takes exactly three
per-manager-influenced inputs — `reachBias`, `positionalTerm` (which folds in
`profile.tilt(pos)`, `PickScorer.java:83-85`) and `runTerm` — plus
`unpredictability` multiplying that seat's temperature outside the scorer. So:

| the example | lever it needs | exists? |
| --- | --- | --- |
| `teamAffinity: CIN` | a team-affinity term | **no** — that *is* step 3's `w_aff` |
| `positionFloor: {QB: 8}` | per-manager, round-gated position floor | **no** — tilt is position-only; the round gate that exists is global K/DEF |
| `runSensitivity: 1.6` | per-manager run weight | **no** — `w_run` is global |

Extraction was never the hard part. The destination is. Writing the extractor
against attributes nothing consumes produces a tidy JSON blob that changes no
simulation — which is exactly the "looks principled, does nothing" outcome this
doc exists to avoid.

### The process, concretely — and why v1 is a form assistant, not a signal path

Define the closed attribute set **backwards from the dials that exist**, and step 2
needs no engine change, no new provenance concept, and no new table:

    Stage 0  The closed set IS the three dials: reachBias, positionalTilt[pos],
             unpredictability. Nothing else is extractable in v1. (Making
             positionalTilt settable is a small, separable change -- it is
             fitted-only today by an explicit decision, see HANDOFF.)

    Stage 1  Extract. One LLM call per note, temperature 0, fixed JSON schema
             over that closed set. Output is {dial, direction} plus an explicit
             `unmapped: [...]` list of the parts of the note it could NOT map.
             No magnitudes invented -- see the constraints above.

    Stage 2  Store as a sibling of the note in manual_json:
             {noteHash, extractedAt, proposed: [...], unmapped: [...]}.
             The raw note is never overwritten. The hash gates re-extraction, so
             the pass is idempotent and only re-runs when the note changes.

    Stage 3  Review. /managers renders "read as: reaches ~8 picks early" with
             accept / edit / dismiss. NOTHING reaches the engine unaccepted.

    Stage 4  Consume. Accepting writes the ordinary reachBias /
             unpredictability fields the tendencies API already writes
             (ManagerController PUT /api/managers/{id}/tendencies). The engine
             sees a number Allan confirmed, through the path that already
             exists.

**Stage 2's storage is safe as of 2026-09-07, and it was not before.** `saveManual`
used to assign `excluded.manual_json`, replacing the whole column from a three-field
Java record — so the sidecar above would have been destroyed by the next save from
either tendencies UI, silently, the same shape as the `adp_at_time` null-wipe in
`HANDOFF.md`. It is now a jsonb merge (`manual_json || excluded.manual_json`,
`ManagerProfileRepository.saveManual`): the three known keys are still replaced
wholesale on every PUT, and unknown keys survive. `clearManual` is the deliberate
exception — DELETE resets the column to `{}`, because a reading of a note that no
longer exists is garbage rather than state worth keeping. Pinned by
`ManagerProfileManualJsonIT` (real Postgres; verified to fail against the old
assignment before the fix landed).

Two properties fall out of Stage 3/4 and they are the whole reason to build it this
way. **The fabricated-confidence problem disappears** — the extractor proposes, a
human disposes, and the value the engine reads is one Allan accepted, so it is
`STATED` in the plainest sense rather than a machine guess wearing a human's
provenance. And **`unmapped` becomes the backlog**: if six of fourteen notes say
something team-flavoured that nothing can consume, that is the evidence for building
step 3's affinity term — measured demand instead of a hunch. If nothing lands there,
step 3 was never worth building.

The honest cost of this framing: v1 does not make the engine smarter. It makes a
form easier to fill in and tells you which lever to build next. That is a smaller
claim than "notes now drive the simulation," and it is the true one.

## Where a vector store earns its place

Only at corpus scale — which, per the measurement above, is now **reachable rather
than hypothetical**:

- Represent each manager-season as a behaviour vector across thousands of drafts.
- Cluster into archetypes (zero-RB, hero-RB, late-QB, and whatever else falls out).
- Assign a fifteen-pick manager to an archetype rather than fitting them individually.

That is a materially better answer to sparse data than shrinking toward a league mean,
and it is the plan's own "borrow strength from public drafts" idea made concrete —
and, per the measurement, it is the *only* half of that idea the data supports.

Note that even this is k-means over a modest number of dimensions. You would *store*
vectors, not search them. The measured corpus is thousands of drafts, not 10^5+ rows,
so nothing here has changed that.

## Recommendation on infrastructure

`pgvector` in the Postgres already running. Not a separate service.

Fourteen managers and a few hundred relevant players do not need approximate nearest
neighbour — that starts paying off around 10^5 rows. A second datastore for this would
be architecture theatre, and one more thing to operate for a single-user app.

## Honest constraint, again

Fitting how much affinity matters needs data this league does not have. With ~15 picks
per manager, an affinity weight learned from history is noise.

So the shippable version is: compute the vectors, expose the term, and put `w_aff` in
`weights.yml` hand-set like every other weight — declared arbitrary and tunable, per
that file's own header ("EVERY NUMBER IN THIS FILE IS A GUESS"). Whether it helps is
then something you can look at, which is more than can be said for learning it from
fifteen picks.

## Order, if this gets built

1. ~~Feasibility script: can public Sleeper drafts be enumerated at volume?~~
   **DONE 2026-09-07 — yes.** See the measured table above. Scripts kept in
   `claude/scripts/`; both are read-only and rerunnable.
2. **Note extraction into structured attributes.** Small, useful immediately, no
   corpus, no migration (`manual_json` is already a jsonb blob). The one genuinely
   new dependency is an LLM call in the ingest/API path, which this codebase does
   not have anywhere yet — that is the decision to make consciously, not the
   extraction itself.
3. **Player feature vectors + affinity term**, weight hand-set in `weights.yml`.
   Adds a fifth term to `PickScorer`'s documented four. This is the change that
   touches the hot path the ~20–30× refactor in `be423eb` produced — read
   `board-first-layout-and-pick-latency.md` §B2 before adding a per-candidate
   cosine to a loop that was specifically flattened to avoid per-candidate work.
4. **Archetype clustering** — now unblocked by step 1, and still the largest piece.

## Acceptance criteria, per step

**Step 2 (notes → attributes).** Extraction is idempotent and re-runnable; a note
that yields nothing structured stores nothing rather than a fabricated attribute;
the raw note text is never overwritten by its extraction; a seat whose note produced
an attribute shows *which* attribute in the API payload, not just a changed number
(the `Confidence`/provenance convention in `AGENTS.md`). **No magnitude the note did
not contain is invented** — per the three constraints in "Where that failure actually
lives now" above, a strength/weight comes from Allan or from `weights.yml`, never
from the extractor, and the attribute's provenance reads `STATED`.

**Step 3 (affinity term).** With `w_aff: 0` the engine's seeded-RNG output is
**byte-identical** to today — the same bar the `PickDecider` extraction was held to.
Measured before/after wall clock on a 500-iteration run, per §B1's method, showing
the added term's real cost. A player with no `depth_chart_order` (44% of the pool)
scores without a silent zero.

**Step 4 (archetypes).** Before any clustering is trusted: the corpus crawl is
re-runnable and its output is *stored*, not re-fetched (the measurement above cost
43 s for two levels; level 4 is minutes and should not be repeated casually). Any
archetype assignment rides to the UI with the same provenance honesty as
`NEUTRAL`/`STATED`/`FITTED`/`BLENDED` does today — a manager assigned to a cluster
must never be displayed as though they were individually fitted.

## Explicitly not being built

- **A shared text-embedding space for notes and players** — the trap above. This is
  the appealing wrong answer this doc exists to prevent.
- **A second datastore.** `pgvector` in the existing Postgres or nothing.
- **Learning `w_aff` from fifteen picks.** Hand-set in config, labelled a guess.
- **Borrowed individual draft history as a way to widen a manager's own sample** —
  measured above as roughly +1 draft per manager. Not worth the ingest path. This
  is a change of position from `claude/borrowed-drafts.md`, on evidence that doc
  did not have.

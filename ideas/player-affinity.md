# Player affinity, embeddings, and where a vector store would actually help

Design note, 2026-08-29. Nothing here is built. Recorded so the reasoning does not
have to be redone, and so the appealing-but-wrong version does not get built by
accident later.

## The question

Can manager tendencies live in a vector database — notes and players in one space —
so that "players near each other" produces a per-round prediction conditioned on ADP?

Short answer: player similarity is genuinely valuable and slots into the existing
engine cleanly. Putting notes and players in a *shared* embedding space is the part
to avoid. And the round/ADP conditioning already exists.

## What already exists (do not rebuild this)

The scoring function already conditions on both round and ADP:

    positionalPrior(round, position)   ->  P(position | round), fit from history
    valueDelta(pickNo, adp, reachBias) ->  board position against pick number

So "predict per round depending on ADP" is the scorer as written. The missing term is
affinity, and `PickScorer` has room for it — the design doc's §5 already reserves
`w_aff * affinity(player, team)`.

## The trap: one embedding space for notes and players

Embedding `"drafts his own Bengals"` and `"Ja'Marr Chase, WR, CIN"` and taking the
distance produces a number that moves in plausible directions. It is measuring
lexical and semantic overlap — both strings mention Cincinnati — not preference.

There is no way, from the score alone, to tell whether it captured a real tendency or
a shared token. That is the specific failure this project keeps trying to avoid:
output that looks more principled than it is, with no signal that it has gone wrong.
Text-embedding a manager and a player into a shared space is not a preference model.

## The version that works

**Player vectors built from features, not text.** Computed at ingest from what makes
players substitutable:

    position (one-hot), ADP / positional rank, age, years_exp,
    team, depth_chart_order, and — when a stats source exists —
    target share, snap share, usage rate

In that space, proximity means "these two are interchangeable for a drafter", which
is the property affinity needs.

**Revealed preference as a centroid.** A manager's drafted players give a centroid
(or better, a direction: centroid minus the ADP-expected centroid, so it measures
deviation from consensus rather than just "drafts good players"). A candidate scores
on cosine similarity to that direction.

**Notes as structured attributes, not vectors.** One LLM extraction pass per note:

    "drafts his own Bengals"        -> {teamAffinity: "CIN", strength: 0.8}
    "never takes a QB before 8"     -> {positionFloor: {QB: 8}}
    "panics after a run"            -> {runSensitivity: 1.6}

The scorer reads these directly. There are fourteen notes; finding similar ones is
not a problem anyone has.

## Where a vector store earns its place

Only at corpus scale. If a large public-draft corpus turns out to be reachable
(open question #2 in the plan — **unverified**, and worth a small feasibility script
before anything else here), then:

- Represent each manager-season as a behaviour vector across thousands of drafts.
- Cluster into archetypes (zero-RB, hero-RB, late-QB, and whatever else falls out).
- Assign a fifteen-pick manager to an archetype rather than fitting them individually.

That is a materially better answer to sparse data than shrinking toward a league mean,
and it is the plan's own "borrow strength from public drafts" idea made concrete.

Note that even this is k-means over a modest number of dimensions. You would *store*
vectors, not search them.

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
the project's own convention that an arbitrary modelling choice should be configurable
rather than presented as principled. Whether it helps is then something you can look
at, which is more than can be said for learning it from fifteen picks.

## Order, if this gets built

1. Feasibility script: can public Sleeper drafts be enumerated at volume? Everything
   at corpus scale depends on the answer and nobody has checked.
2. Note extraction into structured attributes. Small, useful immediately, no corpus.
3. Player feature vectors + affinity term, weight hand-set in config.
4. Archetype clustering — only if step 1 came back positive.

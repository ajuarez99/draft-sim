# Future optimization ideas

Not a plan — a parking lot for speed/scale ideas that came up but weren't needed
at the time, so the next session doesn't have to rediscover the reasoning for
shelving them. Promote an entry to its own plan doc when it's actually worth
building.

---

## Horizon truncation (resim only simulates a window of remaining picks)

Raised 2026-09-01, in conversation, after `board-first-layout-and-pick-latency.md`
§B2/§B3 shipped. The idea: at pick 5, don't resimulate all the way to pick 210 —
stop at some horizon (e.g. pick 23) and leave the board past that point
un-recalculated.

**This is the same idea as that doc's §B6 "horizon truncation" lever**, which was
explicitly held back rather than built: `DraftSimulator.run()` loops all 210 picks
with no early exit; adding a `horizonPicks` cutoff gets roughly a 4x speedup on a
mid-draft resim, but leaves the board past the horizon with no fresh prediction —
needing either a visible "not recalculated" state or a background full-fidelity
run that swaps in behind the reveal head. Real added complexity.

**Why it's shelved, not just deferred:** §B2/§B3 (the scoring hot-path refactor)
already solved the problem this would solve. Measured on the real fantasy(heart)
board: a 500-iteration resim went from ~5s to **~0.2s**, a 2000-iteration cold run
from ~25s to **~0.8s** — both well inside the doc's own targets (<450ms / <3s)
with no truncation involved. Allan confirmed live on 2026-09-01 that the resim
feels fast in practice. There is currently no speed problem for this to fix.

**When to revisit:** if iteration counts get pushed much higher (thousands, for
precision rather than speed — see the "headroom to raise iteration count" framing
from the 2026-09-01 conversation), or if a future feature (e.g. `next-features-roadmap.md`
Feature C's turn-by-turn mock room) reintroduces a latency budget the current
numbers don't cover. Re-measure before building — don't assume the old 18.5s-era
math still applies.

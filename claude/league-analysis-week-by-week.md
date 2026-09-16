# League analysis, week by week

Four additions to `/leagues/:sleeperLeagueId/analysis`, asked for 2026-09-16 as
"week by week like ffwrapped" and built the same day.
`claude/league-analysis.md` and `claude/league-analysis-lineups-and-matchups.md`
are the page's first two briefs; this one only says what changes.

The page had two time horizons and nothing between them: **the rest of the
season** (one summed bar) and **the next game** (one matchup block). Every
question of the form "when" fell into that gap — which week is the bye, who was
hot in week 6, who plays whom in week 11.

## The four

1. **A week stepper on the matchups.** Chips for every remaining week; the
   block is rebuilt for whichever is chosen, lineups and all.
2. **A week strip in each lineup drawer.** The rest-of-season bar taken apart
   into the weeks that made it.
3. **A scored-week grid.** What every roster actually scored, week by week,
   with the week's best marked.
4. **The bump chart**, fed by the grid's per-week scoring rank.

## The bump chart, and the non-goal it does not break

Both earlier briefs list "a second bump chart" as a non-goal: Power rankings
draws one, and building it twice is the failure this repo keeps re-learning.
That still holds, so **the chart was extracted, not rewritten**.
`web/src/components/BumpChart.tsx` is the component that used to live inside
`PowerRankings.tsx`, moved unchanged; both pages import it. `segmentsOf` and
`SeriesPoint` are re-exported from `PowerRankings` so its existing chart test
keeps importing the rule from the page that owns its meaning.

What makes this a second chart rather than a second *implementation* is the
series: Power rankings plots its composite ladder, which the commissioner and
the voters feed. This plots **what each roster actually scored that week**.
Different claim, different source, same drawing.

## The bug this work created, and what it taught

The first cut of the week strip re-ran the lineup assembly for each week — the
obvious thing, and what the matchup block correctly does. Live, kieriskash's
thirteen weeks summed to **1777.1 under a bar reading 1683.9**.

Neither number was wrong. Setting the best lineup every week beats locking one
lineup for thirteen weeks, always, so the weekly optimum is the larger number
by construction. They were two correct answers to two different questions,
printed a centimetre apart with nothing saying which was which.

**The strip now values the bar's own lineup, week by week**, so it decomposes
the thing it sits under. `LeagueAnalysisServiceTest` pins that it sums, and the
bye still shows: kieriskash's week 6 reads 89.2 against a 149.1 best week,
where the re-optimising version had smoothed it to 116.5 by shuffling in a
bench player. The dip is the whole point of the chart, and the "better" number
was hiding it.

The week-by-week optimum is a real number with a real home — the matchup
preview, where the question genuinely is "what would this roster start in THAT
game". It stays there.

(Display rounding means the strip's printed weeks sum to 1683.8 against a bar
of 1683.9 — thirteen values rounded to a tenth. That is a tenth of a point on a
projection, not two different lineups.)

## Cost

One query, not thirteen: `PlayerProjectionRepository.totalsByPlayerPerWeek`
reads the window once and keys it by week. The strip needs no lineup assembly
at all — it values starters the projections pass already chose. The whole
response went from 475 ms to **591 ms** and from ~30 KB to **51 KB**, with the
scored-week grid included.

The week stepper refetches with `?week=N` and **swaps only the matchup block
into state**. Replacing the whole response would reset every open lineup drawer
to answer a question that did not touch them.

## What live verification found

Verified 2026-09-16 against the real league and a real browser, on both the
live 2026 season (1 scored week) and the finished 2025 one (17).

**The grid printed one number five times.** With a single scored week, total,
average, high and low are all that one game — `165.0 165.0 165.0 165.0 165.0`
across a row, five columns pretending to be five facts. The summary columns now
appear from week two, and the page says so. Same discipline as the ranking
score's own min-weeks gate, and the third time that gate has earned its keep.

**A stale backend served the old week strip.** The sums still disagreed after
the fix because the running `bootRun` predated it; the unit test was already
green. Recompiling does not restart the server, and this repo's concurrent
sessions make "which build is on :8080" a real question — it is worth checking
the number changed, not just that the code did.

Working as intended, measured:

- Week 9 for kieriskash is a different opponent *and* a different lineup value
  (139.7, Gibbs at 25.2) than week 2 (142.0, Gibbs at 26.1). The stepper is
  genuinely revaluing, not re-labelling.
- The finished 2025 season shows 17 week columns, 17 "best of the week" cells
  (one per week — the tie rule holds), a 12-line bump chart, and popsharky's
  own row marked. Its matchup panel correctly offers **no** week chips: there
  are no remaining weeks to step through.
- No horizontal overflow at 952px of chart inside the page.

## The follow-up: "wheres the UI charts?"

Asked immediately after the four above shipped, and the answer was that on the
league Allan actually looks at, there were **none**:

- The scored-week bump chart needs two played weeks to draw a line. The 2026
  season has **one**. So the chart was correct to refuse and useless to have.
- The week strip was inside the lineup drawer, behind a click.

Meanwhile the response was already carrying thirteen weeks of projections that
nothing plotted. So there is now a second chart, **Projected week by week** --
each roster's projected rank across the remaining weeks, visible on a live
season from week 1. Its ranks are computed on the BACKEND through `Ranker`,
not in the page: ties are a rule this repo has already shipped wrong once, and
a frontend copy would be the second implementation.

**Neither chart was readable without a legend.** Twelve lines in twelve hashed
hues is a hairball -- `hueFor` spreads names around the wheel but not far
enough apart to tell a dozen apart. Power rankings already answers this with a
clickable legend that dims everything else, so both charts here use the same
markup rather than a second way of reading one chart. With kieriskash isolated,
the week-5 dive from 1st to 9th reads instantly as the bye it is.

## Non-goals

- **A win probability**, still. Unchanged from the previous brief.
- **Stepping the projections block by week.** The stepper drives the matchups,
  where a week is a game. The rest-of-season bar is not a statement about a
  chosen week and must not silently become one.

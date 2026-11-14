# Time Machine: the friendly look (068)

Date: 2026-11-13. Branch `068-time-machine-friendly-look`, off main `3cd8bef`. Web only: no backend,
API or migration change.

## What Allan asked for

"Un-AI" the Time Machine: make it look user friendly like a daily game (NYT Games, MapTap), instead
of harsh fonts and boxes everywhere.

Two of the references he named could not be found: "Gauntlet's research on daily games" and
"krillion". Nothing in the repo or a web search turned them up, so this pass works from NYT Games
and MapTap only. If either has a link, the wording should be re-read against it.

## What was there

- `web/src/pages/HistoricalDraft.tsx`: page header with an uppercase eyebrow, then four full-width
  `.panel` boxes (how it works, level, a 21-tile trophy grid, your games), chips for every label.
- `web/src/pages/HistoricalRoom.tsx` results branch: page header, then a stat-coverage box
  ("points 100.0% of box scores") above the finish, then eight more full-width boxes.
- The site's display face is Big Shoulders Display in uppercase (`.cond`), used for the finish
  headline, the clock banner and the bracket.

## What changed

Everything is scoped under a `.tm` class, so no other page moves.

- **Type.** `.tm` re-points `--font-body`, `--font-display` and `--font-num` to Nunito (added to the
  Google Fonts link in `web/index.html`). Uppercase tracking is removed from every Time Machine label.
- **One column.** The landing page and the results are a single centred 760px column (`.tm-page`).
  Panels are flattened there; a surface appears only where something repeats (a game row, a trophy,
  one of the three draft-story cards) and for the level card.
- **Landing.** A centred hero: mark, "Time Machine", one sentence, the sport switch, the one button.
  "How to play" is three numbered steps with no box. A first-timer sees the steps right under the
  button; once there are games, the games come first and the steps move to the bottom. Locked
  trophies sit behind one "N still to unlock" line instead of a wall of locked tiles.
- **Results.** The finish is the headline, with "Draft another year" under it. Then "The story of
  your draft": a new **What you got right** card, then the bust and the passed-on steal. Then what
  the game earned, hindsight, the pick table, the bracket, standings and the weeks. The
  what-counts disclosure moved from the top to a closed `<details>` at the bottom, with the counts
  in its summary line (the same treatment the room already had, plan review PR-16 of spec 063).
- **Room.** Same three-column layout and the same boxes (they separate working columns). Only the
  type, the Draft button, the search field and the empty roster slots are softened.

### What you got right

`gotRight()` in `HistoricalResults.tsx`, from the rows the pick table already shows
(`result.pickFinishes`): how many picks finished ahead of where they were drafted at their position,
and the biggest jump among them (a tie goes to the higher scorer). The card prints both ranks and
the points, so the claim can be checked against the table below it. It is absent for a game stored
before pick finishes existed. Ordering tests are in `HistoricalResults.test.tsx`.

## Not built, and why

- **A share button** (the Wordle / MapTap result text). It is the obvious next piece for a
  daily-game feel, but it is new behaviour, not look and wording.
- **A daily puzzle** (one shared season per day). Same reason; it also needs a backend.
- **Friendlier outcome headlines** ("Champion", "You missed the playoffs (7th of 12)"). They are
  pinned by `historicalCopy.outcome.test.ts` and shared with the games list; left as they are.
- **The per-row "(excludes bonuses)" note** in the room's player list. It reads as clutter, but a
  test pins it as a disclosure, so it stays.
- **The word "perfect"** for the hindsight roster. It is a greedy pick-by-pick roster, not the best
  possible one, so the page still calls it hindsight.

## Verification (2026-11-13)

Run for real, on the worktree's vite (5268) against a backend on 8168 and the `draftsim064` scratch
database (NBA 1970 to 2016, NFL 2015 to 2022):

- `npx tsc -b`, `npx vitest run` (178 files, 2,980 tests) and `npm run build`: all pass.
- Landing page at 1366px: hero, sport switch, steps, level, trophies, games.
- Results at 1366px for a new basketball game (1989-90) and at 375px for a new football game
  (2020), both auto-finished: finish, the three story cards, awards, hindsight, bracket, standings.
  The "biggest jump" on the card (PF28 to PF13) matched the pick table's largest green change.
- Results for a game stored before pick finishes (id 32): the got-right card is absent, the rest
  renders.
- Draft room at 1366px for a game left drafting.
- A phone-width overflow was found and fixed in this pass (a wide table widened the page).

Not run:

- A draft made by hand in the browser (the pane was hidden and would not take clicks); the test
  games were started and auto-finished over HTTP. The room's click paths are covered by the
  existing tests only.
- The draft room at phone width, the abandoned-game page, and the level-up toasts in the new face.
- Any browser other than the built-in pane; production.

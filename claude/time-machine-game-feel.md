# Time Machine: game feel (070)

Date: 2026-11-14. Branch `070-time-machine-game-feel`, off main `c6b1303`. Web only: no backend, API or
migration change. Follows `claude/time-machine-friendly-look.md` (068), which changed the look and left
behaviour alone.

## What Allan asked for

"Keep improving Time Machine, keep increasing our research on daily games and see what we are missing. The
functionality is there but it needs more UI-friendly elements."

## What daily games do, and where Time Machine stood

Read for this pass: coverage of Wordle and NYT Games, and MapTap (TechCrunch, June 2026). These are secondary
write-ups, not design docs from the makers. A search for sports daily games (Immaculate Grid, Poeltl) returned
nothing usable, so nothing here leans on them. "Gauntlet's research" and "krillion" from the 068 request are
still not found.

| What the daily games do | Time Machine before this pass (walked at 375px and 800px on 2026-11-14) |
|---|---|
| One number sums up the game (MapTap: a score out of 1,000) | Two totals side by side, and the ratio only inside a badge sentence |
| The result reads at a glance as a row of marks (Wordle squares, MapTap per-round emoji) | A seven-column table that scrolls sideways on a phone |
| One tap copies a plain-text result to send to a friend | No share at all |
| The result screen is short; detail is a tap away | Ten open sections; the results page ran to about nine phone screens |
| One shared puzzle a day, a streak, a countdown to the next | A random season per click; no day, no streak |
| A reveal: the answer arrives in steps | The whole season appears at once, although the tagline says "watch that season play out" |
| No account needed to play | Behind the Sleeper username gate, and absent from the signed-out landing page |
| The play screen is mostly the play surface | On a phone the sticky clock banner took about 143px and every player row repeated two notes |

## What was built

1. **Draft score.** Under the finish: your whole-season points as a share of the hindsight draft's, rounded
   down (`draftScore` in `web/src/historicalShare.ts`). The sentence under it prints both totals, so the number
   can be checked. It is the same ratio the near-perfect badge already states. Not shown for an old-format game.
2. **One square per pick.** In "The story of your draft", above the three cards (`PickGrid` in
   `HistoricalResults.tsx`). A square's colour and arrow are the sign of the Change column in the pick table:
   finished ahead of, at, or behind the position rank he was drafted at. Pressing a square prints that pick in a
   sentence. Each square carries an arrow as well as a colour and has an accessible name. The squares pop in one
   after another unless reduced motion is on.
3. **Share your result.** Beside "Draft another year". Copies the season, the finish, the draft score, the
   squares and a link; on a touch device it opens the share sheet. If the browser refuses both, the text is
   shown in a box to copy by hand.
4. **Folded detail.** The hindsight roster table, the pick table, the standings, the week-by-week replay and the
   XP ledger are closed `<details>` with their heading as the control. The XP total and the badge count stay in
   view. The bracket moved up, above the hindsight comparison, and stays open. "Would it have won?" now sits
   above the hindsight roster table instead of under it.
5. **Room rows.** A player's fantasy points last season are the row's own right-hand number, not the tail of the
   stat line. "Excludes bonuses" is said once in the list header instead of on every basketball row. "Ranked on
   2011-12" shows only when that season differs from the stat line's season.
6. **Phone room.** The sentence under the title is hidden (the how-to says it), and the clock banner is two
   rows: who is up with Auto-finish beside it, then the pace and Skip.

Two tests were changed, both because copy moved, and both still assert the disclosure:
`HistoricalRoom.test.tsx` now expects "excludes bonuses" exactly once, and reads the football points from the
row's number.

## Not built, and why

- **A game of the day** (one season for everybody, a streak, a countdown). This is the biggest gap against the
  references and it is backend work: a season chosen by date, bots seeded so every player faces the same draft,
  one game per person per day. It also needs a decision on whether a score from a shared puzzle is comparable
  when each player drafts from a different slot.
- **A season reveal.** Playing the weeks out one at a time (record ticking up, then the bracket) would make the
  tagline true. It touches the result branch, the level-up toasts and the live region, so it is its own change.
- **A shorter game.** Fifteen rounds is long next to a two-minute daily game. Fewer rounds, or drafting only the
  first few and auto-finishing, is a format decision for Allan.
- **Stats on the landing page** (best draft score, average, a streak). The games list does not carry each
  game's points, so this needs an API field.
- **Playing signed out**, and Time Machine on the signed-out landing page (open since the 2026-11-11 audit).
- **A share image.** Text pastes everywhere; an image needs a canvas card and a download step.

## Verification (2026-11-14)

Run for real, on this worktree's vite (port 5270, started in a terminal tab because the preview pane was at its
five-server cap) against the 068 backend on 8168 and the `draftsim064` scratch database:

- `npx tsc -b`, `npx vitest run` (179 files, 2,992 tests) and `npm run build`: all pass.
- Results at 375px for football 2020 (game 84): score 79 with "2,479.0 ... 3,108.7"; fifteen squares whose
  marks match the pick table row for row (▲ = ▼ ▼ ▼ ▲ ▲ ▲ ▼ ▲ ▼ ▼ ▼ ▲ ▲); pressing round 8 printed "Tyler
  Lockett, round 8: drafted as WR35, finished as WR8 (265.4 points)"; the pick table fold opened; no sideways
  page scroll.
- Share at 375px: the pane refused the clipboard, so the fallback box appeared with the expected five lines.
- Results at 800px for basketball 1989-90 (game 83) and for a game stored before pick finishes (game 32): score
  shown, no squares on game 32, the rest renders.
- Draft room at 375px and 800px for a game left drafting (game 85): points column, the two-row phone banner
  (about 112px, from about 143px read off the earlier screenshot), no sideways page scroll.

Not run:

- A successful clipboard copy and the phone share sheet (the pane allows neither).
- The phone room row with the Draft button showing, after its layout fix (the game was not on the fan's turn
  when re-checked).
- An old-format game's results, a draft made by hand, any browser other than the built-in pane, production.
- No separate adversarial review pass was run on this change.

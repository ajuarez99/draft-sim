# FantasyAlarm NBA mock draft simulator: research notes (agent report, 2026-10-09)

A Sonnet research agent ran one complete 12-team, 13-round mock at
https://www.fantasyalarm.com/nba/mock-draft-simulator with the default settings. It did not sign in or enter any data, and it viewed the page
at about 800px wide; mobile was not tested. It also tried to look at other sites for contrast. FantasyPros returned only its footer, and
ESPN, Yahoo, Underdog and Draft Sharks were not inspected, so there are **no contrast findings**.

## Observed

- **Setup is one card and one click.** The fields are:
  - team name, number of teams, draft slot (default Random), rounds;
  - scoring "format" (named after platforms), ADP source, positional ranks;
  - draft speed: 0.3s, 1s or 2s per pick;
  - roster slots as +/- steppers, with the total shown live.

  START DRAFT takes you straight into the room.
- **The room sits inside the article page.** Ads and the page header stay above it.
  - The left two-thirds shows the pick header, then a team chip strip, then **either** the Player List **or** the Full Board. The two share one toggle, so you can never see both.
  - The right third holds your roster slots, a team dropdown for viewing any roster, a pick counter ("0 / 156"), CSV export, and the draft log.
- **The pick header** shows "YOUR PICK" or "ON THE CLOCK" with the team's name, and "Round 1 · Pick 1 (1 of 12)". Its buttons are Auto Pick, Auto All (which becomes Cancel Auto while running) and the view toggle.
- **The team chip strip** puts a red ring on whoever is on the clock and an amber ring on you. Labels past T9 get cut off to "T1", which is a bug.
- **Board cells** show the player's name, an ADP badge, a position pill, the NBA team and its logo.
  - Your column is tinted.
  - The on-the-clock cell is empty, with a coloured left border and the words "ON THE CLOCK".
  - Future cells show a dash.
  - There are no snake arrows.
  - At about 800px only about 5 columns fit, and names get cut off.
- **The player list** shows rank, position pill, name, team, "ADP x.x", positional rank and a one-click red DRAFT button. There is no confirmation and no undo.
  - It has three view tabs: Overview, last season's stats (FP/G, PTS, REB, AST, ADP, GP) and next season's projections.
  - Filters are ALL plus the five positions and AVAILABLE. There is a search box and a HIDE DRAFTED toggle.
  - The list shows 50 rows at a time with a "Load more" button. Columns can't be sorted, and there are no category (9-cat) stats.
  - After each pick, the list scrolls itself back to the best available player.
- **Every pick gets a toast**, e.g. "Team 2 picks (R1.2) Victor Wembanyama, C · SAS · ADP 2.1". Your own picks get a green "YOU DRAFTED (R2.24)".
- **Auto All** finished about 130 picks in roughly 15 seconds. There is no explicit "skip to my pick" button and no pick clock for you.
- **The roster panel** fills slots as you draft (PG/SG/G/SF/PF/F/C/UTIL×3/BN×3), showing the pick number and player, with empty slots marked "Empty".
- **When the draft finishes**, you get "DRAFT COMPLETE", a summary line of the format, and buttons for View Full Board, Download CSV, Close and Draft Again. There is no grade, no recap and no share link.
- **The CPU drafts in roughly ADP order with a little noise.** Every team behaves the same.

## Gaps there (inferred from what's missing)

- No per-category strength view.
- No value-vs-ADP or availability signal.
- No explanation for picks.
- No grades or recap.
- No opponents who behave like specific managers.

## The agent's ranked transferable patterns

1. Start from defaults on one screen.
2. Let the user choose the draft speed.
3. Show a toast for every pick, carrying the same facts as the board cell.
4. Auto All, with a Cancel Auto to take back control.
5. Pack the decision facts into one board cell, and tint your own column.
6. Make the on-the-clock cell itself show that it's on the clock.
7. A roster panel that fills its slots as you draft.
8. Team chips that ring whoever is on the clock and let you inspect any roster.
9. Player-list tabs that swap the data but keep the layout; position pills; a Hide Drafted toggle.
10. Scroll the list back to the best available after each pick, and keep the pick counter and export always visible.

# Sleeper draft room: research notes (agent report, 2026-10-09)

Collected by a Sonnet research agent. **The browser was signed out**, so the only page it actually saw was the pre-draft "claim a team" lobby at
`sleeper.com/draft/nba/1414361905279049728?ftue=commish`. Everything about the live room (player list, queue, roster, timer, chat) comes from
articles or is inferred, and is labelled that way below. Web search turned up very little, so the complaints section is thin.

## Observed directly (pre-draft lobby, signed out)

- **Two-pane layout.** A narrow white sidebar sits on the left. It holds:
  - the league avatar and title ("Popsharky's Draft");
  - the format "4-team Standard Snake";
  - a three-number strip: **14 Rounds | 4 Teams | 2 Min Timer**;
  - a teal primary button, JOIN DRAFT, and an outlined CREATE MY OWN.
- **The board fills the rest of the page.**
  - Each column header is an orange **CLAIM** pill above a faint "Team N" placeholder.
  - The board is 14 rows by 4 columns, and it scrolls on its own, independently of the sidebar.
- **Cells.**
  - Each cell is a pale rounded tile with the pick label in its top-right corner, written as round.pick ("1.1", "2.4").
  - A tiny snake-direction arrow sits in the bottom-left corner: → on odd rounds and ← on even rounds. Some cells show ↓; the agent didn't confirm what that means.
  - Empty future cells are quiet and uniform.
- **Style.** Light theme with a flat pastel board. Saturated colour appears only on the things you can act on: teal for the primary button and orange for the claim pills.

## From articles (not observed)

- **Board.** Sleeper says the board is designed so position runs and opponents' positional needs are visible. It also offers dark mode and casting to a big screen.
- **Queue.** You queue targets in priority order. When the timer runs out, autopick takes from your queue first. If the queue is empty, it falls back to a need-aware, higher-ranked player.
- **Commissioner tools.** The commissioner can pause or undo, change a pick, and use "Force CPU Auto Pick" from the on-the-clock tile. Picks can be traded mid-draft.
- **Notifications.** Push notifications tell you when you're on the clock.
- **Mock drafts.** Mocks use learning AI opponents, a shared link for friends, custom roster slots, and custom ADP.

Not found anywhere: the player list's default columns, filters and sort; the player popover; the roster/needs panel; sounds; and where chat sits.

## The agent's ranked transferable patterns

1. The board is the main canvas, with only a small amount of context around it.
2. Every cell carries a round.pick label.
3. Each cell has a quiet snake-direction arrow.
4. You claim a seat in the column header.
5. The format is summarised as three numbers: rounds, teams, timer.
6. Autopick takes from your queue first, then falls back to need-aware ranking.
7. Commissioner actions live on the on-the-clock tile itself.
8. Position runs and opponents' needs are readable across the columns.
9. Colour discipline: saturated colour only on actionable things.
10. A cast or big-screen board view.

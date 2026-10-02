# Quickstart / verification: Player spotlight on the home page (spec 015)

The bar is the repo's own: run the real server, click the real UI. A green `npm test` is necessary,
not sufficient. Record each V-step's result in this file as **measured** (with the number or what was
seen) or **not run**, and never blur the two.

## Prerequisites

- Postgres on `localhost:5433` (AGENTS.md; **not** 5432, which is a separate, unrelated cluster).
- The viewer is in at least one ingested **NFL** league (in season; week 4 on 2026-10-02) and one
  ingested **NBA** league (pre-season until 2026-10-20). Re-ingest first if the board is stale:
  `curl -X POST localhost:8080/api/ingest/all/{sleeperLeagueId}`.
- Run from this worktree. `preview_start` launched from a worktree runs **main's** launch.json
  (memory `worktree_preview_serves_main_checkout`). Check that the running `bootRun`/vite process
  serves this tree before trusting anything below. If it doesn't, start vite from this worktree on
  the `-alt` port.

## Automated

```bash
cd web && npx tsc -b && npm test -- --run && npm run build
```

```bash
cd backend && ./gradlew test --tests '*NoSportNameInPlayerSpotlight*'
```

Check the source-scan test **ran** (not skipped) and that it covers the new file.

## Live checks

| # | Check | Expected | Spec |
|---|---|---|---|
| V1 | Open `/`, signed in | "Player spotlight" sits between From Sleeper and Mock drafts, one tab per league in "Your leagues" order | FR-001, FR-002 |
| V2 | Network panel on load | exactly one `/player-spotlight` request (first tab); "Your leagues" rows appear without waiting for it | FR-007, U1 |
| V3 | Open the NFL league's home in a second tab; compare | Top/Trending/Rookie entries, points, week label, owners and Free agent pills are identical | FR-004, SC-002, U11 |
| V4 | Select the NBA tab | the pre-season reasons and the 2026-10-20 start date, as the league home words them; **no** weekly-report request | SC-003, U5 |
| V5 | Select the NFL tab first time; time it | one weekly-report request after the spotlight; record both durations. The Top column has a loading state, the others don't wait | R2 (latency assumed until here) |
| V6 | Throttle to "Slow 4G", reload | time-to-"Your leagues" visible versus `main` under the same throttle; record both | SC-005 (not measured before this) |
| V7 | Switch back to an already-visited tab | no request, no skeleton | FR-008, U3 |
| V8 | Rail filter → NBA, then → NFL | the tab strip follows "Your leagues"; selection falls back to the first visible tab | FR-002, U8 |
| V9 | Block the `/player-spotlight` URL for one league (devtools request blocking) | that tab says it couldn't load and names the league; other tabs and sections are fine | FR-006, SC-004, U6 |
| V10 | Mobile preset (375px) | no horizontal page scroll; the league strip scrolls within itself; the three columns become the segmented control | FR-011, U10 |
| V11 | Keyboard: Tab to the strip, then arrow keys | selection moves and wraps; focus stays visible | U9 |
| V12 | "Open league" link | lands on the selected league's home | FR-009 |
| V13 | A league whose newest ingested season is past (if one exists locally) | the past-season sentence, not a blank panel. If none exists, record **not run** and cite the unit test instead | U7, R4 |

## Known, accepted, and to be stated in the hand-off

- The home page starts no refresh (research R7). Its data is as fresh as the daily run or the last
  league visit, and trending shows its age when it is old.
- 014's NBA "most recent night" cutoff is still unverified until a real NBA night (after 2026-10-21).
  This feature inherits it unchanged.

## Results (2026-10-02, T024)

Run against **this worktree's** code: `bootRun` on :8085 and vite on :5185 (proxy → 8085), both
started from `.claude/worktrees/015-home-roster-players`, not via `preview_start` (which reads the
main checkout's launch.json). Local Postgres on 5433. Signed in as popsharky, who has 1 NBA league
(Ball Knowers, pre-draft) and 4 NFL leagues (2026, NFL week 3 final). Dev build, so React StrictMode
is on.

| # | Result |
|---|---|
| V1 | **Measured, pass.** The section is after From Sleeper and before Mock drafts; tabs are Ball Knowers (NBA), West Coast Fantasy Football, test, fantasy😍, (Foot) Ball Knowers, which is "Your leagues" order. |
| V2 | **Measured, pass with a note.** Only the first league's `/player-spotlight` is requested on load. It appears **twice** in dev because StrictMode runs effects twice (the first is cancelled; `main.tsx` wraps the app in `<StrictMode>`). The unit test U1 asserts exactly one call without StrictMode. A production bundle was **not** measured. |
| V3 | **Measured, pass.** For (Foot) Ball Knowers, all three sections' full text (including hidden rows) is identical between the home tab and `/leagues/1346366555759341568`. The first comparison differed **only in Trending**, because opening the league page started its hourly refresh and replaced a 27-hour-old trending list; re-compared after, it was identical. This is research R7 seen live: the home page alone doesn't refresh. |
| V4 | **Measured, pass.** The NBA tab shows "No regular-season games yet. The regular season starts Tue, Oct 20…" for Top and Rookie watch, Trending shows "Updated 27 hours ago", and **no** weekly-report request was made. |
| V5 | **Measured (local, unthrottled).** NFL tab first open: spotlight requests 219 ms / 286 ms (StrictMode pair), then **one** weekly-report request starting about 290 ms after the click, taking 170 ms. The Top column filled about 460 ms after the click; Trending and Rookie watch didn't wait for it. R2's "one extra round trip" is confirmed at about 170 ms locally. Production latency was not measured. |
| V6 | **Partly measured.** Unthrottled local reload: `/api/drafts` finished at 187–195 ms; the first `/player-spotlight` started at 220 ms, after it, so it can't delay "Your leagues". The "Slow 4G vs `main`" comparison was **not run** (no throttling in the pane, and no `main` build served side by side). |
| V7 | **Measured, pass.** Switching between two visited tabs: 0 requests and no skeleton; the inactive panel stays mounted with `hidden`. |
| V8 | **Measured, pass.** Rail filter → NBA: the strip shows only Ball Knowers and selection moved to it from the (now hidden) NFL tab; the "Open league" link followed. The filter was restored to All sports after. |
| V9 | **Measured, pass.** The page's `fetch` was patched to return 500 for the `test` league's spotlight: that tab read "Couldn't load the player spotlight for test."; the other panel still had its 3 lists and Mock drafts was still rendered. |
| V10 | **Measured, pass.** At 375×812: document scroll width 375 (no sideways scroll); the league strip is 343 px wide with 824 px of content and scrolls within itself; the Top/Trending/Rookies segmented control is shown. |
| V11 | **Measured, pass (events dispatched, focus ring not judged by eye).** ArrowRight 0→1, ArrowLeft 1→0, ArrowLeft 0→4 (wrap), Home→0, End→4; focus followed the selection each time. |
| V12 | **Measured, pass.** "Open league" href is `/leagues/{selected id}` and follows tab changes (also seen under V8). |
| V13 | **Not run.** No local league's newest ingested season is a past one (every lineage is 2026). Covered by the unit test "U7: a past season gets a sentence, never a blank panel". |

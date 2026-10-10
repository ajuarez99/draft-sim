# Quickstart: verifying spec 024

This project's bar for "verified" is driving the real thing: the real server, the real browser and real endpoints. A green suite alone doesn't count. Each step below maps to a spec criterion.

*Amended after plan review (2026-10-09): the draft ids, test locations, the auto-pick rule, mock shape and parity method. Each change is marked.*

## Prerequisites

- Postgres on `localhost:5433`, the throwaway dev cluster. **Not** the unrelated server on 5432.
- The backend and frontend from **this worktree**: `preview_start` `draft-sim-024-api-8094` and `draft-sim-024-web-5195` (in the main checkout's `.claude/launch.json`, which uses `cd /d …\draft-sim-024`).
- The real league draft behind the screenshot, `1414306786223153152`. Ingest league `1414306784801239040` first with `POST /api/ingest/all/1414306784801239040`. It is pre-draft with no draft order: the "0 of 4 managers identified" case.
  - *Amended after review #3:* `1414361905279049728`, the URL in the request, is a Sleeper `league_mock` that ingest cannot reach.
- Already local: the 12-team NBA 2025 draft `1229352720230514688` (complete, 168 picks, for a filled board) and the NBA 2026 draft `1339351318128517120` (pre-draft, 12 teams).
- After any Java change, kill and restart the backend; `bootRun` doesn't hot-reload. After restarting Vite, hard-refresh open tabs.

## Automated

```bash
cd backend && ./gradlew test
```

```bash
cd web && npx tsc -b && npm test && npm run build
```

**Check the skipped count, not just BUILD SUCCESSFUL.** Integration tests skip silently when Postgres is down. The tests that must exist and pass (*locations amended after review #18*):

- **`MockDraftServiceTest`** (Mockito with the real `SportRules`). Each case asserts *which* player was chosen (lessons class 1):
  - PICK takes the first draftable target;
  - with no targets, it takes the top draftable player by ADP with `rosterNeed > benchFloor`. This includes a case that **would fail under `> 0`**: an NBA tenth pick with all nine starters filled;
  - a non-draftable player (an NFL kicker early) is never taken;
  - FINISH marks only the user's picks AUTO;
  - not your turn returns 409.
- **`TargetControllerIT`** (real Postgres):
  - the replace-whole-list round trip;
  - the `missing` list for off-board targets;
  - 400 cases;
  - a fork copies targets.
- **`AccessControlMvcIT`:**
  - `/api/targets` returns 401 on an anonymous PUT, including with the admin token;
  - a non-member gets 404;
  - another owner's mock gets 404;
  - `/api/mocks/{id}/auto` returns 404 for someone else's mock.
- **V30** runs against a real Postgres. An AUTO insert succeeds, a `draft_target` row with both scopes or neither is rejected, and two concurrent PUTs don't 500 (lessons class 2/3).
- **Web unit tests:**
  - the divider keyboard handling and its minimums;
  - compact versus full cell density;
  - accent-insensitive search ("jokic" finds Jokić);
  - targets marked taken from picks;
  - scarcity hides 0-pool positions with a note;
  - round.pick labels and snake arrows, including `reversalRound: 3`;
  - the Claim pill on a header with no `Seat`;
  - no "mine" marking when `mySlot` is undefined.

## Live checks (a 1440×900 viewport, then 375×812)

1. **SC-001 / US1:** open `/drafts/1229352720230514688/live` (filled, 12 teams) and `/drafts/1414306786223153152/live` (4 teams, pre-draft). Measure with `javascript_tool`:
   - the board and list bounding rectangles overlap by 0 px;
   - at the default split, at least 7 compact board rounds and at least 8 list rows are in view;
   - the divider can be dragged to both minimums, and dragging the board taller brings back full-size cells;
   - double-clicking the divider resets it, and its position persists across a reload.
2. **FR-003 / "same for the actual draft":** screenshot, at 1440 wide, the live room and the projection room for `1229352720230514688`, plus an ordinary 12-team NBA mock. The regions must sit in the same places: status, controls, compact row, board, divider, targets and list.
   - *Amended after review #21:* a fork is impossible for the 4-team league, because mocks need 8, 10, 12 or 14 teams and a `drafting` source.
3. **SC-003:** choose 10 random cells. Each must show round.pick, its kind under FR-008 (live: landed vs empty; projection: projected vs yours; mock: all real), and whether it's yours. All 10 must be readable from the screenshot.
4. **SC-004:** draft `1414306786223153152` before it starts must show:
   - one waiting message;
   - no "0/0" chip, but an "SG, SF: …" note if those counts are 0;
   - the summary "4 teams · 14 rounds · 2 min · snake", with the timer from the stored column, so it shows pre-draft;
   - unclaimed headers whose Claim sets `?slot=N`.
5. **SC-002:** click search, type "jokic", and the first row is Nikola Jokić.
6. **US3 / SC-005:**
   - add 3 targets and reorder them;
   - reload, or use a second browser profile signed in as the same user, and the list matches;
   - a different user sees an empty list;
   - in a mock, draft one target, and it shows as taken in the same update;
   - make a save fail (stop the backend), and the edit stays on screen with "Couldn't save targets — retry".
7. **US5 / SC-006:** start a 12-team NBA mock (14 rounds) and press auto-finish.
   - The response is `COMPLETE`, there is no per-pick reveal, and the feed shows "auto" on your picks only.
   - Time the request, and record the measured number in `verification.md`, not a guess.
8. **SC-007:** at 375×812, there's no horizontal page scroll in any of the three rooms, and one tap switches between Board and Players. A pick card that arrives while Players is showing is still visible. Check that at 1024 too (review #14).

Record the results in `verification.md`, keeping verified and assumed separate. Anything not run gets labelled "not run".

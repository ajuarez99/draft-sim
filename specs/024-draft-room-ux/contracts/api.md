# API contracts: Draft room UX (spec 024)

## Identity

Every endpoint here reads `X-Sleeper-User`, the same header the rest of the app uses. A missing header returns **401** on writes, **even with the admin token**, because a target needs an owner. On reads it returns an empty list, matching how `/api/mocks` treats an anonymous caller. A caller who isn't a member of a real draft's league gets **404** from both GET and PUT, through `membership.visibleDraft`, the same check `seats()` uses. *(Amended after plan review #19.)*

## GET /api/targets

**Query:** exactly one of `sleeperDraftId=<text>` or `mockSessionId=<long>`. Neither or both returns **400**.

**200:**

```json
{ "players": [ { "id": 812, "sleeperId": "4278", "name": "Nikola Jokić", "position": "C", "team": "DEN", "adp": 2.1, "positionalRank": 2 } ] }
```

- `players` comes back in rank order, as a full `PlayerRef` from the draft's sport board.
- *Amended after plan review #20:* a target whose player is no longer on the board comes back in a separate `"missing": [{ "sleeperId": "…", "name": "…" }]`, not in `players`. `PlayerRef.adp` is a primitive and would otherwise have to be made up. The strip shows a missing target with no survival number, never 0%.
- A mock owned by someone else returns **404**, the same as `MockDraftService.get`.

## PUT /api/targets

**Body:**

```json
{ "sleeperDraftId": "1414361905279049728", "sleeperPlayerIds": ["4278", "6462"] }
```

or the same with `"mockSessionId": 42` in place of `sleeperDraftId`.

- The body **replaces** the whole list. Order is rank, and an empty array clears the list.
- **400** for any of these:
  - a duplicate id;
  - an unknown sleeper id for the draft's sport (the sport comes from the draft or mock row; an unknown real draft is a 404);
  - more than 50 entries. That cap is a guess at a sane upper bound and is labelled arbitrary.
- **200** returns the saved list in the same shape as GET.
- A mock owned by someone else returns **404**.

**Client behaviour (FR-014a):**
- The list updates on screen straight away.
- If the PUT fails, the strip shows "Couldn't save targets — retry". The on-screen list stays as the user left it; it is neither rolled back nor marked as saved.

## POST /api/mocks/{id}/auto

**Body:** `{ "scope": "PICK" }` or `{ "scope": "FINISH" }`

- **`PICK`:** it must be the user's turn, otherwise **409** `"not the user's turn"`. Makes one AUTO pick for the user, then advances the bots to the user's next turn, the same as `/pick` does.
- **`FINISH`:** valid at any point while `IN_PROGRESS`. Every remaining pick is made in one transaction: the user's picks as AUTO, everyone else's as BOT. The response has `status: "COMPLETE"`.
- **Response:** **200** `MockSessionState`, the same shape `/pick` returns.
- **Already complete:** **409**.
- **Someone else's mock:** **404**.
- **How the player is chosen:** see research **A1**, which corrects R5. Every candidate must first pass `isDraftable`. Then the first draftable target, then the top draftable player by ADP with `rosterNeed > benchFloor`, then the top draftable player by ADP. Only players in `ctx.byId` are considered.
- **409 handling:** comes from the existing `ErrorHandler` mapping of `IllegalStateException`, so no controller work is needed.

## Changed responses

- `GET /api/drafts/{id}/seats` → `draftType?` and `pickTimerSeconds?`, both nullable. *(Amended after plan review #2: the timer is no longer on the live frame.)*
- `MockSessionState.picks[].source` may be `"AUTO"`.

## UI contract: `DraftRoomLayout` (web)

All three room pages render through this one layout (FR-003). Each named region accepts content or an explained-absence message. A region is never silently dropped.

| Region | Live | Projection | Mock |
|---|---|---|---|
| status / format summary | `LiveStatusBar` + summary with timer | `OnTheClock` + summary with timer | `TurnIndicator` + summary (no timer) |
| notices *(added after review #8)* | errors, fork error, resim progress | `seatsDirty` banner, re-run progress, errors | errors |
| prompt *(added after review #8)* | — | `PickPrompt` / pause banner | "Pick from full list" |
| controls | announce, cards, project again, continue as mock (primary; disabled pre-draft) | skip (the settings gear stays in the page-action slot; re-run lives there) | auto-pick, auto-finish (primary) |
| compact row *(FR-001c, user decision)* | feed ticker · your team · scarcity chips · Room read toggle | feed ticker · your team | feed ticker · your team |
| board | `DraftBoard` | `DraftBoard` | `DraftBoard` |
| divider | ✓ | ✓ | ✓ |
| target strip | survival when seat known | survival | "no survival in mocks" note |
| board absence content | "Connecting… / Waiting…" (pre-projection) | the "Start the mock draft" CTA (its `.start-overlay` CSS is **kept**) | — |
| player list | `AvailabilityPanel` | `AvailabilityPanel` | `AvailabilityPanel`, always mounted. The review confirmed a mock's idle state is always your turn or complete. On complete it shows "Draft complete". |

*The "below" region from the first version is removed (amended after review #8). Its content is now the compact row above the board.*

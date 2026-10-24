# Research: ADP compare + live auction board (spec 046)

Every "measured" line below was run against the live provider or `origin/main` `5512cd7` between
2026-10-21 and 2026-10-23. Anything not measured says so.

## R1. Where Sleeper's dynasty/redraft ADP comes from

- **Decision**: Read `stats.adp_dynasty` and `stats.adp_std` from the **same raw payload**
  `SleeperProjectionClient.season(sport, season)` already returns. Capture them **before**
  `SeasonProjectionIngestService` filters out stub rows.
- **Rationale**:
  - Measured: the unfiltered `nba/2026` payload has 1,857 rows; 835 carry `adp_dynasty < 999`.
  - The service keeps only rows with `pts` (529 of 2,120 with its position filter), because the
    rest are "stubs carrying an ADP and nothing else" (its own Javadoc).
  - Those stubs are exactly the deep rookies dynasty drafters care about, so capturing after the
    filter would lose them.
  - The service also strips every `adp_*` key from the stored line
    (`SeasonProjectionIngestService.java`, `if (key.startsWith("adp_")) continue;`), so nothing
    stores them today.
  - 999 means undrafted. It's not written (`adp_snapshot.adp` is `not null`; absence = unranked).
- **Alternatives**:
  - A second fetch: rejected. It's the same 1.5 MB payload.
  - Keeping ADP inside `player_season_projection`'s JSON line: rejected. ADP is a per-source,
    per-date fact, and `adp_snapshot` is already keyed exactly that way.
- **Not verified**: whether football's season payload carries `adp_dynasty` too. Football reads
  weekly projections, and the season endpoint isn't called for NFL (`refresh()` throws for
  non-NBA). v1 is NBA-only for both sources.

## R2. Yahoo: endpoint, limits, matching

- **Decision**: a new `YahooAdpClient` + `YahooAdpService`, server-side, NBA only.
  - Endpoint: `pub-api-ro.fantasysports.yahoo.com/fantasy/v2/league/nba.l.public;out=settings/players;position=ALL;start=N;count=25;sort=DA_AP;out=draft_analysis?format=json_f`
  - Pages of 25 until a page has no player with a numeric `average_pick`. Stop at 400 as a hard
    cap.
  - Fields read: `average_pick`, `average_cost`, `percent_drafted`, name, team abbr,
    `display_position`.
- **Measured (2026-10-21)**:
  - No auth needed. The response is 200 JSON.
  - It sends no `Access-Control-Allow-Origin`, so a browser can't call it.
  - 300 players fetched; **186** had a numeric ADP. ADP tops out around 123.
  - Adding `auctionvalues` to `out=` gives a 400 ("Invalid player resource").
- **Matching**: `PlayerMatcher.build(Sport.NBA, players)`, then `match(name, position, team)`,
  the same path FFC uses.
  - The prototype's own normaliser (strip accents, punctuation, Jr/Sr/II–V) matched 186/186
    against Sleeper names.
  - `PlayerMatcher` is not yet measured on Yahoo names. That's a quickstart step (V2), not an
    assumption.
  - Misses are counted and logged, as `FfcAdpService` does, and returned in the result. They're
    never dropped silently.
- **Rate/robustness**: 12 calls per refresh, once a day, with a 2s timeout each. On any failure
  the previous capture stays. A response shape change (no `fantasy_content.league.players`)
  rejects the whole fetch.
- **Alternatives**:
  - Browser fetch: impossible (no CORS).
  - Scraping `basketball.fantasysports.yahoo.com/nba/draftanalysis`: rejected. Its table renders
    client-side and the HTML had no player data (measured, 0 matches).

## R3. Storage: extend `adp_snapshot`, don't add a table

- **Decision**: V39 adds nullable `avg_cost numeric(6,2)` and `percent_drafted numeric(5,4)` to
  `adp_snapshot`. New source keys: `sleeper_dynasty`, `sleeper_redraft`, `yahoo`.
- **Rationale**:
  - V2 set the precedent: "new columns on adp_snapshot rather than a new table: every reader
    already keys on (sport, source, captured_on)".
  - Every board reader filters by `source` (`BoardRepository.load/latestCapture/topRanked`,
    `BoardService` backfill uses `SOURCE_BLEND`), so new sources can't leak into the board.
    Checked by reading every `adp_snapshot` query on main.
  - `source_scoring` (V2) records `'redraft'` / `'dynasty'`, so FR-002's "recorded as redraft" is
    data, not a comment.

## R4. Auction price + budget storage

- **Decision**: V39 also adds `draft_pick.amount int null` and `draft.budget int null`.
  - `PickMapper.toPickRow` reads `metadata.amount`: a string on the wire (measured: `"amount":
    "22"`). It parses defensively, so missing or garbage gives null.
  - `LeagueIngestService.ingestDraft` and `LiveDraftPoller` read `settings.budget` into the draft
    row.
  - `PickRow` gains `Integer amount`. `upsertPicks` / `replacePicks` write it.
- **Measured** on the completed draft `1415422395308609536`:
  - 230 picks, each with `metadata.amount`. Sum is 1,982.
  - `settings.budget = 200`, `settings.rounds = 23`, `type = "auction"`.
- **Alternatives**:
  - Computing budgets in the browser from Sleeper directly, as the prototype does: rejected by
    FR-010 (one poller, the existing one).

## R5. Keeping auction picks out of snake models (US4)

What reads pick order today, found by grepping every `draft_pick` reader on main:

| Reader | Today | Change |
|---|---|---|
| `DraftRepository.allCompletedPicks` → `ProfileService.fit` (reach, tilt, shrinkage N) | filters `l.platform = 'SLEEPER'` (spec 035 F1) | add `and d.draft_type <> 'auction'` beside it, with the same style of comment |
| `BoardService.backfillAdpAtTime` (the update joins `draft`) | `l.platform = 'SLEEPER'` | add the same `draft_type` filter. An auction pick's `adp_at_time` would be meaningless. |
| `BoardService.observedPickNumbers` | reads drafts named in `weights.yml observedDrafts` | skip with a warn when `draft_type = 'auction'`. It's config-driven, so this is a guard, not a live leak. |
| `DraftGradesService` / `DraftAndAdpJoin` (pick-slot value) | grade any draft by pick slot | return a "not graded: auction draft" result. Slot-value grading has no meaning for an auction. |
| Live room / Monte Carlo (`/drafts/{id}/board`, sims) | would simulate snake order | refuse with a clear 409 for auction drafts. The web app routes auction drafts to the auction board instead. |

- **Decision**: `draft_type` is the rule, as `platform` is for ESPN. Use one helper constant
  `DraftRepository.AUCTION = "auction"`, not a string repeated five times. Two implementations of
  one rule is this repo's recurring bug class (memory: multi-sport landmines).
- **Amendment to spec** (recorded in the plan, not silently): the live room and grades rows go
  beyond US4's list. They're the same rule, found by reading the code.

## R6. Blend + rescaling

- **Blend** (FR-007), exactly as the prototype:
  - `w = trust × min(|gap| / fullGap, 1)`
  - `blend = dyn + w × (yahoo − dyn)`
  - Players Yahoo doesn't rank keep `dyn`.
  - Defaults are 75% and 30, hand-set and labelled. They're computed **client-side**: the sliders
    must answer instantly, and the server sends the inputs.
- **Rescaling** (FR-008):
  - **Decision**: proportional surplus scaling.
    1. Take Yahoo's `avg_cost` values from the latest capture, sorted descending. Pad with $1 up
       to `N = teams × rounds` (the draft's own spot count).
    2. Give $1 to every spot, then scale the surplus `(c_i − 1)` so the total equals
       `teams × budget`.
    3. Blend $ for blended rank `i` is `1 + k × (c_i − 1)`.
  - **Measured on the 2026-10-21 data** (computed, not tuned):
    - Σ curve over 230 ranks ≈ 2,083, so k ≈ (2,000 − 230) / (2,083 − 230) ≈ 0.955.
    - This rescale lands the total exactly but makes the top end **lower** (Wembanyama ≈ $77),
      while reality was **higher** ($112).
    - So it fixes SC-005's sum and does **not** fix the top-end error. It's stated in the UI
      note and in verification, not hidden.
  - **Alternatives rejected**:
    - An exponent/convexity parameter chosen to hit $112: that's fitting to one draft, which is
      against the hard rules.
    - Value-over-replacement from projections: a bigger feature (`claude/auction-drafts.md`
      item 1), out of scope here.
  - **When there's no draft** (league with no current draft), or it isn't an auction: show
    Yahoo $ unscaled, labelled "Yahoo's leagues, not yours". Blend $ is shown only when league
    shape (teams, budget, spots) is known. Without a budget, a snake draft has no $ at all, and
    the column is hidden.
- **Gap colouring cutoff** (spec edge case): no tint when either ADP is past the deepest
  Yahoo-ranked ADP in the capture (≈123 on 2026-10-21). Above that depth the gap is an artifact
  of Yahoo stopping, not a disagreement.

## R7. Live updates

- **Decision**: reuse `GET /api/drafts/{id}/live-stream` (SSE, `LiveSnapshot`). The auction view
  subscribes via the existing `useLiveDraft`. On each change of `picksMade` it refetches
  `GET /api/leagues/{id}/adp-compare`, which returns rows plus auction state. The poller already
  runs at 2s while drafting (`DRAFTING_INTERVAL`).
- **Rationale**: it's one poller, it already handles reset, and auto-track starts on open.
- **SC-003 is measured, not assumed**: a quickstart step times sale-on-Sleeper → visible against a
  replayed or real draft.

## R8. Where the page lives

- **Decision**: route `/leagues/:sleeperLeagueId/adp`, labelled "ADP & prices" in the league rail
  for NBA leagues.
  - The draft room links to it.
  - The draft list sends auction drafts to it instead of the simulator room.
  - It's gated the same way as `schedule` (spec 035's `PlatformGate`, page key `adp`), Sleeper
    only.
- It's league-scoped because Blend $ needs the league's shape and the auction state needs its
  draft.

---

## Amended after review (2026-10-23)

Every finding in `plan-review.md` was accepted. The original R-sections above are left as written
so the correction is visible. Where they conflict with this section, **this section wins**.

### R1 → amended (F11)

- The Sleeper ADP capture is **its own pass over the fetched payload**: a new
  `SleeperAdpCaptureService` called with the raw list.
  - It runs **before** and independently of the projection-line validation. The `gp ≠ 1.0`
    rejection returns mid-loop and must not lose the ADP rows.
  - It has its own shrink guard, reusing `MIN_KEPT_SHARE_OF_STORED`.
- It gets its own daily step, `ADP_CAPTURE`, gated on "any NBA league exists", **not** on
  `ProjectionSeason.current`.
  - Reason: dynasty drafts happen in the offseason, when no season is in progress.
  - When the season-projection refresh does fetch, it hands the same payload to the capture, so
    there's no second fetch that day.
  - Otherwise the step makes its own `season()` call.
  - The season fetched is the newest NBA league's season.
- Football is out of v1. The spec's edge case is amended.

### R2 → amended (F16)

- Assert the **exact** built URL in a unit test, including `?format=json_f`. Without it the
  endpoint returns XML.
- Every numeric field is a string. `"-"` means unranked or none, and parses to null.
- Use `average_pick`, `average_cost` and `percent_drafted`, **not** the `preseason_*` fields.
  They're the full-history values. Record which on the source label.
- The shape check covers `fantasy_content.league` and `players`.
- The stop rule is: stop at the first page with no numeric `average_pick` (about 9 calls), hard
  cap 400 players.
- Gate it by `latestCapture(yahoo) == today`, as the season-projection step does. It must **not**
  be a `daily_capture` kind: that check constraint allows only `PLAYERS`/`BOARD`.

### R3 → amended (F5, F17)

- V39 adds a table **`adp_unmatched`** with columns `sport`, `source`, `captured_on`, `name`,
  `team`, `positions`, `adp`, `avg_cost`, `percent_drafted`. It's replaced per capture, like the
  day's `adp_snapshot` rows.
- The compare response returns these as rows with `playerId: null`.
- Table growth: about 1,900 `adp_snapshot` rows a day (817 + 903 + 185). That's fine; noted
  beside V2's precedent.

### R4 → amended (F4, F12)

- V39 also adds **`draft_pick.player_name text null`** and **`draft_pick.sleeper_player_id text
  null`**, filled by `PickMapper` from `metadata.first_name + last_name` and `player_id`.
- `amount`, `player_name` and `sleeper_player_id` are written with plain `= excluded.` on
  conflict. Sleeper sends them on every tick and they're the truth, so they're never coalesced.
- **No defaulted `amount`/`budget` parameters in main code.**
  - The draft `upsert` overloads that default later columns must pass `budget` explicitly.
  - Every main-code caller passes the real value or an explicit `null` with a reason.
  - Tests use a fixture helper.
  - This is the "optional param that encodes a rule" bug class, which has shipped three times.
- The live poller doesn't upsert draft rows; the plan was wrong. It gets an `updateBudget(draftId,
  Integer)` beside `updatePickTimer`, called when `settings.budget` is present, so a pre-draft
  budget change shows up. `LeagueIngestService.ingestDraft` writes `budget` on upsert.

### R5 → amended (F1, F2, F3, F8, F17)

The reader list, corrected:

| Reader | Auction behaviour |
|---|---|
| `DraftRepository.allCompletedPicks` → `ProfileService.fit` | excluded (as planned) |
| `BoardService.backfillAdpAtTime` | excluded (as planned) |
| `BoardService.observedPickNumbers` | skip with a warn (as planned) |
| `DraftGradesService.read` (+ `SeasonStartService.gradeSummary`, `DraftAndAdpJoin` grades state) | `available: false, reason: "AUCTION_DRAFT"`, a new constant, returned **before** any expensive work. It's added to `api.ts`'s `DraftGradesReason` union with its own sentence in `draftGrades.ts`. (F8: the `graded` field was wrong.) |
| `DraftAndAdpJoin.join` (stats leaderboard pick/round) | `pickNo`/`round` null for auction picks. The UI shows "auction" in that cell. |
| `SimulationService` (`POST /api/sims`, `/api/sims/stream`) | 409 `auction_draft` |
| `MockDraftService.createSessionFromDraft` (`POST /api/mocks/from-draft/{id}`) | 409 `auction_draft` |
| `LeagueController.recordPick` (`POST /api/drafts/{id}/picks`) | 409 `auction_draft` |
| `LeagueController.realBoard` (`GET /api/drafts/{id}/board`) | **unchanged.** It's the real completed board (F1). The web app doesn't link auction drafts to it; FR-015 routes them to `/leagues/:id/adp`, whose sales list is the real record. `DraftBoard`'s snake layout would put sales in the wrong columns, so it isn't offered for auctions. |
| `TradeVerdictService` (traded picks by round/slot) | reviewed, no change: low impact, since a startup auction's picks are rarely traded. Recorded here. |

- **One rule, one place**: `DraftRepository.modelsSnakeOrder(String draftType)` returns
  `!"auction".equals(type)`.
  - The SQL filters use `d.draft_type <> 'auction'` with a comment pointing at it.
  - The three 409s share one helper (`AuctionGuard.refuse(...)`).
  - `DraftRow` gains `draftType` so the guard needs no second query.
- `linear` drafts: they're out of scope and their behaviour is unchanged. `DraftSlot` mis-models
  them too, but none is ingested and changing them isn't this feature. Recorded, not fixed.

### R6 → amended (F6, F7, F9, F10)

- **Monotonicity (F6)**: only the blended **value** is monotonic in trust. Rank can reorder.
  - Counterexample (G = 30): P dyn 12 / Yahoo 11, Q dyn 40 / Yahoo 2. At t = 0.75, Q (11.5)
    passes P (11.975).
  - Measured: 20 Yahoo-favoured players get a later rank at t = 0.75.
  - The unit tests assert:
    1. value non-increasing in t for Yahoo-favoured players
    2. never loses ground to a non-favoured player
    3. t = 0 order equals dynasty order with the tie-break (dyn, redraft, name)
- **Reliability rule (F7)**:
  - `YAHOO_MIN_PERCENT_DRAFTED = 0.5`, hand-set and **ARBITRARY**. It's where the tail compression
    starts in 2026-10-23 data, and the label says so.
  - A Yahoo value under it is treated as unranked by both the tint and the blend. The deepest-ADP
    rule is dropped; that figure stays in `sources[yahoo]` as context.
  - The threshold is a client-side constant with a sent default, so verification can re-count the
    tints.
- **Rescale (F9)**:
  - Floor each Yahoo cost at $1 **before** sorting.
  - No Yahoo capture means no Blend $ (the column says "unavailable"), never a divide-by-zero.
  - The tolerance is ±$0.50 × N. SC-005 is an identity check, as stated.
- **Labels (F10)**: the Blend $ note says it's scaled to the league's total and does **not** model
  top-heaviness, with the 25–50% measurement. The Yahoo date reads "fetched on". The review's optional
  "unchanged since" marker is **not built in v1**. It would need its own storage, and "fetched on"
  already stops the date from claiming freshness.
- **Yahoo-ranked, dynasty-unranked**: no blend, and the row says "no dynasty ADP".

### R7 → amended (F13)

- Ordinary fetches send `X-Sleeper-User` through `apiFetch`, not `?user=`. Only the EventSource
  stream uses `?user=`, as today.

### R8 → amended (F13, decision)

- The page is **available for ESPN NBA leagues too**: the ADP sources are sport-wide. Only the
  auction mode needs a Sleeper draft. So **no `LeagueAvailability` gate** is added. That removes
  four touch-points and the 422 path.
- 404 means only "not visible".
- The rail entry is NBA-only (`destinations.ts`, same pattern as `schedule`'s sport check).

### E3 → amended (F15)

- Team budgets are computed per `draft_slot` 1..`teams`, with the manager name attached when
  `slot_to_manager` knows it. Before the draft order is set, cards still render as "Slot N".

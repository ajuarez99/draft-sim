# Research: Fan-first redesign

Phase 0 of [plan.md](plan.md). Each item was checked against the code on
`013-fan-first-redesign` (base `f684189`) or measured on production on
2026-09-30. Where a finding contradicts the spec or the design review, the
spec now has an "Amended after planning" note. Nothing was silently rewritten.

Legend: **Measured** = executed or observed. **Read** = confirmed from source.
**Decision** = chosen here.

---

## R1. Commissioner controls: most of US2 already exists

**Read.** `LeagueMembership.canCommission(leagueId, sleeperUserId)`
(`backend/.../store/LeagueMembership.java:182`) returns true for:
- the configured app owner (`OwnerProperties`), or
- a manager whose `league_member.is_commissioner` is true (Sleeper's `is_owner`, V9).

It is already sent to the client and already gates the UI in three places:

| Page | Control | UI gate today | Server gate today |
|---|---|---|---|
| Power rankings | Recompute (commissioner) | `ballot.canCommission` (`PowerRankings.tsx:927`) | key + `canCommission` |
| Season forecast | Recompute through week N | `data.canCommission` (`SeasonForecast.tsx:250`) | key + `canCommission` |
| Superlatives | conduct-list editor | `canEdit` = `canCommission` | key + `canCommission` |
| **History** | **Compute** (final ranks) | **none** (`LeagueHistory.tsx:325`) | **visible-league only, no key** (`LeagueHistoryController.java:818`) |
| History | Load past seasons (ingest) | none | visible-league only |

**Correction to the design review:** it said Forecast's Recompute and History's
Compute were "shown to every visitor". I was signed in as popsharky, who is the
Sleeper commissioner of "(Foot) Ball Knowers" and so passes `canCommission`.
Forecast's button is already hidden from everyone else. **History's Compute is
the only real gap.**

**Decision:**
- Add `canCommission` to the league-history response (same helper, no new rule)
  and hide Compute unless it's true.
- "Load past seasons" stays visible. It is a read-only fetch of the league's own
  public history from Sleeper, shown only on an empty or error state, and hiding it
  would strand a fan on a blank page.
- The backfill endpoint's server gate is **not** changed. It recomputes derived,
  idempotent data, and the spec keeps key/auth behavior out of scope. This is
  written down, not implied.
- The spec's "Sleeper flag only" is widened to what the server already does: the
  Sleeper flag **or the configured app owner**. Changing that would be an auth
  change.

**Alternatives:** gate backfill server-side with the key (rejected: out of scope,
and it breaks nothing today); client-side `is_owner` lookup (rejected: a second
implementation of `canCommission`, the repo's recurring "two implementations of
one rule" bug).

## R2. Weekly report's default week

**Measured** on prod (`api.ballknowers.co`, 2026-09-30):
`weekly-report/0` returns `week: 2, latestScoredWeek: 3`. Week 3 returns
`weekFinal: false`, while Sleeper's `state/nfl` reports `week: 4`. The page opens on
the latest **final** week by design. The label ("Week 2") gave no reason.

**Decision:** FR-019's label reads "Week 2 · latest final week" and, when a later
week is scored but not final, adds "Week 3 in progress →" as a link. Whether
week 3 *should* already be final once Sleeper has moved to week 4 is a data-refresh
question (spec 009's territory) and **out of scope here**. It is recorded as an
open observation, not fixed.

## R3. "Record vs all" and "median record"

**Read.** `ExpectedWinsService.expectedWins(List<Game>)`
(`engine/ExpectedWinsService.java:86`) already walks every week's scores and counts
opponents outscored, ties as half. All-play and median records are the same walk
counted differently:
- all-play W-L-T = teams beaten / lost to / tied, summed over weeks
- median W-L = beat the week's median score or not

**Decision:** add `allPlay` and `median` records to `ExpectedWinsService.TeamRow`
and the `/expected-wins` response. `api.ts` `ExpectedWinsTeam` gets them in the
same change (AGENTS.md hard rule). This covers the **current regular season only**,
which is what that service scopes to. Past seasons in the standings show "—" for
these columns. That's not faked, and it's spelled out in the "How this works"
disclosure.
**Invariant test** (ordering/consistency, lessons class 1): all-play wins + losses +
ties = weeks × (teams − 1) per team; the league's all-play wins sum equals its
losses sum.

## R4. Letter grades: where they are computed

**Read.** `SeasonSuperlativesService.EARLY_THRESHOLD_WEEKS = 4` is package-private
(`engine/SeasonSuperlativesService.java:35`). Hand-set constants live in
`config/weights.yml` under `draftsim:`, bound through `*Properties` classes in
`config/`.

**Decision:**
- Grades are computed **server-side**, in one place: a small `LetterGrades` helper
  that maps rank-within-league → grade using cutoffs bound from a new
  `draftsim.grades` block in `weights.yml`. The block's comment says the cutoffs
  are arbitrary, like the rest of the file.
- `EARLY_THRESHOLD_WEEKS` moves to one shared public constant (`SeasonWindow.EARLY_THRESHOLD_WEEKS`).
  Superlatives and grades both read it, so there's one rule and not a copy.
- `/roster-management` rows and `/analysis` ranking-score rows each gain `grade`.
  Each payload gains `gradesEarly: boolean`.
- Ties in rank get the same grade.

**Why server-side:** the threshold and cutoffs are backend config. Computing
client-side would mean mirroring both into TypeScript, which is the two-rules trap.

**Ordering test (lessons class 1):** for every pair of teams, a higher rank never
has a lower grade; 3 scored weeks → `gradesEarly: true`, 4 → `false`.

## R5. Draft room "Your pick" panel: availability exists in only two of the three rooms

**Read.**
- **Simulator** (`DraftView.tsx`): `result.availability` curves exist; `AvailabilityPanel` already renders them below the board.
- **Live room** (`LiveDraftView.tsx`): has projections and (spec 012) per-pick insight, including survival odds once the seat is known.
- **Mock draft** (`MockDraftView.tsx`): plays live against bots. There's **no simulation and no availability curve**. `SimRequest` is keyed to a Sleeper draft id (`api.ts:155`), which a mock session doesn't have.

**Decision:** the panel ships in all three rooms with tiers, photos and position
rank. The **availability bar appears only where a curve exists** (simulator, live
room). In a mock it says "Availability needs a simulation, which mocks don't run".
Adding simulations to mocks would be a new engine path, which the spec puts out of
scope. **Spec amended** (FR-020, SC-006, US6) to say so.

**Tiers:** by gaps in the board's existing ADP order (a new tier starts where the
ADP gap to the previous player exceeds a hand-set value, labelled arbitrary).
*Amended at task generation:* that value lives in `web/src/tiers.ts`, not in
`weights.yml`. Nothing on the backend reads it. No new data. If ADP is the 999 sentinel, the player goes in a trailing
"unranked" group.

## R6. Player photos and team logos

**Measured** (2026-09-30, HTTP 200):
- `https://sleepercdn.com/content/nfl/players/thumb/{sleeperId}.jpg`
- `https://sleepercdn.com/content/nba/players/thumb/{sleeperId}.jpg`
- `https://sleepercdn.com/images/team_logos/{nfl|nba}/{team lowercase}.png`

**Read.** `PlayerRef` already carries `sleeperId`, `team` (nullable) and `position`
(`api.ts:13`). Manager avatars already load from the same CDN (`Avatar.tsx:10`).

**Decision:** one `PlayerFace` component, `photo → team logo → initials`, driven by
`onError`. `DEF` goes straight to the logo. A null team skips to initials. No
backend change. Images are `loading="lazy"` with fixed dimensions so a 12×15 board
doesn't shift layout.
**Unverified:** that every player has a photo. The fallback exists for exactly that
reason.

## R7. Navigation already has one source of truth

**Read.** `destinations.ts` is "the one declaration of what pages a league has",
consumed by the rail, the matcher, the jump-to palette and the switcher, with a test
that fails if `App.tsx` gains a league route without a row.

**Decision:** grouping and fan labels are **fields on that table** (`group`,
`label`), not a second menu definition. The new league home (`/leagues/:id`) is a
new row. Existing addresses are untouched (FR-015). The draft-view collapsed rail
uses each row's glyph plus a `title`/`aria-label` from the same `label`.

## R8. League home data: composed from existing endpoints

**Read.** `isMe` already exists on analysis matchup sides, projections and score
rows (`api.ts:1404/1446/1477`), and on ballot members (`api.ts:1653`).

**Decision:** the league home is **client-composed** from `/analysis` (record, rank,
this week's matchup via `isMe`), `/history` (standings snippet), `/power` (headline)
and `/superlatives` (newest award). There's no new endpoint. Each block fails
independently, with its own empty state. A non-member sees no "you" block because
no row has `isMe`.

## R9. Manager archetypes and basketball

**Read.** `managerBehaviour.ts` is the single shared text source for tendencies
(used by the seat popover and /managers). Its header says **every basketball
manager has no reach measurement** (`picksScored = 0`, permanently for 2024/2025
drafts).

**Decision:** archetypes are derived in `managerBehaviour.ts`, with cutoffs in the
same file's labelled constants. Reach-based labels ("Reacher", "Waits") are used
**only when reach was measured**. Otherwise the label comes from positional tilt
alone ("Guards early"), or is "Not enough history". A manager is never called a
reacher from a league-average default.

## R10. Contrast

**Measured** (computed from the OKLCH values, WCAG 2 formula; not a browser tool):

| Pair | Ratio |
|---|---|
| page-dark text on new volt | 14.44 |
| `--up` on panel | 8.52 |
| `--down` on panel | 5.43 |
| `--muted` on bg / panel | 5.70 / 5.29 |
| **text on current `--crimson` fill** | **4.07 ✗** |

**Decision:** the crimson *fill* behind text (the "You" seat chip, mock setup's
1.01) gets a darker fill token (`--crimson-fill`) tuned until ≥ 4.5, and the tuned
value and ratio are recorded in the build notes. The `--crimson` ring and text
color stay as they are. The browser check in quickstart confirms the ratio on the
real page.

## R11. How much of the styling is shared

**Read.** `styles.css` is 4,583 lines with a documented house style (header
comment). There are ~125 uses of the `.mono` class across `.tsx` files, and ~182
uses of `panel`. Section titles are `.panel h2` / `.cond` (Oswald, uppercase).

**Decision:** Phase 1 changes the **shared rules first**. `.mono` becomes
Plus Jakarta with tabular numbers. `.panel h2` becomes sentence case, weight 600.
The surface tokens are consolidated. A page's own nested boxes are then removed page
by page. The house-style header comment is updated in the same change, because it is
the document future sessions read.

## Open, not resolved here (recorded, not guessed)

- Does Sleeper set `is_owner` on co-commissioners? Only a one-commissioner league
  was measured. There's no behavior change either way, since `canCommission`
  handles a plural flag.
- Why week 3 is not final while Sleeper is on week 4 (R2). Out of scope.
- The manager comparison page (`/managers/:a/versus/:b`) was never opened in the
  review. It gets US1's shared rules only, and it's checked in quickstart.

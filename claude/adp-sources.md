# draft-sim — multi-source ADP and mock data, 8- to 14-team leagues

## Verified this session (2026-08-29, evening) — §3 + §5 + §9 built, scoped down

FFC is now a real third input to the board, alongside search_rank and observed
order — `FfcClient`, `PlayerMatcher`, `FfcAdpService`, wired into
`BoardService.rebuild()` and `POST /api/ingest/adp` (also runs inside
`/api/ingest/all/{id}` and `/api/ingest/board`). V2 migration adds `stdev`,
`source_teams`, `source_scoring`, `sample_drafts`, `derived`, `derivation` to
`adp_snapshot` and a (currently empty) `player_alias` table.

**Scoped down from the full design below in one deliberate way, discovered by
actually calling the live API before writing code against it (per §12's own
instruction):** §3's central premise — "FFC is natively team-count and
scoring-aware" — is only half true right now. Verified live, both via the API
and FFC's own website: **scoring format is real** (standard/half-ppr/ppr have
different `total_drafts` and different per-player ADP — 1905 / 3302 / 8162
drafts respectively, this week). **Team count is not, yet.** `teams=8` and
`teams=14` return byte-identical `total_drafts` and identical per-player ADP
for the same format — confirmed both via `curl` with cache-busting query
params (ruling out CDN caching) and by loading FFC's own 8-team and 14-team
web pages side by side. `teams` is validated (an unsupported value 400s with
`{"errors":["Invalid teams"]}`) but does not currently change which mock
drafts are pooled — almost certainly because it's very early in the 2026
preseason (the data window is 2026-08-22 to 2026-08-29) and FFC hasn't
accumulated enough per-size mock volume to segment yet.

Building §4's full 8/10/12/14 × format matrix today would have modeled a
distinction that isn't in the data. Instead: `FfcAdpService` fetches the one
cell fantasy(heart) actually needs (14-team PPR, configured in
`weights.yml`'s new `draftsim.adp.ffc` block), and probes one other team size
at ingest time — if `total_drafts` matches, the row is stored with
`derived=true` and a `derivation` string naming exactly this finding, so
nothing downstream can mistake it for genuinely 14-team-specific data. This
self-corrects automatically once FFC's data differentiates by size later in
the preseason; no code change needed, just re-ingest.

**Also found and fixed getting this to actually run:** FFC's API serves valid
JSON with `Content-Type: text/html; charset=utf-8` (confirmed with plain
`curl -D -`, so it's the server, not Spring) — `RestClient.body(Map.class)`
refuses to parse it, so `FfcClient` now fetches as `String` and parses with
Jackson directly, bypassing content-type negotiation. See `claude/lessons.md`.

**§5 (name matching) result on real data:** all 271 FFC players matched
against the internal player table, zero misses, via `PlayerMatcher`'s exact
(name, position) → name-alone-if-unambiguous → DEF-on-team-abbreviation chain.
Seven unit tests in `PlayerMatcherTest` pin the trap list this doc calls out
(diacritics, suffixes, stale team, ambiguous name, DST). The `player_alias`
table from §7 was created but not populated — there was nothing to put in it.

**§9 checklist, run for real against fantasy(heart)'s board:**

- [x] Top 40 read by eye — real, recognizable 2026 PPR first three rounds.
- [x] Zero unmatched rows (0 of 271, not just none under rank 100).
- [x] QB1 sits later: search_rank-only had Josh Allen at pick 3. Blended with
      FFC, he's pick 28 — a large, correct-direction move, not noise.
- [x] Pass-catching WRs sit earlier: Puka Nacua at pick 4.
- [ ] Size sanity (8/10/12/14 monotonic) — **not applicable**, see the finding
      above; nothing to check until FFC itself differentiates by size.
- [x] `picksWithContemporaneousBoard` still 180 after the new snapshots landed.
- [x] First real simulation re-run on the new board: monotonicity still holds
      (0/1368 violations). **Round-1 modal probabilities did not change**
      (still 3.5%–9.4%) — this isolates HANDOFF's calibration finding to
      `weights.yml`'s `adpScale`/`temperature`, not board quality. The board
      was not the cause.

**Not built, explicitly deferred:** the 8/10/12/14 team-size matrix (see
above — not yet meaningful), FantasyPros CSV / `CsvAdpSource`, ESPN, Yahoo,
`stdev`-based per-simulation sampling (the column is populated, nothing reads
it yet), ramping `sleeper_rank` tail-filler weight, sample-size-scaled
(`log(total_drafts)`) blend weighting (a flat configured weight is used
instead — one guess swapped for a simpler one, both labelled as guesses in
`weights.yml`).

---

Plan for **2026-08-30**. Written 2026-08-29.
Companion to `claude/next-steps.md`; supersedes the one-line "Real ADP import" bullet in
`claude/HANDOFF.md` §4.

---

## Read this before planning the day

**fantasy(heart) drafts 2026-08-31 21:15 CDT.** Tomorrow is the last full day before the
only draft this tool exists to help with. That sets the priority order: get one good board
in for *this* league, verify it by eye, run a real simulation. ESPN and Yahoo integrations
are the week after.

The league-size generality below is not extra scope — it is mostly *avoiding* hardcoding
14 in four places tomorrow and paying for it later. Capping the range at 8–14 removes the
only genuinely expensive part: every size in that range is natively published, so nothing
ever has to be extrapolated.

If only one thing gets done: **§3 + §5 (matching) + §9 (verification)**.

## 1. Why this is the highest-value change

From HANDOFF's own headline: there is no true 14-team PPR ADP feed. The board is Sleeper's
`search_rank` — a popularity ordering, not size- or scoring-aware — blended at weight 0.5
with observed pick order from completed drafts. That 0.5 is a coin flip wearing a
parameter's clothes.

Everything downstream reads `BoardEntry`. `valueDelta`, reach fitting, availability curves,
the modal board: all inherit whatever error is in the board. A wrong board produces a
confidently wrong simulation and nothing in the UI says so.

`search_rank` is roughly a 12-team, scoring-agnostic consensus. It diverges from a
14-team PPR board hardest at exactly the positions where the need model has the most
leverage — QB and TE go later in deeper leagues, pass-catching RBs and volume WRs go
earlier in full PPR — and those are the same positions where `runPressure` is supposed to
carry real weight.

## 2. The core design decision: store rank, derive picks

**An ADP pick number is meaningless without its league size.** Pick 36 is the end of round
3 in a 12-team league and the middle of round 4 in an 8-team league, and they are not the
same quality of player. So:

- **Store rank, not pick number, as the canonical value.** `adp_snapshot` gains
  `overall_rank`, `source_teams`, `source_scoring`, `source_drafts` (sample size).
  Keep the raw `adp` too — it's what the source said, and you want it for debugging.
- **Materialize `BoardEntry` per league at read time**, converting rank to a pick number
  for that league's team count and rounds.
- Rank is the only scale every source shares.

Snake conversion, given rank `r` and `N` teams:

```
round = ceil(r / N)
slotInRound = ((r - 1) % N) + 1
slot = (round odd) ? slotInRound : N + 1 - slotInRound
```

**Consequence: the board is a function of a league, not a singleton.** `GET /api/board`
currently takes no league parameter. It needs `?leagueId=...`, with the team count,
rounds, and scoring read from `league.settings_json`. Sleeper gives `total_rosters`,
`roster_positions` and `scoring_settings` — `BoardRepository` already does the
`scoring_json->>'rec'` coalesce, so the plumbing is half there.

Scoring inference from `rec` (thresholds in config, not constants):

```
rec >= 1.0    -> ppr
0.25 – 0.99   -> half-ppr
< 0.25        -> standard
```

### Source interface

```java
interface AdpSource {
    String sourceKey(LeagueShape shape);   // "ffc:ppr:14", not a hardcoded string
    boolean supports(LeagueShape shape);
    AdpFetch fetch(int season, LeagueShape shape) throws IOException;
}

record LeagueShape(int teams, int rounds, Scoring scoring, boolean superflex) {}

record AdpFetch(
    List<RawAdpRow> rows,
    int nativeTeams,        // what the source actually published
    Scoring nativeScoring,
    int sampleDrafts,       // for sample-size-aware blending
    LocalDate asOf,
    boolean derived,        // true if a fallback cell was substituted
    String derivation       // "requested 10, served 12 (thin sample)", null if exact
) {}
```

`derived` and `derivation` ride all the way out to the API so the UI can label a
substituted board as approximate. Within 8–14 this should almost always be `false`; it
exists for the thin-sample case in §3 and as an honest signal if a cell disappears. Per
project convention: an arbitrary modelling choice is declared and configurable, never
presented as principled.

Name resolution lives in exactly one place — `PlayerMatcher` — shared by every source.
That class is where the day actually goes (§5).

## 3. Fantasy Football Calculator — do this one first

The only free source that is natively **team-count and scoring aware**, which is the
entire problem. FFC publishes a free REST API with JSON responses, free for personal and
commercial use, asking only for attribution as a link or mention. They ask that it not be
called frequently since the data refreshes once a day, and it takes parameters for scoring
format, team count, year and position.

```
GET https://fantasyfootballcalculator.com/api/v1/adp/{format}?teams={N}&year=2026&position=all
```

`{format}` is a path segment: `standard` / `ppr` / `half-ppr` / `2qb` / `dynasty` /
`rookie`. Confirmed live for 2026 at 8-team PPR and 8-team half-PPR; 12- and 14-team URLs
appear in FFC's own examples. **The supported range appears to be exactly `{8, 10, 12, 14}`,
which is the reason to cap scope there** — it is the range where the data is real rather
than inferred. Verify all four with a loop tomorrow (§11); 10-team is the one I have not
seen directly.

Two properties worth knowing: the ADP is generated from **live mock drafts**, which answers
the "mocks from other sources" half of the question directly — this *is* aggregated mock
data. And FFC filters out computer selections, counting only human picks, which is exactly
the population you want to model.

**Expected response fields — unverified, confirm with curl before coding against them:**
`status`, `meta` (`type`, `teams`, `rounds`, `total_drafts`, `start_date`, `end_date`),
and `players[]` with `player_id`, `name`, `position`, `team`, `adp`, `adp_formatted`
("1.02"), `times_drafted`, `high`, `low`, `stdev`, `bye`.

### Sample size varies enormously by cell — check `meta.total_drafts` every time

Verified from their own pages: 8-team PPR for 2026 drew 8,104 mocks in a single week,
while 8-team half-PPR over a comparable window drew 2,391, and an 8-team standard defense
page in 2025 was built on 490. Popular cells (12-team PPR, 14-team PPR) are thick; odd
combinations are thin.

So **blend weight must scale with sample size**, not be a fixed per-source constant. A cell
with 300 drafts should not carry the same weight as one with 8,000. Set a `minDrafts` floor
in config below which the cell is discarded and §4's fallback runs instead.

### The underrated field is `stdev`, not `adp`

The engine treats ADP as a point estimate. It isn't. A player who goes anywhere from 20 to
45 and one who always goes 33–35 are different objects, and modelling them identically is
a real source of fake precision in the availability curves. Cheapest first:

- Store `stdev` on `adp_snapshot` (nullable column; needs a V2 migration only if `bootRun`
  has already succeeded once — see the HANDOFF schema note).
- Later: sample each seat's perceived board position as `adp + N(0, stdev)` **once per
  simulation run**, not per pick. That injects correlated per-player uncertainty into the
  Monte Carlo, closer to reality than temperature alone. Gate behind a `weights.yml` flag;
  it's a guess, not a known improvement.

### Fetch lazily

Five formats × four sizes is twenty daily requests if you crawl the cartesian product.
Don't. Fetch only the cells the configured leagues actually need, cache to disk, one fetch
per cell per day.

## 4. Covering 8 through 14

**Every size in this range is natively published, so fetch the right cell and never model
the difference.** That is the whole benefit of capping here: no extrapolation, no
positional trend fitting, no invented coefficients. `LeagueShape.teams` selects a URL.

This matters more than it sounds, because **rank order is not invariant across league
size**, and 8 to 14 is a 75% spread — a wider gap than the 14-to-16 case that got dropped.
As leagues deepen, scarcity rises and scarce positions climb: QB, TE, elite RB, eventually
even DST and K move up relative to rank, because the waiver wire that made them streamable
is gone. Shallow leagues do the reverse, hard. An 8-team board and a 14-team board disagree
sharply about when the QB run starts. Reusing one for the other would be systematically
wrong in a patterned way at exactly QB and TE — which is precisely why the native cell is
worth fetching rather than rescaling.

Resolution order in `AdpSource`, now short:

1. **Exact native cell exists and clears `minDrafts`** → use it. `derived = false`. This is
   the expected path for every league in scope.
2. **Cell is missing or too thin** → nearest published size, `derived = true`, delta
   recorded. Realistically only 10-team standard or an odd format lands here.
3. **Reject sizes outside 8–14** at the API boundary with a clear error, rather than
   quietly serving a 14-team board to a 16-team league. An explicit "not supported" is a
   better product than a silently wrong board.

```yaml
adp:
  sources:
    ffc:          { weight: 0.6, minDrafts: 500 }
    fp_consensus: { weight: 0.3, minDrafts: 0   }
    sleeper_rank: { weight: 0.1, minDrafts: 0   }
  blend:
    strategy: rank            # rank | pick_number
    sampleSizeWeighting: true # scale weight by log(total_drafts)
  leagueSizes:
    supported: [8, 10, 12, 14]  # the sizes FFC publishes natively
    onMissingCell: nearest      # nearest | reject
```

### Board depth scales with league size too

Picks needed is `teams × rounds`: 120 for 8×15, 210 for 14×15. Aggregate feeds thin out in
the tail, so a 14-team league needs most of the published board while an 8-team league
barely touches half of it. Keep `sleeper_rank` in the blend as a **tail filler with a
ramping weight** — near zero at the top, rising past the point where the primary source's
coverage degrades — rather than a flat 0.1 everywhere. A gap at pick 12 is an emergency;
one at pick 200 is fine.

### This exposes an existing bug in the engine

`positionalPrior(round, position)` is fit on ~360 picks drawn from **both a 12-team league
(Ball Knowers) and 14-team leagues (West Coast, fantasy(heart))**. Round 3 is picks 25–36
in one and 29–42 in the other. Mixing them by raw round index quietly biases the priors,
and an 8-team league would make it much worse — round 3 there is picks 17–24.

Fit on **pick fraction** — `pick_no / (teams × rounds)` — or on normalized rank, not round
index. Two more places with the same disease:

- **`runPressure` window.** "Last 5 picks" is over half a round at 8 teams and about a
  third at 14. Make the window proportional: `max(3, round(teams / 2.5))`, in config.
- **K/DEF round gating.** Currently gated by round number. Should be gated by rounds
  remaining, or by fraction of the draft elapsed, or the gate fires in the wrong place at
  every size but the one it was tuned on.

These are small changes and they are much cheaper now than after another season of
profiles is fit against the wrong denominator.

## 5. FantasyPros — the shortcut that may make §6 unnecessary

FantasyPros publishes a consensus ADP aggregating the major hosts, with per-source columns
broken out. Their own ADP page notes that ESPN's numbers appear under PPR scoring and
Yahoo's under half-PPR.

So you can get ESPN and Yahoo **without touching either platform's API**, in one CSV,
already normalised against a single player universe. For a one-user tool the day before a
draft, that is strictly the better trade.

- No free public API. Save the CSV by hand into `data/adp/`, point `CsvAdpSource` at it.
  Ten minutes, no auth, no scraping fragility.
- Write `CsvAdpSource` generically — source key, column mapping, done — so any future
  hand-saved export loads without new code.
- **Caveat that matters here:** their consensus is not team-count specific. It is broadly a
  12-team view, so it enters the blend with `nativeTeams = 12` and gets the §4 treatment
  like anything else. Useful as a cross-check and a disagreement signal, not as the primary
  board for a 14-team league, and further off for an 8-team one.

## 6. ESPN and Yahoo — after the draft, not before

### ESPN
Undocumented but long-stable JSON under the fantasy v3 API. Player payloads carry
`player.ownership.averageDraftPosition`, `player.ownership.averageDraftPositionPercentChange`,
and `player.draftRanksByRankType` with per-format ranks, alongside `fullName`,
`defaultPositionId` and `proTeamId`.

Shape (**unverified — confirm in a browser devtools network tab first**): the seasonal
players endpoint with `view=kona_player_info` and an `X-Fantasy-Filter` JSON header
carrying limit, sort and filter. Public read for public data; private leagues need `SWID`
and `espn_s2` cookies, which you don't need here.

Gotchas: `defaultPositionId` and `proTeamId` are integer enums needing lookup tables; and
ESPN's ADP reflects ESPN's own drafts at ESPN's default size and scoring. It's a belief
signal about casual drafters, not a value estimate, and it has **no team-count parameter at
all** — treat `nativeTeams` as ESPN's default and let §4 handle it.

### Yahoo
Official, documented, highest friction: OAuth2 three-legged with app registration, so a
redirect URI and stored refresh token before a single row arrives. The endpoint is the
player `draft_analysis` collection returning `average_pick`, `average_round` and
`percent_drafted`. XML by default; request JSON.

Given §5 already yields a Yahoo column, the only reason to build this is Yahoo's live
percent-drafted trend during draft week. Real thing to want. Not tomorrow.

## 7. Name matching — where the day will actually go

Assume every source disagrees with Sleeper about names. This eats hours and it silently
corrupts the board if you skip the miss log.

1. **Normalise** — lowercase, strip punctuation and diacritics, strip suffixes (`Jr`, `Sr`,
   `II`, `III`, `IV`), collapse whitespace.
2. **Exact match** on normalised `(name, position)`.
3. **Fall back** to normalised name alone for known-ambiguous cases.
4. **Alias table** for the remainder: `player_alias(source, source_name, player_id)`.
   Hand-fill once; roughly twenty rows and it never grows much.

Traps worth pre-empting:

- **Nicknames.** Marquise / "Hollywood" Brown. Chigoziem / Chig Okonkwo. Sources disagree
  on the canonical form and flip between seasons.
- **Punctuation.** Amon-Ra St. Brown, Ja'Marr Chase, JK / J.K. Dobbins.
- **Suffix drift.** Deebo Samuel Sr. vs Deebo Samuel — appears and disappears by source
  *and* by year.
- **Defenses**, the worst case, structurally different everywhere: Sleeper uses the team
  abbreviation as the player id (`PHI`); ESPN renders "Eagles D/ST" with a numeric position
  id; FFC uses city-and-nickname with position `DEF`. Special-case DST on team abbreviation
  before the general matcher runs.
- **Kickers** are often stale or missing in aggregate feeds. Low stakes — the engine gates
  them late anyway — so let them miss.
- **Team fields disagree.** A source snapshotted before a trade or cut has stale teams.
  **Match on name + position; use team only as a tiebreaker, never as a required key.**
  This is the most common way a matcher loses a first-rounder.
- **Rookies** absent from a source captured too early.

**Log every unmatched row with its source rank, sorted ascending.** An unmatched player at
rank 8 is an emergency; one at rank 190 is noise. Sorted by name the log tells you nothing;
sorted by rank it tells you whether you can proceed.

## 8. Blending

Blend on **rank**, never on raw ADP across sources of different sizes — averaging a 12-team
36 with a 14-team 36 is a category error.

1. Convert each source to overall rank, blend with configured weights, map the blended rank
   back to pick numbers for the *target* league (§2).
2. Scale each source's weight by sample size — `log(total_drafts)` is fine and is a
   defensible default rather than a tuned one.
3. Optionally weight by recency from `captured_on`.

**Keep cross-source disagreement per player.** Where FFC and FantasyPros diverge badly,
that's genuine market uncertainty and a second, independent handle on what `stdev`
measures. Surfacing spread is more honest than a single blended number.

## 9. Verification — do not skip, this is the point

The engine and SQL are verified. None of that says the board is any good.

- [ ] `curl 'localhost:8080/api/board?leagueId=...&limit=40'` and **read the names as a
      fantasy player.** If the top 12 don't look like a real 14-team PPR first round, stop.
- [ ] Zero unmatched rows with source rank < 100.
- [ ] QB1 and TE1 sit **later** than on the `search_rank`-only board. If they didn't, the
      new source isn't actually weighted in.
- [ ] Pass-catching RBs and high-target WRs sit **earlier**. Same check, PPR side.
- [ ] **Size sanity:** request the same league at 8, 10, 12 and 14 teams. QB1 and TE1 must
      move monotonically earlier in rank as team count rises. If they don't, you are
      serving one cell four times — check that `teams` is actually reaching the URL.
- [ ] `picksWithContemporaneousBoard` still ~180 after new snapshots land. New
      `captured_on` dates interact with `maxBoardLagDays`; if this drops toward 0, every
      reach profile silently empties.
- [ ] First real simulation: 2000 iterations, T=1.0. Availability decreasing monotonically
      across your picks, round-1 modal probabilities in the 20–60% band, and the 1.11 menu
      recognisable.

## 10. Operational and legal

- Personal, non-commercial, single user. Cache to disk; one fetch per cell per day.
- **FFC asks for attribution.** A line in the UI footer. It's the condition of the free API.
- ESPN's endpoints are undocumented and can change without notice; Yahoo's token expires.
  Both are **best-effort**: a source that fails to fetch logs a warning and drops out of the
  blend rather than failing the board build.
- Add per-source `lastFetchedAt`, `rowCount`, `sampleDrafts` and `derived` to
  `GET /api/health` next to `weightsLoaded`. Silent staleness is the failure you wouldn't
  otherwise notice.

## 11. Suggested shape of the day

| Block | Work |
|---|---|
| First | `curl` FFC across `{8,10,12,14} × {standard, half-ppr, ppr}`; record which cells exist and their `total_drafts`. Ten minutes, settles §4. |
| Then | `AdpSource` / `LeagueShape` / `FfcAdpSource` / `PlayerMatcher`; miss log sorted by rank |
| Then | Clear the top-100 misses via the alias table |
| Then | Rank blend + rank→pick materialization keyed on league; weights in `weights.yml` |
| **Checkpoint** | §9 — read the top 40 by eye. **Do not proceed past a bad board.** |
| Then | Pick-fraction fix for `positionalPrior`, proportional `runPressure` window (§4) |
| If time | Hand-saved FantasyPros CSV via `CsvAdpSource`; `stdev` column |
| Not today | ESPN endpoint, Yahoo OAuth, corpus enumeration |

## 12. What was not verified in writing this

No network to these domains from the sandbox, so **nothing below was executed**:

- FFC's JSON field names in §3 — from memory and search snippets. The API's existence,
  licence terms, parameter set, mock-draft provenance, human-picks-only filtering, and
  8-team coverage for 2026 *are* confirmed from FFC's own pages. The response shape is not.
- The complete set of supported team counts. 8, 12 and 14 are seen in the wild; **10 is
  assumed and is the one gap in the 8–14 range.** If 10-team turns out not to exist, §4's
  fallback covers it, but you want to know before a 10-team league is configured. A single
  loop of curls settles it and should be the first thing you run.
- ESPN's exact path and `X-Fantasy-Filter` shape. The field names are confirmed from a
  published working example; the request shape is not.
- Yahoo's `draft_analysis` response shape — from memory.

Confirm each with one curl before writing a parser against it. Per the project's own
convention: prefer testing a claim over asserting it, and say plainly which is which.

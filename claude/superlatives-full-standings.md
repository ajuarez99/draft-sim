# Superlatives: click an award, see where everyone finished

**Status: planned 2026-09-29, nothing built.** Asked for by Allan: clicking an award
card should open a modal showing where **every** team ranked, not just the winner.

File:line references were checked against `main` at `04c2043`. Nothing
here has been executed.

> **Amended after review (2026-09-29).** This became spec
> `specs/010-superlatives-full-standings/`. Its `plan.md` records 13 amendments that
> override this doc, and the doc below is left as written. What this doc got wrong:
>
> - **It missed the controller.** `SuperlativesController.superlativeRow` builds each
>   award's JSON by hand, so new record fields would never have reached the page (R1).
> - **It missed orphaned rosters.** "Every team" has to mean every `roster_season` row.
>   The name map this doc implied skips a roster with no manager (R2).
> - **"Absent means 0" for Waiver Wire Warrior and Joel Embiid** could rank non-winners
>   level with, or above, the card's winner. Both kinds now show their empty state when
>   the best value is ≤ 0 (amendment 9). Embiid's unclassified weeks are noted on every
>   row, not just the winner's (amendment 4).
> - **"Formatted the way the card formats it"** only holds for a few kinds. There is now one
>   per-kind formatter (amendment 10).
> - **The "your row" highlight was dropped.** It compared two different Sleeper name fields
>   (amendment 11).
>
> And one correction of a correction. The spec's first plan claimed the four record kinds
> needed a separate, unfiltered query, or rank 1 could disagree with the card. **That claim
> was never run, and it was false.** This doc's "rows already loaded" was right (amendment 1).

## What's there now

- **There's no modal.** Each card (`SuperlativeCard`,
  `web/src/pages/Superlatives.tsx:170`) shows the holder(s), the winning value and, for
  some kinds, a `<details>` "Games" / "Swing weeks" list of the *winner's* rows
  (`DetailList`, `:493`).
- **The API returns only winners.** In `SeasonSuperlativesService.Superlative` (`:161`),
  `holders` is the tied-for-first set and `detail` is their rows. Nothing about 2nd
  through 12th is sent.
- **But most kinds already compute every team and then throw it away.** Each builder
  makes a per-roster map and filters it to the max:
  - `closeGameSuperlative` (`:373-395`): `byRoster` is a count per team.
  - `benchSuperlative` (`:546-592`): `byRoster` is a `BenchAgg` per team.
  - `waiverSuperlative` (`:607-657`): a `RosterTotal` per team.
  - `absenceSuperlative` (`:761-901`): a `RosterCost` per team.
  - `unethicalSuperlative` (`:1078-1132`): `totalByRoster`.
  - `addLuckSuperlatives` (`:457-495`): `ExpectedWinsService.TeamRow` for every team.

  So for these, the standings are a change to what's returned, not new computation.
- **The week- and game-record kinds don't have per-team data.** `HIGHEST_WEEK`,
  `LOWEST_WEEK`, `BIGGEST_BLOWOUT` and `CLOSEST_GAME` take the top **20 league-wide
  rows** from `LeagueRecordService` (`:263-272`). That list can hold one team five times
  and another team zero times. It's the wrong input for "where did everyone rank".

## Design

### Backend: one new field, `standings`

```java
public record Standing(int rank, Holder team, Double value, String note, boolean hasValue) {}
```

This is added to `Superlative` as `List<Standing> standings`, sorted in the award's own
direction.

- **`rank`**: competition ranking, so ties share a rank (1, 2, 2, 4). The rank-1 rows
  must be exactly `holders`. This gets a test.
- **`note`**: the one-line context for that team's figure, such as "week 7" or
  "vs Team X, week 3". It's worded the same way as the winner's line in
  `holderDetailLines`, so the modal and the card read alike.
- **`hasValue = false`** marks a team the award couldn't measure, for example no valid
  bench weeks or no pairings. The team is listed at the bottom with the reason, **not
  as 0**. A missing number shown as zero is the kind of false confidence this repo
  exists to avoid.

What each kind is ranked by. Direction mistakes are recurring bug class #1, so each
direction is written out:

| Kind | Each team's figure | Order |
|---|---|---|
| HIGHEST_WEEK | That team's best single week | high → low |
| LOWEST_WEEK | That team's worst single week | **low → high** |
| BIGGEST_BLOWOUT | That team's biggest winning margin (no wins means `hasValue=false`, "no wins yet") | high → low |
| CLOSEST_GAME | That team's smallest margin in any game, won or lost | **low → high** |
| CLOSE_WINS / CLOSE_LOSSES | Count; teams absent from the map are a real **0**, not missing | high → low |
| LUCKIEST | `winsAboveExpected` | high → low |
| UNLUCKIEST | `winsAboveExpected` | **low → high** |
| MOST_BENCH_POINTS | `BenchAgg.pointsLeft` | high → low |
| WAIVER_WIRE_WARRIOR | `RosterTotal.totalPoints` | high → low |
| JOEL_EMBIID | `RosterCost.totalPointsLost` | high → low |
| UNETHICAL | Games, from the commissioner's list. Unlisted teams are 0. | high → low |
| JABARI_SMITH_JR | **Players, not teams.** See below. | adds, high → low |

The four week and game kinds get their per-team figure from rows that are already
loaded: `parsedWeeks` / `leagueBreakdowns` for week scores, and `games` (the paired list
at `:251`) for margins. That's a group-by over data already in memory, with no new
query and no change to `LeagueRecordService`.

**JABARI_SMITH_JR** is headed by players (`playerHolders`), and the modal for it should
rank players:

- The top 10 most-added players with their add counts.
- `playerStandings` as a separate list, rather than bending `Standing` to hold either a
  team or a player.

This is the one kind where "everybody" means players. Say so in the modal subtitle.

**`early`** and **`coverage`** already exist on `Superlative`. The modal shows both.
An early luck ranking is as much noise at 7th place as at 1st.

### `web/src/api.ts`

Mirror `Standing`, `standings` and `playerStandings` field-for-field in the same change.
This is a hard rule.

### Frontend: `SuperlativeStandingsModal`

- The whole card becomes the trigger: a `<button>` wrapper or `onClick` plus
  `role="button"` and `tabIndex`, with keyboard and Enter support.
  - `<details>` inside the card must stop propagation, or opening "Games" would also
    open the modal.
  - An unavailable card or empty card isn't clickable.
- Follow the existing modal pattern in `components/StartMockModal.tsx`:
  `role="dialog"`, Escape to close, backdrop click to close, focus returned to the card.
- **Layout: a ranked list, not cards.** Each row has rank, avatar, team name, the value
  formatted with the card's own `formatValue`/unit, and the `note` beside it.
  - Winners' rows use the card's hue.
  - The signed-in user's row is highlighted if the page knows it.
  - This matches the "label the axis, spell out the number" rule: one encoding per
    row, and the exact value printed. There's no bar without a number.
- The `hasValue=false` rows go last, in muted type, with their reason.
- Phone width: it's a full-height sheet at 375px, with no horizontal scroll.

## Not building

- **Charts or sparklines in the modal.** A ranked list of 10–12 numbers is already the
  readable form.
- **Per-team drill-down into every game from the modal.** The winner's `DetailList`
  stays where it is. If people want it for everyone, it's a follow-up.
- **Standings for other seasons in the same modal.** That comes from
  `claude/season-scoped-rail-links.md`: switch the year, then open the card.

## Acceptance criteria

1. **Backend unit tests, one per ordering-sensitive kind.** Each asserts **order**, not
   just count:
   - LOWEST_WEEK's rank 1 is the lowest score.
   - UNLUCKIEST's rank 1 has the most negative `winsAboveExpected`.
   - CLOSEST_GAME's rank 1 is the smallest margin.
   - A 2-way tie gives ranks 1, 1, 3.
   - Rank-1 rows equal `holders` for every kind.
   - Every roster appears exactly once.
   - A roster with no measurable data is `hasValue=false`, not 0.
2. **`./gradlew test`**: green, **and** the report checked for the skip count (the
   "suite skips ITs silently" trap). The superlatives IT must actually run against
   Postgres.
3. **`npx tsc -b && npm run build`** is clean, and `Superlatives.test.tsx` covers the
   following:
   - Clicking a card opens the modal with N rows.
   - Escape closes it.
   - Clicking "Games" doesn't open it.
4. **Live, on the 2025 season of both leagues:**
   - Open Highest week, Unluckiest, and Jabari Smith Jr.
   - Check one team's figure against the Weekly report or Expected wins page by hand.
   - Screenshot at desktop width and at 375px.

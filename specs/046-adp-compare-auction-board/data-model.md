# Data model: spec 046

## Migration V39 (`V39__adp_compare_auction.sql`)

Highest on `origin/main` (5512cd7) is V38. Re-check main and in-flight branches before merging; on
2026-10-23 none claimed V39.

```text
adp_snapshot
  + avg_cost         numeric(6,2) null   -- Yahoo average auction price, $200 budget; null for every other source
  + percent_drafted  numeric(5,4) null   -- Yahoo, 0..1; null elsewhere

draft
  + budget           int null            -- settings.budget for auction drafts; null for snake

draft_pick
  + amount           int null            -- metadata.amount for auction picks; null for snake or when Sleeper sent none
```

All nullable, no defaults, no backfill. Existing rows mean "not an auction / not Yahoo".

**Amended after review (2026-10-23, F4/F5)**: V39 also carries:

```text
draft_pick
  + player_name        text null   -- metadata.first_name + ' ' + last_name; names a sale whose player didn't resolve
  + sleeper_player_id  text null   -- raw pick player_id, so a later player ingest can resolve it

adp_unmatched (new)
  id               bigserial primary key
  sport            text not null
  source           text not null
  captured_on      date not null
  name             text not null
  team             text null
  positions        text null
  adp              numeric(6,2) null
  avg_cost         numeric(6,2) null
  percent_drafted  numeric(5,4) null
  index (sport, source, captured_on)
```

`adp_unmatched` is replaced per `(sport, source, captured_on)` in the same transaction as that
day's `adp_snapshot` rows. The E1 sentence "returned in the capture result … logged" below is
superseded: they're stored and shown.

Write semantics (F12): `amount`, `player_name` and `sleeper_player_id` use plain
`= excluded.<col>` on conflict, never `coalesce`. `budget` is written by `ingestDraft` and by the
poller's `updateBudget`. No main-code overload defaults it.

Team budgets (E3, F15) are keyed by `draft_slot` 1..`teams`, not by `manager_id`.

## E1. ADP capture (existing `adp_snapshot`, three new source keys)

| source | adp | avg_cost | percent_drafted | source_scoring | derived |
|---|---|---|---|---|---|
| `sleeper_dynasty` | `stats.adp_dynasty` (<999 only) | null | null | `dynasty` | false |
| `sleeper_redraft` | `stats.adp_std` (<999 only) | null | null | `redraft` | false |
| `yahoo` | `average_pick` | `average_cost` | `percent_drafted` | `redraft` | false |

- Unique `(player_id, source, captured_on)` (existing). Same-day reruns replace the day's rows.
- Unranked = no row. Never written as 999 or 0.
- Yahoo players `PlayerMatcher` can't match aren't stored (there's no `player_id` to key on).
  They're returned in the capture result (`unmatched: [{name, team, pos, adp, cost}]`) and logged.
  The view shows the unmatched count with the capture date (FR-003).

## E2. Auction sale (existing `draft_pick` + `amount`)

- `amount` comes from `metadata.amount` (a string on the wire, parsed to int). Null if absent or
  unparseable.
- A sale whose `player_id` didn't resolve still has a row (existing behaviour: `player_id` null).
  The view names it from the raw Sleeper metadata. **Gap**: `draft_pick` doesn't store the Sleeper
  name, so the plan adds it to the response from the Sleeper player cache, falling back to
  "Unknown player".

## E3. Team budget (derived per request, not stored)

For each seat (`slot_to_manager`), from the draft's picks:

- `spent` = Σ amount (null counts as 0, and is flagged as `priceMissing`)
- `filled` = number of picks
- `spotsLeft` = draft.rounds − filled
- `left` = draft.budget − spent
- `maxBid` = spotsLeft > 0 ? max(0, left − (spotsLeft − 1)) : 0

Rule check: 23 spots, $200, after one $84 buy → left 116, spotsLeft 22, maxBid 95.

## E4. Compare row (derived per request)

`player` fields: id, Sleeper id, name, team, positions, years_exp, rookie (years_exp = 0). Plus
the latest capture of each source:

- `dynasty`
- `redraft`
- `yahooAdp`
- `yahooCost`
- `yahooPctDrafted`
- `sale`, when the league's draft has a pick for the player: {amount, buyerManagerId, pickNo}

The universe is every player with a row in **any** of the three latest captures. The page hides
nothing by default.

## Validation rules

- `budget` and `amount` are ≥ 0 when present. A negative value from Sleeper is stored as null and
  logged.
- A capture that would store fewer than half the previous capture's rows is refused, and the old
  one stays. This is the same hand-set `MIN_KEPT_SHARE_OF_STORED = 0.5` rule as season
  projections: reused, not re-declared.

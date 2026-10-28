# Contract delta: `GET /api/leagues/{sleeperLeagueId}/superlatives` (spec 051)

Additive only. Every existing field and kind is unchanged (FR-014, SC-004).

## `superlatives[].kind`

New value `"TRENDING_TONY"`, always the **last** element of `superlatives[]`.

## `superlatives[]` — new field on every element

```jsonc
"trendingCoverage": null            // every kind except TRENDING_TONY, and TRENDING_TONY when unavailable
"trendingCoverage": {               // TRENDING_TONY when available
  "judgingSinceAt": "2026-09-06T23:17:39.851Z", // earliest JUDGED PICKUP (code review B5); UI shows a local date (F13)
  "judged": 31,
  "notJudged": 58,
  "totalPickups": 89                // judged + notJudged
}
```

## TRENDING_TONY element

```jsonc
{
  "kind": "TRENDING_TONY",
  "available": true,
  "early": false,
  "value": 4,                       // top count; null when nobody named
  "unit": "PICKUPS",
  "holders": [ /* SuperlativeHolder, tied top */ ],
  "emptyReason": null,              // "nobody has chased the trending list twice yet" when top < 2
  "detail": [ /* TRENDING_PICKUP rows for holders only */ ],
  "coverage": null,
  "playerHolders": [],
  "standings": [                    // every roster; value = count; note = "N of M judged pickups" | "no judged pickups"
    { "rank": 1, "team": {...}, "value": 4, "note": "4 of 9 judged pickups", "hasValue": true, "missingReason": null }
  ],
  "playerStandings": [],
  "playerContributions": [          // every counted trending pickup, all rosters; value = trending rank (UI: "#2 trending", never "2 trending pickups" — F6)
    { "rank": 1, "playerId": "1234", "playerName": "…", "position": "SF", "team": {...}, "value": 2, "note": "week 3 · waiver" }
  ],
  "trendingCoverage": { ... }
}
```

## Detail row `type: "TRENDING_PICKUP"`

```jsonc
{
  "type": "TRENDING_PICKUP",
  "playerId": "1234", "playerName": "…", "position": "SF",   // position nullable
  "rosterId": 3, "week": 3,
  "addType": "WAIVER",               // "WAIVER" | "FREE_AGENT"
  "trendingRank": 2,                 // 1..15
  "addCount": 48211,                 // platform-wide adds in that snapshot's window
  "snapshotAt": "2026-10-29T11:00:04Z"
}
```

Unavailable reasons (amended after review, F2): "Trending data comes from Sleeper; this league is on ESPN." ·
"Transactions for this season haven't loaded yet." · "Trending history wasn't being recorded yet for this season." ·
"Nothing could be judged yet: trending history began on <YYYY-MM-DD> (UTC), and no pickup since has a list within
a day of it." (amended after code review, B1/A5). `detail` holds at most 3 TRENDING_PICKUP rows per holder (B6).

`web/src/api.ts`: `SuperlativeKind` union, `unit` union gains `'PICKUPS'` (F7), `Superlative.trendingCoverage`, a `TrendingCoverage` type and the
`TRENDING_PICKUP` member of the detail union — all in the same change as the Java records (constitution rule 2).

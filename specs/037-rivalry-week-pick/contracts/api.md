# Contract: spec 037 additions

## C1 — `GET /api/leagues/{sleeperLeagueId}/rivalries` (existing, additive change)

These stay the same:
- the route
- league access scoping: anonymous and stranger callers get 404, as spec 034 verified
- every existing field

There's one new nullable field, `recommendation.week`:

```json
"recommendation": {
  "status": "OK",
  "minMeetings": 3, "minMeetingsLabel": "hand-set",
  "pairings": [ /* unchanged: spec 034's history-best set */ ],
  "unpaired": null, "thinCount": 2,
  "note": "This is a suggestion for a rivalry week; it doesn't change your Sleeper schedule.",
  "week": {
    "status": "OK",
    "cap": 2,
    "capLabel": "league rule",
    "recommended": {
      "week": 9,
      "total": 31.5,
      "bestTotal": 34.0,
      "reason": {
        "code": "BEST_UNDER_CAP",
        "text": "1 of the best pairings would be a third meeting in week 9, so it's swapped out. This set scores 31.5 of a possible 34.0.",
        "numbers": { "heldBack": 1, "total": 31.5, "bestTotal": 34.0, "tiedWeeks": 1 }
      },
      "pairings": [
        { "pairing": { /* existing RivalryPairing */ }, "alreadyScheduled": false }
      ],
      "unpaired": null,
      "heldBack":  [ { "aManagerId": 32, "bManagerId": 49, "meetsInWeeks": [3, 11] } ],
      "displaced": [ { "aManagerId": 32, "bManagerId": 51, "stillMeetsInWeeks": [16] } ]
    },
    "others": [ /* WeekOption, same shape, ordered total desc then week asc */ ],
    "unfitWeeks": [ 18 ],
    "overCapNote": null,
    "note": "This is a suggestion for a rivalry week; it doesn't change your Sleeper schedule."
  }
}
```

Rules:
- `week` is `null` whenever `recommendation.status != "OK"`, covering both NO_HISTORY and the
  config/size refusals. Nothing new is shown without history.
- `week.recommended` is non-null iff `week.status == "OK"`. With any other status, the
  client shows spec 034's `pairings` under a message for that status (FR-010).
- An `ALL_BEST_FIT` reason, when `tiedWeeks > 1`, says it's the earliest of `tiedWeeks` weeks
  where every best pairing fits (research R7).
- `displaced[].stillMeetsInWeeks: []` means that pair has no other regular-season meeting.
  The client says so in words.
- Every number in `reason.text` is in `reason.numbers` (spec 034 convention).
- `api.ts` gets `RivalryWeekRecommendation`, `RivalryWeekOption`, `RivalryWeekPairing`,
  `RivalryHeldBack`, `RivalryDisplaced` and the status union, in the same change as the Java
  records (constitution).

## C2 — `config/weights.yml`

```yaml
  rivalry:
    min-meetings: 3
    playoff-bonus: 1.5
    championship-bonus: 2.5
    max-managers: 16
    max-regular-season-meetings: 2   # LEAGUE RULE (Allan, 2026-10-15), not fitted. Range 1-5.
```

If it's missing, `RivalryProperties.loaded()` is false and the whole recommendation answers
`CONFIG_MISSING`, the same as any partial block (spec 034 F17).

## C3 — UI (`RivalryWeekPanel`)

- **`week.status == "OK"`:**
  - Headline: "Recommended: Week N", with the reason sentence.
  - The rule line: "Nobody plays the same manager more than {cap} times in the regular
    season (league rule)."
  - The week's pairings, rendered as today. An "already on the schedule" tag goes on
    `alreadyScheduled`.
  - Held-back pairs in one sentence each, naming the weeks they already meet.
  - A "What this replaces" disclosure listing displaced matchups. Each shows "still meet in
    week X" or "they wouldn't meet otherwise this season".
  - An "Other weeks" disclosure. Each week shows its total against the best, its held-back
    count, and an expandable list of its own pairings.
  - Weeks in `unfitWeeks` are named once: "No full set fits in week(s) …".
- **Any other `week.status`:** one sentence for that status, then spec 034's history-best
  pairings under the heading "Best pairings from history".
- `rec.note` still renders (FR-011).

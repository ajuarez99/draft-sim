# Data model: spec 050

No persisted data. Two in-code shapes.

## LandingCopy (`web/src/landingCopy.ts`)

| Field | Type | Rule |
|---|---|---|
| `siteOrigin` | string | `https://www.ballknowers.co`; no trailing slash |
| `title` | string | contains "fantasy football", "basketball", "Sleeper"; no banned words (R6) |
| `description` | string | ≤ 160 chars; names both sports |
| `headline` | string | the `h1`; no banned words |
| `subheadline` | string | one sentence |
| `sports` | `('NFL' \| 'NBA')[]` | both present |
| `platformsNote` | string | must say sign-in is Sleeper; ESPN only as "some season pages also work for ESPN leagues" (FR-009 as amended) |
| `groups` | `LandingGroup[]` | exactly the four non-home `DESTINATION_GROUPS` keys, in rail order |
| `imageAlt` | string | alt text for the preview image |

`LandingGroup`: `{ key: DestinationGroup; features: LandingFeature[] }` — heading is read
from `DESTINATION_GROUPS`, never retyped.

`LandingFeature`: `{ name: string; line: string; destination: DestinationKey | 'managers' }`.
Sports are not stored: `featureSports(f)` reads the destination's own `sports` (`['nfl','nba']` for
`'managers'`). Mock drafts use the existing `'mock'` key. *Amended 2026-10-26 after plan-review A2/A7
and analyze F1; the first version had a `'mockDrafts'` key.*
Validation (test): every `destination` exists in `DESTINATIONS` or is a global rail entry;
`line` ≤ 110 chars; no banned words.

## SharePreview (derived, `web/src/landingHtml.ts`)

Pure function `headTags(copy) → string` producing the tag list in plan D2, and
`staticBody(copy) → string` and `jsonLd(copy) → string`. All text HTML-escaped.
Image constants: `/og-image.png`, 1200×630, `image/png`.

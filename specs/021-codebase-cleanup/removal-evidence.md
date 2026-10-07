# Removal evidence (spec 021, contract C1)

## T007: verify harness, route, PNGs, `.verify-*` CSS

Grep run on 2026-10-07 over `web/src backend/src scripts claude/scripts .github README.md DEPLOY.md HANDOFF.md`,
before anything was deleted:

| Pattern | Hits outside the items being removed | Verdict |
|---|---|---|
| `PowerRankings.verify` / `PowerRankingsVerify` | `App.tsx:22` (import) and `:149` (route), both removed in the same change. The other two are a comment in the new `PowerRankings.builders.test.ts` and a section comment at `styles.css:3138` (removed with the rules) | remove |
| `power/verify` | `destinations.test.ts:108-109` and `railLeague.test.ts:31` use the path as a **string input** to pure path parsers. They don't route to the page, and they still test the "deeper path under a league page" case. **Kept**; only the description in `destinations.test.ts` was reworded | remove the route; keep the tests |
| `pr-reference` / `2a-front-page` / `2b-ladder` | only `PowerRankings.verify.tsx:303` | remove |
| `verify-` | only `PowerRankings.verify.tsx` (className usages) and `styles.css:3141-3158` (12 rules) | remove |
| operator docs (README/DEPLOY/HANDOFF) | 0 hits for any pattern | — |

The design docs under `specs/` and `claude/` that mention the harness are history, and
were left as they are, following the repo convention.

**Builders (FR-003).** `ballotBlockState`, `buildDeck`, `recordLabel` and
`roomTakeSentence` had no importer apart from the harness and no test.
`web/src/pages/PowerRankings.builders.test.ts` (9 tests) pins their current outputs.
It passed on the code **before** the harness was deleted. The four stay exported
because the test imports them (T011).

**Production, before the change (measured 2026-10-07):**
`GET https://www.ballknowers.co/pr-reference/2a-front-page-desktop.png` →
`200 image/png 626914B`. The production JS bundle has 0 matches for the harness
markers, so the route itself never shipped.

## T016: production, after deploy

*Pending. This is blocked by the merge freeze through the 2026-10-10 NBA draft.*

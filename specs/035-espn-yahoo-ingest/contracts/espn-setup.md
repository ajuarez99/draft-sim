# Contract: ESPN setup endpoints

All routes sit under `/api/setup/espn` and use the same membership/identity gate as the
existing `/api/setup/*` routes (`X-Sleeper-User` required). Every response map is built
mutably, because nullable fields are present.

## POST /api/setup/espn/league

Request:
```json
{ "link": "https://fantasy.espn.com/basketball/league?leagueId=12345&seasonId=2026",
  "leagueId": null, "sport": null, "season": null,
  "credential": { "espnS2": "…", "swid": "{…}" } }
```
- Send either `link`, or `leagueId` + `sport` (`nfl`|`nba`) + `season`.
- `credential` is optional. If it's present, it's stored encrypted for this ESPN league after
  a successful add (R13).
- If it's absent and the league already has a stored credential, the stored one is used.

200:
```json
{ "leagueKey": "espn-fba-12345-2026", "platform": "ESPN", "sport": "nba", "name": "…",
  "seasons": [ { "season": 2026, "leagueKey": "espn-fba-12345-2026", "status": "in_season" },
               { "season": 2025, "leagueKey": "espn-fba-12345-2025", "status": "complete" } ],
  "missingSeasons": [ { "season": 2016, "reason": "ESPN_NOT_FOUND" } ],
  "teams": [ { "rosterId": 3, "name": "…", "managerName": "…", "claimedByYou": false, "claimedByOther": false } ],
  "players": { "matched": 182, "unmatched": 2, "ambiguous": 1, "espnOnly": 0 },
  "unavailable": { "draftRoom": "…", "stats": "SCORING_UNTRANSLATED" },
  "credentialStored": true }
```
`credentialStored` is the only credential-related field ever returned. It's a boolean.

Errors (body `{ "error": CODE, "message": human text }`; the text never contains a cookie):

| Status | Code | When |
|--------|------|------|
| 400 | `MALFORMED_REF` | the link/id doesn't parse |
| 404 | `ESPN_NOT_FOUND` | ESPN 404 |
| 403 | `ESPN_PRIVATE_NO_ACCESS` | ESPN 401 and no credential sent or stored |
| 403 | `ESPN_CREDENTIAL_REJECTED` | ESPN 401 with a credential |
| 422 | `UNSUPPORTED_SCORING` | e.g. `H2H_CATEGORY`; the message names the type |
| 503 | `CREDENTIALS_NOT_CONFIGURED` | a credential was sent but the server has no `BK_CREDENTIAL_KEY` |
| 502 | `ESPN_SHAPE_CHANGED` | a required field is missing; the message names the field |
| 502 | `ESPN_HTTP_<code>` | anything else |

On any error, nothing is written (FR-005).

## POST /api/setup/espn/league/{leagueKey}/claim
Body `{ "rosterId": 3 }` → 200 `{ "leagueKey", "rosterId", "managerId" }`.
- 409 `ALREADY_CLAIMED`: someone else holds the claim.
- 404: no such roster.
- If the same user re-claims a different team, the claim moves.

## PUT /api/setup/espn/league/{leagueKey}/credential
Body `{ "espnS2", "swid" }`, bound to `EspnCredential` (redacted `toString`), not to a record of two
Strings: Spring logs a request body's `toString` at DEBUG (found by `CredentialLeakIT`, lessons #44). The
server test-reads the league with the new cookies before storing them. Returns 204, or the error codes
above. On success it also forgets a recorded `ESPN_ACCESS_DENIED` refusal so the next visit retries without
waiting out the failure back-off; any other recorded failure is left alone.
- Only the credential's `owner_manager_id` may replace it; anyone else gets 403 `NOT_OWNER`.

## DELETE /api/setup/espn/league/{leagueKey}/credential
Returns 204. Same owner rule. League data is kept; future refreshes of a private league then
fail with `ESPN_PRIVATE_NO_ACCESS`, which is shown on the league.

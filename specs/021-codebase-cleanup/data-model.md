# Data Model: Codebase Cleanup (spec 021)

There are no database entities. This feature changes no schema (FR-009). The
"entities" below are the records the work itself keeps, plus the conventions the new
response records follow.

## RemovalCandidate (recorded in each P1 PR description)

| Field | Meaning |
|---|---|
| `path` | A file, route, branch or worktree |
| `kind` | `file` · `route` · `asset` · `branch` · `worktree` |
| `evidence` | The command(s) run and their output, per contract C1 |
| `verdict` | `remove` · `keep` · `confirm-with-owner` |
| `confirmedBy` | Required when `kind` ∈ {branch, worktree}; the owner's chat confirmation |

State transitions: `candidate → evidenced → (removed | kept)`. Nothing goes straight
from `candidate` to `removed`.

## RefactorTarget

| Field | Meaning |
|---|---|
| `target` | A file, or a rule (e.g. `ordinal`) |
| `measuredSize` | Line count or copy count at `7b7238e` |
| `seam` | What it is split or merged along |
| `equivalenceCheck` | The C2 or C3 artifact that proves no behavior change |

## Response record conventions (P2b)

- There is one `dto/<Controller>Responses.java` per controller, holding nested
  `record`s. They are not shared across controllers, so that one controller's change
  cannot move another's JSON.
- A component is named exactly after the old map key (Jackson uses component names).
- Nullability comes from the old code, per research R3. Always-put keys stay plain.
  Conditionally-put keys get `@JsonInclude(NON_NULL)` on that component only, never
  at class level, so a deliberate always-present null is never dropped by accident.
- Boxed or primitive types match the value the map held: `Integer` stays `Integer`,
  `double` stays `double`.
- Each record is mirrored by an `api.ts` type in the same commit (AGENTS.md). If the
  `api.ts` type already matched, the commit message says so explicitly.

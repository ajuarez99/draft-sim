# Interface Contracts

**Branch**: `004-ffwrapped-feature-parity` | **Date**: 2026-09-18

Three contracts carry this feature. Two are HTTP; one is an internal Java interface, and it is the one
that matters most, because it is where basketball is currently locked out.

| Contract | Kind | Story |
|---|---|---|
| [`sport-rules.md`](sport-rules.md) | Java interface (`SportRules`) | US1 — gates US2, US5 |
| [`league-analytics-api.md`](league-analytics-api.md) | HTTP, read-only | US2, US3, US4, US5, US6 |
| [`destinations.md`](destinations.md) | Frontend route declaration | all new pages |

## Conventions shared by every endpoint here

Taken from the existing `/api/leagues/{sleeperId}/...` family, not invented for this feature:

- **Read-only and snapshot-respecting.** Nothing is computed on a page load that is backed by a stored
  snapshot. Figures derived from the playoff simulation are served from storage or not served at all.
- **Refusal is a valid answer, and is explicit.** A league that cannot be answered for — too few scored
  weeks, an unmodelled seeding scheme, a sport without a projection source — returns a body saying so,
  never a plausible-looking zero. Callers must be able to distinguish "0.0" from "no answer".
- **Sport is never defaulted.** An endpoint that only applies to one sport says which, explicitly.
- **`sleeperId` is the Sleeper league id**, matching every existing league-scoped route.

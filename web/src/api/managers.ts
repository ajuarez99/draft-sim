import type { Provenance, Sport } from './draft'
import { apiFetch, json } from './http'

export type ManualTendencies = {
  reachBias: number | null
  unpredictability: number | null
  note: string | null
}

// Mirrors ManagerController.describe()'s response shape (api/ManagerController.java:63-82).
// Only field the tendencies UI actually needs is `stated`, but keep the type honest/complete
// per this file's own convention of mirroring the backend record shape exactly.
export type ManagerSummary = {
  managerId: number
  manager: string
  avatarId: string | null
  provenance: Provenance
  effectiveReachBias: number
  // The unshrunk average of this manager's own scoreable picks -- "what they
  // normally pick," independent of anything stated about them. Null with no
  // scoreable picks. Compare against stated.reachBias; do not confuse with
  // effectiveReachBias, which is already blended with any stated value.
  empiricalReachBias: number | null
  // Reach measured against the other managers in the same draft room, not the
  // market board (audit 11: the board runs several picks off for every room).
  // Positive = earlier than the room. Null with no scoreable picks.
  relativeReachBias: number | null
  // Standard error of relativeReachBias; null with fewer than 2 scoreable picks.
  relativeReachStdErr: number | null
  unpredictability: number
  positionalTilt: Record<string, number>
  note: string | null
  draftsObserved: number
  picksScored: number
  stated: ManualTendencies
}

// All three manager endpoints take `sport` and default it to nfl backend-side
// (ManagerController.java), and all three used to be called from here without
// one -- so /managers listed only football managers, and a basketball seat's
// popover read and then WROTE that manager's *football* stated tendencies.
// Ten of the twelve Ball Knowers managers are the same Sleeper user id in both
// leagues (multi-sport-and-rebrand.md), so that was not a rare edge: manual
// tendencies are stored per (manager, sport), and editing a note on an NBA
// seat would land on the NFL row. Required parameter, no default -- the
// default is what hid this.
export const getManagers = (sport: Sport) =>
  apiFetch(`/api/managers?sport=${sport}`).then(json<ManagerSummary[]>)

// Only `note` is writable. The server answers 400 to a body carrying a non-null
// reachBias or unpredictability (claude/audit-2026-09-28/02), and a note is
// private to the caller (V25): the `note` and `stated.note` fields on
// ManagerSummary are always the signed-in user's own.
export const setTendencies = (managerId: number, sport: Sport, body: { note: string | null }) =>
  apiFetch(`/api/managers/${managerId}/tendencies?sport=${sport}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(json<unknown>)

export const clearTendencies = (managerId: number, sport: Sport) =>
  apiFetch(`/api/managers/${managerId}/tendencies?sport=${sport}`, { method: 'DELETE' }).then(
    json<unknown>,
  )

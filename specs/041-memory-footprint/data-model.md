# Data Model: Memory Footprint (spec 041)

There is no persisted data. Nothing goes into the database and there are no API records.
The entities are documentation artefacts and in-process structures.

## Memory holder
| Field | Meaning |
|---|---|
| id | H1–H8 (spec Context table) |
| location | file:line of the declaration |
| limit | `explicit cap N` / `natural bound: <what>` / `transient` |
| size | number + `measured` / `estimated (why)` |
| decision | `change (D#)` / `leave alone (below threshold)` |

Rule: the decision is `change` only if the measured size ≥ the FR-004 threshold (R7).

## Baseline reading (C3)
`(date, git sha, image flags, profile, docker-stats MB, heap-used-after-GC MB, heap-committed MB, NMT total MB)`.
Profile ∈ {`rest`, `warm`, `load-peak`, `load-after-60s`, `load-after-10min`, `ingest-peak`}.

## Monte Carlo accumulator (D3, in-process)
- `counts: List<Map<Long,Integer>>`, indexed by pick number, 0..total
- `survived: Map<Long, Map<Integer,Integer>>`, player → myPick → count
- `bestAvailCounts: Map<Integer, Map<Long,Integer>>`, myPick → player → count
- Invariant: after `add` has been called for runs 0..k in index order, the state equals today's
  `aggregate` loops run over `runs[0..k]`.
- Lifetime: one `run` call. It is discarded on cancellation.

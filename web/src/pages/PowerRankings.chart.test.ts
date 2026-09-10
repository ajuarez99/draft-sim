import { describe, expect, it } from 'vitest'
import { segmentsOf, type SeriesPoint } from './PowerRankings'

// claude/power-rankings-ballots.md AC12. segmentsOf decides what the bump chart
// is allowed to draw, and both of its rules are the kind that look right until
// a mode with gaps arrives -- which is exactly what MEMBER is.

function pt(week: number, rank: number, thin = false): SeriesPoint {
  return { week, rank, score: null, note: null, ballotCount: thin ? 1 : null, thin }
}

describe('segmentsOf', () => {
  it('splits on a week gap and does NOT bridge it', () => {
    const segments = segmentsOf([pt(2, 1), pt(5, 3)])
    expect(segments).toHaveLength(2)
    expect(segments[0].points.map((p) => p.week)).toEqual([2])
    expect(segments[1].points.map((p) => p.week)).toEqual([5])
  })

  it('keeps a contiguous run in one segment', () => {
    const segments = segmentsOf([pt(1, 1), pt(2, 2), pt(3, 2)])
    expect(segments).toHaveLength(1)
    expect(segments[0].points).toHaveLength(3)
  })

  it('splits on a coverage change, and the THIN side carries the boundary', () => {
    // Weeks 3 and 4 both have data, so 3->4 must still be drawn -- and it is
    // drawn dotted, because the lower-confidence side owns a boundary link. An
    // undrawn gap here would claim week 4 is missing; a solid one would claim
    // the step into thin coverage was as well-supported as the weeks before it.
    const segments = segmentsOf([pt(1, 1), pt(2, 1), pt(3, 1), pt(4, 2, true), pt(5, 2, true)])
    expect(segments).toHaveLength(2)
    expect(segments[0].thin).toBe(false)
    expect(segments[1].thin).toBe(true)
    expect(segments[0].points.map((p) => p.week)).toEqual([1, 2, 3])
    // Week 3 is duplicated into the thin segment, so no visual hole opens.
    expect(segments[1].points.map((p) => p.week)).toEqual([3, 4, 5])
  })

  it('marks a lone point so it is not drawn as an invisible polyline', () => {
    const segments = segmentsOf([pt(4, 1)])
    expect(segments).toHaveLength(1)
    expect(segments[0].points).toHaveLength(1)
  })

  it('is empty for no points', () => {
    expect(segmentsOf([])).toEqual([])
  })
})

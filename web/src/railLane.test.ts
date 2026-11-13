import { describe, expect, it } from 'vitest'
import { laneOverflow } from './railLane'

/*
 * The edge-fade helper for a horizontally scrolling strip (now the league header's sub-tab strip on
 * a phone; it was the rail's page lane). Moved here from RailPhoneNav.test.tsx.
 */
describe('laneOverflow (the edge-fade helper)', () => {
  it('shows no fade when everything fits', () => {
    expect(laneOverflow(0, 347, 347)).toEqual({ start: false, end: false })
  })
  it('fades only the end at the start of a long lane', () => {
    expect(laneOverflow(0, 347, 1249)).toEqual({ start: false, end: true })
  })
  it('fades both edges mid-lane', () => {
    expect(laneOverflow(400, 347, 1249)).toEqual({ start: true, end: true })
  })
  it('fades only the start at the end of the lane, tolerating a fractional scrollLeft', () => {
    expect(laneOverflow(901.6, 347, 1249)).toEqual({ start: true, end: false })
  })
})

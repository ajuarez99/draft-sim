import { describe, expect, it } from 'vitest'
import { NFL_STAT_ORDER, perGameText, sportOf } from './historicalCopy'

/** Spec 056 review N3: the web's football stat order is the backend's documented 13, in the same order. */
describe('football stat order', () => {
  it('is the backend order (StatOrder tail = NflHistoricalSport.COUNTING = application.yml scoring)', () => {
    expect(NFL_STAT_ORDER).toEqual([
      'pass_yd', 'pass_td', 'pass_int', 'rush_yd', 'rush_td', 'rec', 'rec_yd', 'rec_td',
      'fum_lost', 'pass_2pt', 'rush_2pt', 'rec_2pt', 'st_td',
    ])
  })
  it('orders a card line by it, whatever order the keys arrive in', () => {
    expect(perGameText({ rec_td: 1, fum_lost: 2, pass_yd: 3, st_td: 4 }, 'NFL')).toBe('3 pass yd 1 rec TD 2 fum lost 4 ret TD')
  })
  it('sportOf reads a missing sport as basketball', () => {
    expect(sportOf({})).toBe('NBA')
    expect(sportOf({ sport: 'NFL' })).toBe('NFL')
  })
})

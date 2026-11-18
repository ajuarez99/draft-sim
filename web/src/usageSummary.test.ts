import { describe, expect, it } from 'vitest'
import type { UsageFunnelRow } from './api'
import { dayCounts, daysBetween, finishRate, funnelTotals, routeLabel, sharesBySport, sinceFor } from './usageSummary'

function row(over: Partial<UsageFunnelRow> = {}): UsageFunnelRow {
  return {
    day: '2026-11-10', sport: 'nba', started: 0, finished: 0, abandoned: 0, drafting: 0,
    guestStarted: 0, guestFinished: 0, unknownStarted: 0, dailyStarted: 0, dailyFinished: 0, ownerStarted: 0, ownerFinished: 0, ownerAbandoned: 0, ownerDrafting: 0, ownerDailyStarted: 0, ownerDailyFinished: 0,
    ...over,
  }
}

describe('funnelTotals', () => {
  const rows = [
    row({
      started: 10, finished: 6, abandoned: 2, drafting: 2, guestStarted: 4, guestFinished: 2, unknownStarted: 1,
      dailyStarted: 3, dailyFinished: 2,
      ownerStarted: 3, ownerFinished: 3, ownerDailyStarted: 2, ownerDailyFinished: 2,
    }),
    row({
      day: '2026-11-11', started: 5, finished: 1, abandoned: 1, drafting: 3, guestStarted: 2, guestFinished: 0,
      ownerStarted: 1, ownerFinished: 0, ownerAbandoned: 0, ownerDrafting: 1,
    }),
  ]

  it('leaves the owner out of the headline when the server knows who he is, and says how many', () => {
    const t = funnelTotals(rows, true)
    expect(t.started).toBe(11) // 15 - 4
    expect(t.finished).toBe(4) // 7 - 3
    expect(t.notFinished).toBe(7)
    expect(t.ownerStarted).toBe(4)
    expect(t.ownerFinished).toBe(3)
    expect(t.ownerDrafting).toBe(1)
    expect(t.signedInStarted).toBe(15 - 6 - 1 - 4) // all - guest - not recorded - owner
    expect(t.ownerLeftOut).toBe(true)
  })

  it('leaves the owner out of EVERY number: gave up, left open and game of the day too', () => {
    const t = funnelTotals(rows, true)
    expect(t.abandoned).toBe(3) // 2 + 1, the owner abandoned none
    expect(t.drafting).toBe(4) // 2 + 3 - the owner's 1 left open
    expect(t.dailyStarted).toBe(1) // 3 - 2
    expect(t.dailyFinished).toBe(0) // 2 - 2
    expect(t.notFinished).toBe(t.abandoned + t.drafting) // the table still adds up
  })

  it('the reviewer scenario: the owner and one guest both play the game of the day', () => {
    const daily = row({
      started: 2, finished: 2, dailyStarted: 2, dailyFinished: 2, guestStarted: 1, guestFinished: 1,
      ownerStarted: 1, ownerFinished: 1, ownerDailyStarted: 1, ownerDailyFinished: 1,
    })
    const t = funnelTotals([daily], true)
    expect([t.started, t.finished]).toEqual([1, 1])
    expect([t.dailyStarted, t.dailyFinished]).toEqual([1, 1])
    expect(dayCounts(daily, true)).toEqual({ started: 1, finished: 1, notFinished: 0 })
    // and with no owner configured the same row reads in full
    expect(funnelTotals([daily], false).dailyStarted).toBe(2)
    expect(dayCounts(daily, false).started).toBe(2)
  })

  it('a game the owner started as a guest and claimed stays a guest game and nothing goes negative', () => {
    // the server counts it in guestStarted (started_as_guest) and NOT in ownerStarted
    const claimed = row({ started: 1, finished: 1, guestStarted: 1, guestFinished: 1 })
    const t = funnelTotals([claimed], true)
    expect(t.started).toBe(1)
    expect(t.guestStarted).toBe(1)
    expect(t.ownerStarted).toBe(0)
    expect(t.signedInStarted).toBe(0)
    for (const [k, v] of Object.entries(t)) if (typeof v === 'number') expect(v, k).toBeGreaterThanOrEqual(0)
    expect(dayCounts(claimed, true).notFinished).toBeGreaterThanOrEqual(0)
  })

  it('counts everything when no owner is configured, even if a row carries owner numbers', () => {
    const t = funnelTotals(rows, false)
    expect(t.started).toBe(15)
    expect(t.finished).toBe(7)
    expect(t.notFinished).toBe(8)
    expect(t.ownerStarted).toBe(0)
    expect(t.ownerLeftOut).toBe(false)
    expect(t.abandoned).toBe(3)
    expect(t.drafting).toBe(5)
    expect(t.dailyStarted).toBe(3)
  })

  it('not finished is abandoned plus left open when there is no owner', () => {
    const t = funnelTotals([row({ started: 9, finished: 4, abandoned: 2, drafting: 3 })], false)
    expect(t.notFinished).toBe(t.abandoned + t.drafting)
  })

  it('an empty range is all zeros, and its rate is null rather than 0%', () => {
    const t = funnelTotals([], true)
    expect(t.started).toBe(0)
    expect(finishRate(t)).toBeNull()
  })

  it('the rate is finished over started, whole percent', () => {
    expect(finishRate({ started: 11, finished: 4 })).toBe(36)
  })
})

describe('sinceFor', () => {
  it("is that site's own first day, or null when the site has none", () => {
    const r = { countingSince: [{ site: 'timemachine' as const, since: '2026-11-10' }, { site: 'leagues' as const, since: '2026-11-15' }] }
    expect(sinceFor(r, 'timemachine')).toBe('2026-11-10')
    expect(sinceFor(r, 'leagues')).toBe('2026-11-15')
    expect(sinceFor(r, 'combined')).toBeNull()
  })
})

describe('sharesBySport', () => {
  it('adds up result-shared per sport and ignores every other event', () => {
    const e = (name: string, props: Record<string, string>, count: number) => ({ site: 'timemachine' as const, name, props, count })
    expect(
      sharesBySport([
        e('result-shared', { sport: 'nba', who: 'guest', daily: 'no' }, 3),
        e('result-shared', { sport: 'nba', who: 'member', daily: 'yes' }, 2),
        e('result-shared', { sport: 'nfl', who: 'guest', daily: 'no' }, 1),
        e('sign-in', {}, 40),
      ]),
    ).toEqual({ nba: 5, nfl: 1 })
  })
})

describe('daysBetween', () => {
  it('is inclusive, oldest first, and ignores DST', () => {
    expect(daysBetween('2026-11-01', '2026-11-03')).toEqual(['2026-11-01', '2026-11-02', '2026-11-03'])
    expect(daysBetween('2026-03-07', '2026-03-09')).toEqual(['2026-03-07', '2026-03-08', '2026-03-09'])
    expect(daysBetween('2026-11-05', '2026-11-05')).toEqual(['2026-11-05'])
  })
})

describe('routeLabel', () => {
  it('uses the table label, the landing form, and falls back to the template itself', () => {
    expect(routeLabel('leagues', '/leagues/:league/power')).toBe('League power rankings')
    expect(routeLabel('leagues', '/(landing)')).toBe('Landing')
    expect(routeLabel('leagues', '/(landing)/managers')).toBe('Landing (from Managers)')
    expect(routeLabel('leagues', '/(not-found)')).toBe('Not found')
    expect(routeLabel('leagues', '/retired/page')).toBe('/retired/page')
    expect(routeLabel('leagues', '/(landing)/retired/page')).toBe('/(landing)/retired/page')
  })
})

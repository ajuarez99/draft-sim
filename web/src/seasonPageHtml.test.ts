import { describe, expect, it } from 'vitest'
import type { RedraftSeasonData, SeasonPage } from './api'
import fixtureJson from './__fixtures__/redraft-seasons.json'
import { LEAGUE_WORDS } from './leagueWords'
import { appShellPage, indexablePages, notFoundPage } from './redraftPages'
import { dailyBody, homeBody, notFoundBody, seasonBody, sportIndexBody, staticBodyFor } from './seasonPageHtml'
import { makeConfig } from './site'

// Spec 074 T023: the static bodies, read as a crawler reads them (no scripts run).
const data = fixtureJson as unknown as RedraftSeasonData
const cfg = makeConfig({ VITE_SITE: 'timemachine' })
const nba = (slug: string) => data.NBA.pages.find((p) => p.slug === slug)!
const doc = (html: string) => new DOMParser().parseFromString(html, 'text/html')
const text = (html: string) => (doc(html).body.textContent ?? '').replace(/\s+/g, ' ')
const names = (p: SeasonPage) => [
  ...p.topOverall, ...p.busts, ...p.steals, ...p.dreamRoster, ...Object.values(p.topByPosition).flat(),
].map((x) => x.name)

describe('season page body', () => {
  const p = nba('2016-17')
  const html = seasonBody(cfg, p)
  const d = doc(html)

  it('contains every finisher, bust, steal, position leader and dream-roster name', () => {
    const t = text(html)
    for (const n of names(p)) expect(t, n).toContain(n)
  })

  it('every bust row shows its weeks played (FR-006b)', () => {
    const rows = [...d.querySelectorAll('ol.tm-movers.bust > li')]
    expect(rows).toHaveLength(p.busts.length)
    p.busts.forEach((b, i) => expect(rows[i].textContent).toContain(`${b.weeksPlayed} weeks played`))
  })

  it('busts fall and steals rise: each row says so, and no player is in both lists', () => {
    expect([...d.querySelectorAll('ol.tm-movers.bust .tm-move-delta')].every((e) => /^Fell \d+ spots?$/.test(e.textContent!))).toBe(true)
    expect([...d.querySelectorAll('ol.tm-movers.steal .tm-move-delta')].every((e) => /^Rose \d+ spots?$/.test(e.textContent!))).toBe(true)
    const bustIds = new Set(p.busts.map((b) => b.playerId))
    expect(p.steals.some((s) => bustIds.has(s.playerId))).toBe(false)
  })

  it('shows the pointsNote outside any details, before the first points number', () => {
    const note = d.querySelector('.tm-points-note')!
    expect(note.textContent).toBe(p.pointsNote)
    expect(note.closest('details')).toBeNull()
    expect(html.indexOf('tm-points-note')).toBeLessThan(html.indexOf('tm-rank-pts'))
  })

  it('carries the rookie sentence, the missed-season sentence and the ranking label (FR-006b: all three outside the spoiler fold)', () => {
    const t = text(html)
    expect(t).toContain('First-year players with a known NBA draft pick are ranked by that pick, not by stats.')
    expect(t).toContain('Only players who appeared in 2016-17 are on the board, so a player who missed the whole season is not listed.')
    expect(t).toContain(p.rankingLabel)
    for (const sentence of ['known NBA draft pick', 'Only players who appeared', p.rankingLabel]) {
      const holder = [...d.querySelectorAll('p')].find((x) => x.textContent!.includes(sentence))!
      expect(holder.closest('details'), sentence).toBeNull()
    }
  })

  it('"The draft" keeps the points note and the going-in definition; the rookie and missed-season sentences are in the data notes (D9)', () => {
    const draft = d.querySelector('section[aria-label="The draft"]')!.textContent!
    expect(draft).toContain(p.pointsNote)
    expect(draft).toContain(`"Going in" is our own pre-season ranking (${p.rankingLabel}), not where people really drafted players that year.`)
    expect(draft).not.toContain('Rookies')
    expect(draft).not.toContain('known NBA draft pick')
    expect(draft).not.toContain('Only players who appeared')
    const about = d.querySelector('section[aria-label="About this season\'s data"]')!.textContent!
    expect(about).toContain('known NBA draft pick')
    expect(about).toContain('Only players who appeared')
    // finished is defined once, and the going-in sentence is not repeated there
    expect(about).toContain('Finished is a player\'s rank in season points among all')
    expect(about).not.toContain('Going in is our pre-season ranking')
  })

  it('points carry a muted unit, and going in has its denominator (D2, D3)', () => {
    const top = d.querySelector('ol.tm-top')!
    expect(top.querySelector('.tm-rank-pts')!.textContent).toMatch(/ pts$/)
    const subs = [...top.querySelectorAll('.tm-rank-sub')].map((e) => e.textContent!).filter((t) => t !== 'No pre-season rank')
    expect(subs.length).toBeGreaterThan(0)
    for (const t of subs) expect(t).toMatch(new RegExp(`^Going in \\d+(st|nd|rd|th) of ${p.rankedPlayers} ranked$`))
    const mover = d.querySelector('ol.tm-movers.bust .tm-move-nums dd')!
    expect(mover.textContent).toMatch(new RegExp(`of ${p.rankedPlayers} ranked$`))
    expect(d.querySelector('ol.tm-movers .tm-rank-num')).toBeNull() // no empty gutter
  })

  it('the spoilers are in a closed details, and the dream roster is labelled greedy and a team total', () => {
    const fold = d.querySelector('details.tm-spoilers')!
    expect(fold.hasAttribute('open')).toBe(false)
    expect(fold.textContent).toContain('Built by taking the top scorer each lineup spot allows')
    expect(fold.textContent).toContain('It may not be the single best possible roster')
    expect(fold.querySelector('.tm-dream-total')!.textContent).toContain('Team total:')
  })

  it('prev, next and the index are real links to slashless addresses; a null side is omitted', () => {
    const links = [...d.querySelectorAll('nav.tm-neighbours a')].map((a) => [a.getAttribute('href'), a.textContent])
    expect(links).toEqual([
      ['/nba/1976-77', 'Previous season: 1976-77'],
      ['/nba', 'All NBA seasons'],
    ])
    const first = doc(seasonBody(cfg, nba('1976-77')))
    expect([...first.querySelectorAll('nav.tm-neighbours a')].map((a) => a.getAttribute('href'))).toEqual(['/nba/2016-17', '/nba'])
  })

  it('the early NBA season lists what it does not count; the modern one has no such block', () => {
    const early = doc(seasonBody(cfg, nba('1976-77')))
    const items = [...early.querySelectorAll('.hist-excluded li')].map((li) => li.textContent)
    expect(items).toHaveLength(2)
    expect(items.join(' ')).toContain('not tracked by the NBA that season')
    expect(items.join(' ')).not.toMatch(/league/i)
    expect(d.querySelector('.hist-excluded')).toBeNull()
  })

  it('football lists its attribution and the position sentence; basketball has no attribution', () => {
    const nfl = data.NFL.pages[1]
    const t = text(seasonBody(cfg, nfl))
    expect(t).toContain(nfl.attribution!)
    expect(t).toContain('Quarterbacks, running backs, wide receivers and tight ends only.')
    expect(text(html)).not.toContain('Quarterbacks')
  })

  it('never pads: a short or empty bust list says so', () => {
    const short = { ...p, busts: p.busts.slice(0, 2), steals: [] } as SeasonPage
    const t = text(seasonBody(cfg, short))
    expect(t).toContain('Only 2 players in our pre-season top 60 finished lower than ranked.')
    expect(t).toContain("Nobody in the season's top 60 had been ranked lower going in.")
    expect(doc(seasonBody(cfg, short)).querySelectorAll('ol.tm-movers.bust > li')).toHaveLength(2)
  })

  it('names the unranked players left out when there are some', () => {
    expect(text(seasonBody(cfg, { ...p, unrankedLeftOut: 2 }))).toContain('2 more players finished in the top 60 with no pre-season rank')
    expect(text(seasonBody(cfg, { ...p, unrankedLeftOut: 0 }))).not.toContain('no pre-season rank (no stats')
  })

  it('has no league wording, no "Time Machine", no "hindsight", no inline style, a disabled draft button', () => {
    const t = text(html)
    expect(t).not.toMatch(LEAGUE_WORDS)
    expect(t).not.toMatch(/time machine|hindsight/i)
    expect(html).not.toContain('style=')
    expect(d.querySelector('button.tm-play')!.hasAttribute('disabled')).toBe(true)
  })

  it('escapes names: markup in a player name is text, not elements', () => {
    const evil = { ...p, topOverall: [{ ...p.topOverall[0], name: '<img src=x onerror=alert(1)>' }, ...p.topOverall.slice(1)] }
    const dd = doc(seasonBody(cfg, evil))
    expect(dd.querySelector('img')).toBeNull()
    expect(dd.body.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('shows a tie as a visible T{n} with the words in hidden text (an aria-label on a plain span is ignored, C1)', () => {
    const tied = { ...p, topOverall: p.topOverall.map((f, i) => (i < 2 ? { ...f, finishRank: 1 } : f)) }
    const dd = doc(seasonBody(cfg, tied))
    const num = dd.querySelector('.tm-rank-num')!
    expect(num.firstChild!.textContent).toBe('T1')
    expect(num.querySelector('.hist-sr-only')!.textContent!.trim()).toBe('tied for 1st')
    expect(num.querySelector('[aria-label]')).toBeNull()
  })

  it('has the needs-JavaScript line beside the disabled controls, and an empty claim region for the mounted page (C11, C5)', () => {
    const dd = doc(seasonBody(cfg, p))
    expect(dd.querySelector('noscript')!.textContent).toContain('Drafting needs JavaScript turned on.')
    expect(doc(dailyBody(cfg)).querySelector('noscript')!.textContent).toContain('Drafting needs JavaScript turned on.')
    expect(doc(homeBody(cfg, data)).querySelectorAll('noscript').length).toBeGreaterThan(0)
    expect(dd.querySelector('.tm-claim[role="status"]')!.textContent).toBe('')
  })

  it('the neighbours group is named for what it holds, and the footer is a footer (C9, C13)', () => {
    const dd = doc(seasonBody(cfg, p))
    expect(dd.querySelector('nav.tm-neighbours')!.getAttribute('aria-label')).toBe('Other NBA seasons')
    expect(dd.querySelector('footer nav.tm-foot')).not.toBeNull()
  })
})

describe('sport index body', () => {
  it('links every offered season of the sport, newest decade first, and a newest-season tile', () => {
    const d = doc(sportIndexBody(cfg, 'NBA', data))
    expect([...d.querySelectorAll('.tm-years a')].map((a) => a.getAttribute('href'))).toEqual(['/nba/2016-17', '/nba/1976-77'])
    expect([...d.querySelectorAll('.tm-decade-head')].map((h) => h.textContent!.trim().split(' ')[0])).toEqual(['2010s', '1970s'])
    expect(d.querySelector('a.tm-newest')!.getAttribute('href')).toBe('/nba/2016-17')
    expect(d.querySelector('h1')!.textContent).toBe('NBA Redraft: every past season')
  })
})

describe('daily and home bodies', () => {
  const mid = {
    ...data,
    NBA: { ...data.NBA, list: { ...data.NBA.list, seasons: [data.NBA.list.seasons[0], { sport: 'NBA', seasonStart: 1996, label: '1996-97', slug: '1996-97' }, data.NBA.list.seasons[1]] } },
  } as RedraftSeasonData

  it('/daily names no season at all and marks its data-driven regions', () => {
    const html = dailyBody(cfg)
    for (const label of ['1976-77', '2016-17', '2006', '2007']) expect(html).not.toContain(label)
    expect(doc(html).querySelectorAll('[data-dynamic]').length).toBeGreaterThanOrEqual(2)
    expect(doc(html).querySelector('h1')!.textContent).toBe('Daily Redraft: a past season every day')
  })

  it('/ names only the first and last season of each sport, as a range (never a middle one)', () => {
    const t = text(homeBody(cfg, mid))
    expect(t).toContain('1976-77 to 2016-17')
    expect(t).toContain('3 seasons to pick from')
    expect(t).not.toContain('1996-97')
    expect(doc(homeBody(cfg, mid)).querySelector('#sign-in input')!.hasAttribute('disabled')).toBe(true)
  })

  it('no static page says "hindsight", "Time Machine" or league words', () => {
    const everything = [...indexablePages(cfg, data), notFoundPage(cfg)].map((pg) => staticBodyFor(pg, cfg, data)).join(' ')
    expect(text(everything)).not.toMatch(/hindsight|time machine/i)
    expect(text(everything)).not.toMatch(LEAGUE_WORDS)
    expect(everything).not.toContain('style=')
  })
})

describe('not found and the app shell', () => {
  it('404 links home, today, and both sports; the app shell has an empty root body', () => {
    const d = doc(notFoundBody(cfg))
    expect([...d.querySelectorAll('nav.tm-links a')].map((a) => a.getAttribute('href'))).toEqual(['/', '/daily', '/nba', '/nfl'])
    expect(staticBodyFor(appShellPage(cfg), cfg, data)).toBe('')
  })

  it('every page body has a real home link through tmHome and the footer navigation', () => {
    for (const pg of indexablePages(cfg, data)) {
      const d = doc(staticBodyFor(pg, cfg, data))
      expect(d.querySelector('a.topbar-wordmark')!.getAttribute('href'), pg.path).toBe('/')
      expect(d.querySelector('nav.tm-foot')!.querySelectorAll('a'), pg.path).toHaveLength(4)
    }
  })
})

// V-08c: HistoricalPlayerCard.team is the prior season's team; a season page does not print it.
describe('season page rows carry no team (V-08c)', () => {
  it('prints names and positions, never the card team', () => {
    const page = data.NBA.pages[0]
    const teams = new Set([...page.topOverall, ...page.busts, ...page.steals].map((r) => r.team).filter((t): t is string => !!t))
    expect(teams.size).toBeGreaterThan(0)
    const doc = new DOMParser().parseFromString(seasonBody(cfg, page), 'text/html')
    for (const who of doc.querySelectorAll('.tm-rank-who')) {
      expect(who.querySelector('.muted:not(.tm-rank-sub)')).toBeNull()
    }
  })
})

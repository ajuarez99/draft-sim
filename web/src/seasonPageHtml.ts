import type { Finisher, HistoricalSport, Mover, RedraftSeasonData, SeasonPage } from './api'
import { esc } from './htmlEscape'
import { notCountedReason, pts1, statLong } from './historicalCopy'
import * as copy from './redraftCopy'
import { DAILY_PATH, SPORTS, offeredSportsOf, seasonPath, sportIndexPath, sportRange, type RedraftPage } from './redraftPages'
import { tmHome, type SiteConfig } from './site'

// Spec 074 T023: the static body of each Redraft page, as strings, following the element trees and class
// names in design.md. PURE (no React, no DOM global): vite.config.ts runs it in Node at build time. Every
// word is from redraftCopy.ts; the data is the fixture or the backend's public season endpoint.
//
// No inline `style` anywhere (the CSP forbids it). Buttons are rendered disabled: they cannot work before
// React mounts, but they take the space the real ones will. Regions that depend on who is looking or on the
// day (daily cards, yesterday's list) carry `data-dynamic`, which Phase 5's parity test uses to exclude them
// from the static-versus-React comparison (tasks.md T041) instead of loosening the comparison.

const STARTER_SKIP = new Set(['BN', 'IR'])

function link(href: string, cls: string, text: string): string {
  return `<a class="${cls}" href="${esc(href)}">${esc(text)}</a>`
}

function posBadges(positions: string[]): string {
  return positions.map((p) => `<span class="pos ${esc(p)}">${esc(p)}</span>`).join('')
}

/** The bar and the footer every page carries, around the page's own content. Mirrors TimeMachineShell. */
function frame(cfg: SiteConfig, content: string): string {
  const home = tmHome(cfg)
  return (
    `<div class="app-shell">` +
    `<a class="skip-link" href="#main">${esc(copy.skipLink)}</a>` +
    `<header class="topbar">` +
    `<a class="topbar-wordmark cond" href="${home}" aria-label="${esc(copy.homeAria)}">${esc(copy.brand)}</a>` +
    `<div class="topbar-actions"><a class="topbar-signin" href="${home}#sign-in">${esc(copy.signInLink)}</a></div>` +
    `</header>` +
    `<main class="app-main" id="main" tabindex="-1">${content}</main>` +
    `</div>`
  )
}

/** `sports`: the ones the build has seasons for (CR-08); a sport with none gets no link. */
function footNav(cfg: SiteConfig, sports: HistoricalSport[] = SPORTS): string {
  return (
    `<footer><nav class="tm-foot" aria-label="${esc(copy.footNavLabel)}">` +
    link(tmHome(cfg), '', copy.footHome) +
    link(DAILY_PATH, '', copy.footDaily) +
    sports.map((s) => link(sportIndexPath(s), '', copy.footSeasons(s))).join('') +
    `<span class="muted small">${esc(copy.footNote)}</span>` +
    `</nav></footer>`
  )
}

const pts = (n: number) => `${esc(pts1(n))}<span class="muted small">${esc(copy.ptsUnit)}</span>`

function howToPlay(): string {
  const steps = copy.howSteps
    .map((s) => `<li><strong>${esc(s.title)}</strong><span>${esc(s.body)}</span></li>`)
    .join('')
  return `<section class="tm-section" aria-label="${esc(copy.howHeading)}"><h2>${esc(copy.howHeading)}</h2><ol class="tm-steps">${steps}</ol></section>`
}

// ---- Season page -------------------------------------------------------------------------------------

/** No team: the card's team is the PRIOR season's (HistoricalPlayerCard), so on a page about the season itself it would mislead (V-08c). */
function whoCell(p: { name: string; positions: string[] }, sub: string | null): string {
  return (
    `<div class="tm-rank-who"><strong>${esc(p.name)}</strong> ${posBadges(p.positions)}` +
    (sub ? `<span class="tm-rank-sub muted small">${esc(sub)}</span>` : '') +
    `</div>`
  )
}

function finisherRows(rows: Finisher[], numbered: 'rank' | 'index', ranked: number): string {
  const counts = new Map<number, number>()
  for (const r of rows) counts.set(r.finishRank, (counts.get(r.finishRank) ?? 0) + 1)
  return rows
    .map((r, i) => {
      let num: string
      if (numbered === 'index') num = String(i + 1)
      else if ((counts.get(r.finishRank) ?? 0) > 1)
        num = `${esc(copy.tieText(r.finishRank))}<span class="hist-sr-only">${esc(copy.tieAria(r.finishRank))}</span>`
      else num = String(r.finishRank)
      return (
        `<li class="tm-rank-row"><span class="tm-rank-num">${num}</span>` +
        whoCell(r, numbered === 'rank' ? copy.rankedGoingIn(r.goingInRank, ranked) : null) +
        `<span class="tm-rank-pts">${pts(r.points)}</span></li>`
      )
    })
    .join('')
}

function moverRows(rows: Mover[], board: number, ranked: number): string {
  return rows
    .map(
      (m) =>
        `<li class="tm-mover"><div class="tm-mover-head">${whoCell(m, copy.weeksPlayed(m.weeksPlayed))}` +
        `<span class="tm-rank-pts">${pts(m.points)}</span></div>` +
        `<dl class="tm-move-nums"><div><dt>${esc(copy.moverGoingIn)}</dt><dd>${esc(copy.moverGoingInOf(m.goingInRank, ranked))}</dd></div>` +
        `<div><dt>${esc(copy.moverFinished)}</dt><dd>${esc(copy.moverFinishedOf(m.finishRank, board))}</dd></div></dl>` +
        `<p class="tm-move-delta">${esc(copy.moverDelta(m.goingInRank, m.finishRank))}</p></li>`,
    )
    .join('')
}

/** A bust or steal block. Never padded: 0 rows is the empty sentence, fewer than five is the "Only n" sentence. */
function moverSection(
  heading: string, sub: string, kind: 'bust' | 'steal', rows: Mover[], board: number, ranked: number, shortText: string,
  emptyText: string, unranked: string | null,
): string {
  return (
    `<h3>${esc(heading)}</h3><p class="muted small">${esc(sub)}</p>` +
    (rows.length === 0
      ? `<p class="muted small">${esc(emptyText)}</p>`
      : `<ol class="tm-movers ${kind}">${moverRows(rows, board, ranked)}</ol>` +
        (rows.length < 5 ? `<p class="muted small">${esc(shortText)}</p>` : '')) +
    (unranked ? `<p class="muted small">${esc(unranked)}</p>` : '')
  )
}

export function seasonBody(cfg: SiteConfig, p: SeasonPage, sports: HistoricalSport[] = SPORTS): string {
  const s = p.sport
  const starters = p.rosterPositions.filter((x) => !STARTER_SKIP.has(x))
  const bench = p.rounds - starters.length
  const pills = starters.map((x) => `<li class="hist-pill">${esc(x)}</li>`).join('')
  const excluded = Object.entries(p.excludedStats)
  const countedKeys = Object.keys(p.statCoverage)

  const positions = Object.entries(p.topByPosition)
    .map(
      ([pos, rows]) =>
        `<section><h4><span class="pos ${esc(pos)}">${esc(pos)}</span> ${esc(copy.positionFullWord[pos] ?? pos)}</h4>` +
        `<ol class="tm-rank-list">${finisherRows(rows, 'index', p.rankedPlayers)}</ol></section>`,
    )
    .join('')

  const dream = p.dreamRoster
    .map(
      (d) =>
        `<li><strong>${esc(d.name)}</strong> ${posBadges(d.positions)}<span class="tm-slot-pts">${pts(d.points)}</span></li>`,
    )
    .join('')

  const neighbours =
    (p.prevSlug ? link(seasonPath(s, p.prevSlug), 'tm-pill-link', copy.prevSeason(p.prevSlug)) : '') +
    (p.nextSlug ? link(seasonPath(s, p.nextSlug), 'tm-pill-link', copy.nextSeason(p.nextSlug)) : '')

  const unrankedText = p.unrankedLeftOut > 0 ? copy.unrankedLine(p.unrankedLeftOut, p.teams) : null

  const spoilers =
    `<details class="tm-fold tm-spoilers"><summary><h2>${esc(copy.spoilerH2(p.label))}</h2>` +
    `<span class="tm-fold-hint muted small">${esc(copy.spoilerHint)}</span></summary>` +
    `<h3>${esc(copy.topHeading(p.topOverall.length))}</h3><p class="muted small">${esc(copy.topSub)}</p>` +
    `<ol class="tm-rank-list tm-top">${finisherRows(p.topOverall, 'rank', p.rankedPlayers)}</ol>` +
    moverSection(copy.bustsHeading, copy.bustsSub(p.teams), 'bust', p.busts, p.boardPlayers, p.rankedPlayers,
      copy.bustsShort(p.busts.length, p.teams), copy.bustsEmpty(p.teams), null) +
    moverSection(copy.stealsHeading, copy.stealsSub(p.teams), 'steal', p.steals, p.boardPlayers, p.rankedPlayers,
      copy.stealsShort(p.steals.length, p.teams), copy.stealsEmpty(p.teams), unrankedText) +
    `<h3>${esc(copy.dreamHeading)}</h3><p class="muted small">${esc(copy.dreamSub)}</p>` +
    `<ul class="hist-slots">${dream}</ul>` +
    `<p class="tm-dream-total muted small">${esc(copy.dreamTotal(pts1(p.dreamRosterPoints)))}</p>` +
    `<details class="tm-fold"><summary><h3>${esc(copy.positionFold)}</h3></summary><div class="tm-pos-grid">${positions}</div></details>` +
    `</details>`

  const about =
    `<section class="panel hist-disclosure" aria-label="${esc(copy.aboutHeading)}"><h2>${esc(copy.aboutHeading)}</h2>` +
    (s === 'NFL' && countedKeys.length > 0
      ? `<h3>${esc(copy.countedHeading(p.label))}</h3><ul class="hist-counted">${countedKeys.map((k) => `<li class="hist-pill">${esc(statLong(k))}</li>`).join('')}</ul>`
      : '') +
    (excluded.length > 0
      ? `<h3>${esc(copy.excludedHeading(p.label))}</h3><ul class="hist-excluded">${excluded
          .map(([k, why]) => `<li><strong>${esc(statLong(k))}</strong> <span class="muted">${esc(notCountedReason(why, k))}</span></li>`)
          .join('')}</ul>`
      : '') +
    (p.dataNotes.length > 0 ? `<ul class="hist-notes muted small">${p.dataNotes.map((n) => `<li>${esc(n)}</li>`).join('')}</ul>` : '') +
    `<p class="muted small">${esc(copy.howRanksWork(p.boardPlayers))}` +
    (s === 'NFL' ? ` ${esc(copy.footballPositionsOnly)}` : '') +
    `</p>` +
    `<p class="muted small">${esc(copy.rookieNote(s))} ${esc(copy.missedSeasonNote(p.label))}</p>` +
    (p.attribution ? `<p class="muted small hist-attribution">${esc(p.attribution)}</p>` : '') +
    `</section>`

  return frame(
    cfg,
    `<div class="content tm tm-page">` +
      `<header class="tm-hero">` +
      `<p class="tm-over">${esc(copy.seasonOver(s))}</p>` +
      `<h1 class="tm-title tm-title-sm">${esc(copy.seasonH1(p.label, s))}</h1>` +
      `<p class="tm-tagline">${esc(copy.seasonTagline(p.label, s))}</p>` +
      `<button class="tm-play" type="button" disabled>${esc(copy.draftButton)}</button>` +
      `<span class="hist-sr-only" role="status" data-dynamic="starting"></span>` +
      `<p class="tm-facts">${esc(copy.seasonFacts(p.teams, p.rounds))}</p>` +
      `<p class="muted small" data-dynamic="state-line">${esc(copy.stateLineStatic)}</p>` +
      noscriptLine() +
      `<div class="tm-claim" role="status" tabindex="-1" data-dynamic="claim"></div>` +
      `</header>` +
      `<section class="tm-section" aria-label="${esc(copy.theDraft)}"><h2>${esc(copy.theDraft)}</h2>` +
      `<ul class="hist-counted">${pills}${bench > 0 ? `<li class="hist-pill">${esc(copy.benchLabel(bench))}</li>` : ''}</ul>` +
      `<p class="muted small">${esc(p.scoringNote)}</p>` +
      `<p class="tm-points-note muted small">${esc(p.pointsNote)}</p>` +
      `<p class="tm-going-in muted small">${esc(copy.goingInNote(p.rankingLabel))}</p>` +
      `</section>` +
      spoilers +
      about +
      `<nav class="tm-neighbours" aria-label="${esc(copy.otherSeasons(s))}">${neighbours}` +
      link(sportIndexPath(s), 'tm-pill-link', copy.allSeasons(s)) +
      `</nav>` +
      `<button class="tm-btn quiet" type="button" disabled>${esc(copy.draftAgain(p.label))}</button>` +
      footNav(cfg, sports) +
      `</div>`,
  )
}

// ---- Sport index -------------------------------------------------------------------------------------

export function sportIndexBody(cfg: SiteConfig, s: HistoricalSport, data: RedraftSeasonData): string {
  const sports = offeredSportsOf(data)
  const seasons = data[s].list.seasons
  const r = sportRange(data, s)
  const newest = seasons[seasons.length - 1]
  const byDecade = new Map<number, typeof seasons>()
  for (const x of seasons) {
    const d = Math.floor(x.seasonStart / 10) * 10
    byDecade.set(d, [...(byDecade.get(d) ?? []), x])
  }
  const decades = [...byDecade.keys()].sort((a, b) => b - a)
  const groups = decades
    .map((d) => {
      const rows = byDecade.get(d)!.sort((a, b) => a.seasonStart - b.seasonStart)
      return (
        `<section class="tm-decade"><h2 class="tm-decade-head">${d}s <span class="muted small">${esc(copy.decadeSeasons(rows.length))}</span></h2>` +
        `<ul class="tm-years">${rows.map((x) => `<li><a class="tm-pill-link" href="${esc(seasonPath(s, x.slug))}">${esc(x.label)}<span class="hist-sr-only">${esc(copy.yearPillSuffix(s))}</span></a></li>`).join('')}</ul></section>`
      )
    })
    .join('')
  return frame(
    cfg,
    `<div class="content tm tm-page">` +
      `<header class="tm-hero">` +
      `<p class="tm-over">${esc(copy.seasonOver(s))}</p>` +
      `<h1 class="tm-title tm-title-sm">${esc(copy.sportIndexH1(s))}</h1>` +
      (r ? `<p class="tm-tagline">${esc(copy.sportIndexTagline(r.first, r.last))}</p>` : '') +
      (newest
        ? `<a class="tm-newest ${s.toLowerCase()}" href="${esc(seasonPath(s, newest.slug))}"><span class="muted small">${esc(copy.newestSeason)}</span> <strong>${esc(newest.label)}</strong></a>`
        : '') +
      `</header>` +
      (seasons.length === 0 ? `<p class="muted small">${esc(copy.seasonsEmpty(s))}</p>` : groups) +
      `<p class="muted small">${esc(copy.namingRule(s))}</p>` +
      `<p class="muted small">${esc(copy.eraNote(s))}</p>` +
      footNav(cfg, sports) +
      `</div>`,
  )
}

// ---- Daily -------------------------------------------------------------------------------------------

/** The line beside controls that need scripts (C11). `data-dynamic`: a mounted page has no such line. */
function noscriptLine(): string {
  return `<noscript data-dynamic="noscript"><p class="muted small">${esc(copy.needsScript)}</p></noscript>`
}

/** `sports`: the ones the build has seasons for (CR-08 / V-03): a sport with none gets no card. */
function dailyCardsStatic(sports: HistoricalSport[]): string {
  const cards = sports.map(
    (s) =>
      `<li class="tm-daily-card ${s.toLowerCase()}"><strong>${esc(copy.sportTile(s))}</strong>` +
      `<span class="muted small">${esc(copy.dailyCardStatic)}</span>` +
      `<button class="tm-btn" type="button" disabled>${esc(copy.dailyPlay)}</button></li>`,
  ).join('')
  return (
    `<div class="tm-daily-block" data-dynamic="daily-cards"><ul class="tm-daily-cards">${cards}</ul>` +
    noscriptLine() +
    `<p class="tm-daily-note muted small">${esc(copy.dailyGuestStreak)}</p></div>`
  )
}

export function dailyBody(cfg: SiteConfig, sports: HistoricalSport[] = SPORTS): string {
  return frame(
    cfg,
    `<div class="content tm tm-page">` +
      `<header class="tm-hero">` +
      `<p class="tm-over">${esc(copy.dailyOver)}</p>` +
      `<h1 class="tm-title tm-title-sm">${esc(copy.dailyH1)}</h1>` +
      `<p class="tm-tagline">${esc(copy.dailyTagline)}</p>` +
      dailyCardsStatic(sports) +
      `</header>` +
      `<section class="tm-section" aria-label="${esc(copy.yesterdayHeading)}"><h2>${esc(copy.yesterdayHeading)}</h2>` +
      `<p class="muted small" data-dynamic="yesterday">${esc(copy.yesterdayStatic)}</p></section>` +
      howToPlay() +
      footNav(cfg, sports) +
      `</div>`,
  )
}

// ---- Home (Phase 5 swaps it in with the React home; index.html still uses staticTmBody until then) ---

const TM_MARK =
  `<span class="tm-mark" aria-hidden="true"><svg viewBox="0 0 48 48" width="34" height="34" fill="none" stroke="currentColor" ` +
  `stroke-width="4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 24a15 15 0 1 0 5-11.2"></path>` +
  `<path d="M8 7v7h7"></path><path d="M24 16v9l6 4"></path></svg></span>`

export function homeBody(cfg: SiteConfig, data: RedraftSeasonData): string {
  const sports = offeredSportsOf(data)
  const tiles = SPORTS.map((s) => {
    const r = sportRange(data, s)
    if (!r) return ''
    return (
      `<a class="tm-sport-tile ${s.toLowerCase()}" href="${esc(sportIndexPath(s))}"><strong>${esc(copy.sportTile(s))}</strong>` +
      `<span>${esc(copy.sportTileRange(r.first, r.last))}</span><span class="muted small">${esc(copy.sportTileCount(r.count))}</span></a>`
    )
  }).join('')
  return frame(
    cfg,
    `<div class="content tm tm-page">` +
      `<header class="tm-hero">${TM_MARK}` +
      `<p class="tm-over">${esc(copy.dailyOver)}</p>` +
      `<h1 class="tm-title tm-title-sm">${esc(copy.homeH1)}</h1>` +
      `<p class="tm-tagline">${esc(copy.homeTagline)}</p>` +
      `</header>` +
      `<section class="tm-daily" aria-label="${esc(copy.dailyHeading)}"><h2>${esc(copy.dailyHeading)}</h2>${dailyCardsStatic(sports)}` +
      `${link(DAILY_PATH, 'tm-fold-link', copy.dailyAbout)}</section>` +
      `<section class="tm-section" aria-label="${esc(copy.pickASeason)}"><h2>${esc(copy.pickASeason)}</h2><div class="tm-sport-tiles">${tiles}</div></section>` +
      `<div class="tm-claim" role="status" tabindex="-1" data-dynamic="claim"></div>` +
      howToPlay() +
      `<section class="tm-signin" id="sign-in"><h2>${esc(copy.keepHeading)}</h2><p>${esc(copy.keepBody)}</p>` +
      `<form class="signin-form"><label class="signin-label">${esc(copy.signInLabel)}` +
      `<input class="signin-input" placeholder="${esc(copy.signInPlaceholder)}" disabled /></label>` +
      `<button class="signin-continue" type="submit" disabled>${esc(copy.signInContinue)}</button></form>` +
      noscriptLine() +
      `<p class="signin-note muted small">${esc(copy.keepNote)}</p>` +
      `<p class="signin-note muted small">${esc(copy.analyticsLine)}</p></section>` +
      footNav(cfg, sports) +
      `</div>`,
  )
}

// ---- Not found ---------------------------------------------------------------------------------------

export function notFoundBody(cfg: SiteConfig, sports: HistoricalSport[] = SPORTS): string {
  return frame(
    cfg,
    `<div class="content tm tm-page"><header class="tm-hero">` +
      `<h1 class="tm-title tm-title-sm">${esc(copy.notFoundH1)}</h1>` +
      `<p class="tm-tagline">${esc(copy.notFoundBody)}</p>` +
      `<nav class="tm-links" aria-label="${esc(copy.notFoundNavLabel)}">` +
      link(tmHome(cfg), 'tm-pill-link', copy.notFoundLinks.home) +
      link(DAILY_PATH, 'tm-pill-link', copy.notFoundLinks.daily) +
      sports.map((s) => link(sportIndexPath(s), 'tm-pill-link', copy.notFoundLinks[s === 'NBA' ? 'nba' : 'nfl'])).join('') +
      `</nav></header></div>`,
  )
}

// ---- Dispatch ----------------------------------------------------------------------------------------

/** The static markup that goes inside `<div id="root">` for a page. The app shell has an empty root. */
export function staticBodyFor(page: RedraftPage, cfg: SiteConfig, data: RedraftSeasonData): string {
  switch (page.kind) {
    case 'home':
      return homeBody(cfg, data)
    case 'daily':
      return dailyBody(cfg, offeredSportsOf(data))
    case 'sport':
      return sportIndexBody(cfg, page.sport!, data)
    case 'season': {
      const sp = data[page.sport!].pages.find((x) => x.slug === page.slug)
      if (!sp) throw new Error(`seasonPageHtml: no season data for ${page.path}`)
      return seasonBody(cfg, sp, offeredSportsOf(data))
    }
    case 'notFound':
      return notFoundBody(cfg, offeredSportsOf(data))
    case 'appShell':
      return ''
  }
}

/**
 * The JSON a page embeds as `<script type="application/json" id="redraft-data">`, or null. A season page
 * carries its SeasonPage; a sport index its SeasonList: the same shapes the public endpoint answers with, so
 * the React page parses one thing whether it came from the file or from a client-side navigation.
 */
export function embeddedDataFor(page: RedraftPage, data: RedraftSeasonData): unknown | null {
  if (page.kind === 'season') return data[page.sport!].pages.find((x) => x.slug === page.slug) ?? null
  if (page.kind === 'sport') return data[page.sport!].list
  // The home page's tiles read their range and count from both lists.
  if (page.kind === 'home') return { NBA: data.NBA.list, NFL: data.NFL.list }
  return null
}

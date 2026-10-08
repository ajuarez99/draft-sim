import { useRef, useState } from 'react'
import type {
  PlayerSpotlightApplicable,
  PlayerSpotlight as PlayerSpotlightData,
  Sport,
  SpotlightOwnership,
  SpotlightPeriod,
  SpotlightTrendingEntry,
  WeeklyReport,
} from '../api'
import type { Block } from '../useBlock'
import Avatar from './Avatar'
import PlayerFace from './PlayerFace'
import PlayerLink from './PlayerLink'
import { SkeletonRows } from './Skeleton'

/*
 * specs/014: the league home's player spotlight.
 *
 * Three short ranked lists (Top, Trending, Rookie watch). Wide screens show them
 * as three columns on the dashboard's own grid; phones show one at a time behind
 * a segmented control. Both layouts are the SAME markup: CSS media queries pick
 * which, so there is no layout flash. This file never compares a sport: it
 * branches only on `playersPlayMultiplePerPeriod` and on the presence of
 * `topOfNight` (FR-013, pinned by a source-scan test on the backend).
 */

type Props = {
  /** Where a player's name links. Required: the spotlight is a view of one league. */
  sleeperLeagueId: string
  spotlight: PlayerSpotlightData
  /** The page's existing weekly-report block; football's top list is read from it (research R9). */
  weekly: Block<WeeklyReport>
}

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/

function parseDate(iso: string): Date | null {
  const m = DATE_RE.exec(iso)
  // Local y/m/d parts: `new Date('2026-10-21')` is UTC midnight and shows the previous day in US time zones.
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null
}

/** "Tue, Oct 21" for a YYYY-MM-DD calendar date. */
export function formatCalendarDate(iso: string): string {
  const d = parseDate(iso)
  return d ? d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }) : iso
}

function shortDate(iso: string): string {
  const d = parseDate(iso)
  return d ? d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : iso
}

/** The scoring period named in words: a date for a night, "Week N" for a week. Never "last night" alone. */
export function periodLabel(period: SpotlightPeriod): string {
  return period.kind === 'NIGHT' ? formatCalendarDate(period.date) : `Week ${period.week}`
}

const COLLAPSED_ROWS = 5
const TAB_KEY = 'lh-spotlight-tab'
type TabKey = 'top' | 'trending' | 'rookies'
const TABS: { key: TabKey; label: string }[] = [
  { key: 'top', label: 'Top' },
  { key: 'trending', label: 'Trending' },
  { key: 'rookies', label: 'Rookies' },
]

function readTab(): TabKey {
  try {
    const v = window.localStorage.getItem(TAB_KEY)
    if (TABS.some((t) => t.key === v)) return v as TabKey
  } catch {
    /* storage can be blocked; fall through to the default */
  }
  return 'trending'
}

export default function PlayerSpotlight({ sleeperLeagueId, spotlight, weekly }: Props) {
  if (!spotlight.applies) return null
  return (
    <section className="section lh-spotlight" aria-labelledby="lh-spotlight-h">
      <h2 className="section-title" id="lh-spotlight-h">
        Player spotlight
      </h2>
      <SpotlightLists sleeperLeagueId={sleeperLeagueId} spotlight={spotlight} weekly={weekly} idPrefix="lh" />
    </section>
  )
}

/**
 * specs/015 research R3: the phone segmented control plus the three columns, without the
 * section chrome, so the root home page can mount one per league tab. Element ids come from
 * `idPrefix` (several copies can be in the DOM at once); the league home passes "lh", which keeps
 * its ids exactly as they were. The chosen list is shared across both pages through TAB_KEY.
 */
export function SpotlightLists({
  sleeperLeagueId,
  spotlight,
  weekly,
  idPrefix,
}: {
  sleeperLeagueId: string
  spotlight: PlayerSpotlightApplicable
  weekly: Block<WeeklyReport>
  idPrefix: string
}) {
  const [tab, setTab] = useState<TabKey>(readTab)
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({})

  const choose = (key: TabKey, focus = false) => {
    setTab(key)
    try {
      window.localStorage.setItem(TAB_KEY, key)
    } catch {
      /* per-viewer convenience only */
    }
    if (focus) tabRefs.current[key]?.focus()
  }
  const onKey = (e: React.KeyboardEvent, i: number) => {
    const last = TABS.length - 1
    const next =
      e.key === 'ArrowRight' ? (i === last ? 0 : i + 1) : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1) : e.key === 'Home' ? 0 : e.key === 'End' ? last : null
    if (next == null) return
    e.preventDefault()
    choose(TABS[next].key, true)
  }

  return (
    <>
      {/* Phones only (CSS): one list at a time. */}
      <div className="lh-spot-tabs" role="tablist" aria-label="Player spotlight lists">
        {TABS.map((t, i) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            id={`${idPrefix}-spot-tab-${t.key}`}
            aria-selected={tab === t.key}
            aria-controls={`${idPrefix}-spot-panel-${t.key}`}
            tabIndex={tab === t.key ? 0 : -1}
            ref={(el) => {
              tabRefs.current[t.key] = el
            }}
            className="lh-spot-tab"
            onClick={() => choose(t.key)}
            onKeyDown={(e) => onKey(e, i)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="lh-spot-cols">
        {/* Present only when the sport has a per-night list; football uses the weekly report. */}
        {spotlight.topOfNight ? (
          <TopOfNight sleeperLeagueId={sleeperLeagueId} spotlight={spotlight} active={tab === 'top'} idPrefix={idPrefix} />
        ) : (
          <TopOfWeek sleeperLeagueId={sleeperLeagueId} spotlight={spotlight} weekly={weekly} active={tab === 'top'} idPrefix={idPrefix} />
        )}
        <Trending sleeperLeagueId={sleeperLeagueId} spotlight={spotlight} active={tab === 'trending'} idPrefix={idPrefix} />
        <RookieWatch sleeperLeagueId={sleeperLeagueId} spotlight={spotlight} active={tab === 'rookies'} idPrefix={idPrefix} />
      </div>
    </>
  )
}

// --- shared section + expandable list ----------------------------------------

function Section({
  tabKey,
  idPrefix,
  active,
  title,
  tag,
  children,
}: {
  tabKey: TabKey
  idPrefix: string
  active: boolean
  title: string
  tag?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div
      className="lh-spot-section"
      role="tabpanel"
      id={`${idPrefix}-spot-panel-${tabKey}`}
      aria-labelledby={`${idPrefix}-spot-tab-${tabKey}`}
      data-active={active}
    >
      <h3 className="lh-spot-h">
        {title}{tag ? ' ' : null}
        {tag}
      </h3>
      {children}
    </div>
  )
}

const notFinalTag = (period: SpotlightPeriod | null) =>
  period?.kind === 'WEEK' && !period.weekFinal ? <span className="lh-spotlight-tag">· not final yet</span> : null

/** Five rows, with a text toggle to ten. */
function Rows({ rows }: { rows: React.ReactNode[] }) {
  const [open, setOpen] = useState(false)
  const shown = open ? rows : rows.slice(0, COLLAPSED_ROWS)
  return (
    <>
      <ol className="row-list lh-rows">{shown}</ol>
      {rows.length > COLLAPSED_ROWS && (
        <button type="button" className="lh-link lh-spot-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? 'Show fewer' : `Show all ${rows.length}`}
        </button>
      )}
    </>
  )
}

// --- sections -----------------------------------------------------------------

/** Why there is no period to score against, in words (FR-010). The date is shown only when known. */
function noPeriodText(s: PlayerSpotlightApplicable): string {
  if (s.periodUnavailable === 'NO_COMPLETE_NIGHT_YET') return "The latest night's scores aren't final in our data yet."
  if (s.periodUnavailable === 'NO_WEEK_SCORED') {
    return 'No weeks scored yet this season.' + (s.seasonStartDate ? ` Season starts ${formatCalendarDate(s.seasonStartDate)}.` : '')
  }
  // Only regular-season games are stored; preseason games are played but not counted here.
  return s.seasonStartDate
    ? `No regular-season games yet. The regular season starts ${formatCalendarDate(s.seasonStartDate)}; preseason games aren't counted here.`
    : "No regular-season games yet. Preseason games aren't counted here."
}

function emptyText(s: PlayerSpotlightApplicable, unavailable: string | null): string {
  if (unavailable === 'NO_PERIOD') return noPeriodText(s)
  if (unavailable === 'SECTION_FAILED') return "Couldn't load this section."
  return 'Nothing to show yet.'
}

function TopOfNight({ sleeperLeagueId, spotlight, active, idPrefix }: { sleeperLeagueId: string; spotlight: PlayerSpotlightApplicable; active: boolean; idPrefix: string }) {
  const section = spotlight.topOfNight
  if (!section) return null
  const period = spotlight.period
  const night = period?.kind === 'NIGHT' ? period : null
  return (
    <Section tabKey="top" idPrefix={idPrefix} active={active} title={night ? `Top players · ${formatCalendarDate(night.date)}` : 'Top players'}>
      {night && (
        <p className="muted small">
          {night.gamesCount} {night.gamesCount === 1 ? 'game' : 'games'}
        </p>
      )}
      {section.entries.length === 0 ? (
        <p className="muted small">
          {section.unavailable === 'NO_ROSTERED_PLAYED' && period
            ? `No one on a roster in this league played ${period.kind === 'NIGHT' ? `on ${periodLabel(period)}` : `in ${periodLabel(period)}`}.`
            : emptyText(spotlight, section.unavailable)}
        </p>
      ) : (
        <Rows
          rows={section.entries.map((e, i) => (
            <PlayerRow
              sleeperLeagueId={sleeperLeagueId}
              key={e.playerId}
              rank={i + 1}
              sport={spotlight.sport}
              playerId={e.playerId}
              name={e.name}
              position={e.position}
              team={e.team}
              points={e.points}
              game={{ opponent: e.opponent, isAway: e.isAway }}
              ownership={e.ownership}
            />
          ))}
        />
      )}
      {spotlight.laterNightInProgress && (
        <p className="muted small">Scores from {formatCalendarDate(spotlight.laterNightInProgress)} are still updating.</p>
      )}
    </Section>
  )
}

function TopOfWeek({
  sleeperLeagueId,
  spotlight,
  weekly,
  active,
  idPrefix,
}: {
  sleeperLeagueId: string
  spotlight: PlayerSpotlightApplicable
  weekly: Block<WeeklyReport>
  active: boolean
  idPrefix: string
}) {
  const period = spotlight.period
  const spotlightWeek = period?.kind === 'WEEK' ? period.week : null
  // Labelled from the data actually rendered below (the weekly report), never from the spotlight's own week.
  const shownWeek = weekly.status === 'ok' ? weekly.data.week : null
  if (import.meta.env.DEV && shownWeek != null && spotlightWeek != null && shownWeek !== spotlightWeek) {
    console.warn(`Player spotlight is on week ${spotlightWeek} but the weekly report is on week ${shownWeek}`)
  }
  let body: React.ReactNode
  if (weekly.status === 'loading' || weekly.status === 'idle') {
    body = <SkeletonRows count={3} label="Loading top players" />
  } else if (weekly.status === 'error') {
    body = <p className="muted small">Couldn't load this week's top players.</p>
  } else if (!period) {
    body = <p className="muted small">{noPeriodText(spotlight)}</p>
  } else {
    const performers = weekly.data.topPerformers ?? []
    body =
      performers.length === 0 ? (
        <p className="muted small">No scored performances yet this week.</p>
      ) : (
        <Rows
          rows={performers.map((p, i) => (
            <PlayerRow
              sleeperLeagueId={sleeperLeagueId}
              key={p.playerId}
              rank={i + 1}
              sport={spotlight.sport}
              playerId={p.playerId}
              name={p.playerName}
              position={p.position}
              team={p.team}
              points={p.points}
              game={p.opponent ? { opponent: p.opponent, isAway: p.isAway } : undefined}
              ownership={{ rostered: true, teamName: p.teamName, avatarId: p.avatarId }}
            />
          ))}
        />
      )
  }
  return (
    <Section
      tabKey="top"
      idPrefix={idPrefix}
      active={active}
      title={shownWeek != null && period ? `Top players · Week ${shownWeek}` : 'Top players'}
      tag={shownWeek != null ? notFinalTag(period) : null}
    >
      {body}
    </Section>
  )
}

/** "12 minutes", "3 hours", "2 days" between an ISO instant and now. */
function relativeAge(iso: string): string {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000))
  if (mins < 60) return `${mins} ${mins === 1 ? 'minute' : 'minutes'}`
  const hours = Math.round(mins / 60)
  if (hours < 48) return `${hours} ${hours === 1 ? 'hour' : 'hours'}`
  return `${Math.round(hours / 24)} days`
}

function outcomeText(e: SpotlightTrendingEntry, period: SpotlightPeriod | null): string | null {
  switch (e.outcome) {
    case 'DID_NOT_PLAY':
      if (!period) return null
      return period.kind === 'NIGHT' ? `Did not play on ${formatCalendarDate(period.date)}` : `Did not play in Week ${period.week}`
    case 'NO_GAME':
      if (!period) return null
      return period.kind === 'NIGHT' ? `No game on ${formatCalendarDate(period.date)}` : `Bye in Week ${period.week}`
    default:
      return null
  }
}

/** What a trending score is a score FOR: "Wk 3" or "Oct 21". */
function periodPrefix(period: SpotlightPeriod | null): string | undefined {
  if (!period) return undefined
  return period.kind === 'NIGHT' ? shortDate(period.date) : `Wk ${period.week}`
}

function Trending({ sleeperLeagueId, spotlight, active, idPrefix }: { sleeperLeagueId: string; spotlight: PlayerSpotlightApplicable; active: boolean; idPrefix: string }) {
  const t = spotlight.trending
  return (
    <Section tabKey="trending" idPrefix={idPrefix} active={active} title={`Trending · last ${t.lookbackHours}h`}>
      {t.stale && t.fetchedAt && <p className="muted small">Updated {relativeAge(t.fetchedAt)} ago</p>}
      {t.entries.length === 0 ? (
        <p className="muted small">
          {t.unavailable === 'NEVER_FETCHED'
            ? "Trending hasn't loaded yet; it updates when the league refreshes."
            : t.unavailable === 'SECTION_FAILED'
              ? "Couldn't load this section."
              : 'No trending players right now.'}
        </p>
      ) : (
        <Rows
          rows={t.entries.map((e) => {
            const played = e.outcome === 'PLAYED' && e.points != null
            const note = outcomeText(e, spotlight.period)
            return (
              <PlayerRow
              sleeperLeagueId={sleeperLeagueId}
                key={e.playerId}
                rank={e.rank}
                sport={spotlight.sport}
                playerId={e.playerId}
                name={e.name}
                position={e.position}
                team={e.team}
                points={played ? e.points : undefined}
                pointsPrefix={played ? periodPrefix(spotlight.period) : undefined}
                game={played ? { opponent: e.opponent, isAway: e.isAway } : undefined}
                ownership={e.ownership}
                metaExtra={`${e.addCount.toLocaleString('en-US')} adds`}
                note={note}
              />
            )
          })}
        />
      )}
      {t.omittedUnknownPlayers > 0 && (
        <p className="muted small">{t.omittedUnknownPlayers} more players we don't have details for.</p>
      )}
      <p className="muted small lh-spot-credit">
        Most added across all Sleeper leagues, last {t.lookbackHours} hours · Trending data from Sleeper
      </p>
    </Section>
  )
}

function RookieWatch({ sleeperLeagueId, spotlight, active, idPrefix }: { sleeperLeagueId: string; spotlight: PlayerSpotlightApplicable; active: boolean; idPrefix: string }) {
  const section = spotlight.rookieWatch
  const period = spotlight.period
  return (
    <Section
      tabKey="rookies"
      idPrefix={idPrefix}
      active={active}
      title={period ? `Rookie watch · ${periodLabel(period)}` : 'Rookie watch'}
      tag={notFinalTag(period)}
    >
      {section.entries.length === 0 ? (
        <p className="muted small">
          {section.unavailable === 'NO_ROOKIE_PLAYED' && period
            ? `No rookies played ${period.kind === 'NIGHT' ? `on ${periodLabel(period)}` : `in ${periodLabel(period)}`}.`
            : emptyText(spotlight, section.unavailable)}
        </p>
      ) : (
        <Rows
          rows={section.entries.map((e, i) => (
            <PlayerRow
              sleeperLeagueId={sleeperLeagueId}
              key={e.playerId}
              rank={i + 1}
              sport={spotlight.sport}
              playerId={e.playerId}
              name={e.name}
              position={e.position}
              team={e.team}
              points={e.points}
              game={{ opponent: e.opponent, isAway: e.isAway }}
              ownership={e.ownership}
            />
          ))}
        />
      )}
    </Section>
  )
}

// --- shared row ---------------------------------------------------------------

export type PlayerRowProps = {
  /** Where the name links (a league, never defaulted). */
  sleeperLeagueId: string
  rank: number
  sport: Sport
  playerId: string
  name: string
  position: string | null
  team: string | null
  /** Exact points, shown beside the name (FR-015). Omit for a non-game: never print 0 for one. */
  points?: number
  /** Names which period the points are for, e.g. "Wk 3". */
  pointsPrefix?: string
  /** Pass when a game was played: `opponent` null renders "opponent unknown". */
  game?: { opponent: string | null | undefined; isAway: boolean | null | undefined }
  ownership: SpotlightOwnership
  /** Appended to the position/team/opponent line (e.g. a trending add count). */
  metaExtra?: string
  /** Outcome in words for a player with no score; sits inline after the name, never in a side column. */
  note?: string | null
}

export function opponentText(opponent: string | null | undefined, isAway: boolean | null | undefined): string {
  if (!opponent) return 'opponent unknown'
  return isAway ? `@ ${opponent}` : `vs ${opponent}`
}

export function PlayerRow({ sleeperLeagueId, rank, sport, playerId, name, position, team, points, pointsPrefix, game, ownership, metaExtra, note }: PlayerRowProps) {
  const meta = [position, team, game ? opponentText(game.opponent, game.isAway) : null, metaExtra].filter(Boolean).join(' · ')
  const owner = ownership.teamName ?? 'a team in this league'
  return (
    <li className={`lh-row lh-spot-row${ownership.isMe ? ' mine' : ''}`}>
      <span className="lh-pos">{rank}</span>
      <PlayerFace sport={sport} sleeperId={playerId} team={team} position={position ?? ''} name={name} size={32} />
      <span className="lh-spot-main">
        <span className="lh-spot-line">
          <span className="lh-name" title={name}>
            <PlayerLink sleeperLeagueId={sleeperLeagueId} sleeperPlayerId={playerId} sport={sport}>
              {name}
            </PlayerLink>
          </span>
          {points != null && (
            <span className="lh-pts">
              {pointsPrefix ? `${pointsPrefix} · ` : ''}
              {points.toFixed(2)} pts
            </span>
          )}
          {note && <span className="lh-spot-extra muted small">{note}</span>}
        </span>
        <span className="lh-spot-sub2 muted small">
          <span className="lh-spot-sub">{meta}</span>
          {ownership.rostered ? (
            <span className="lh-spot-owner">
              <Avatar avatarId={ownership.avatarId} seed={owner} label={owner} isMe={ownership.isMe} className="lh-spot-avatar" />
              <span className="lh-spot-owner-name">{owner}</span>
              {ownership.isMe && <span className="lh-yours">Yours</span>}
            </span>
          ) : (
            <span className="lh-fa">Free agent</span>
          )}
        </span>
      </span>
    </li>
  )
}

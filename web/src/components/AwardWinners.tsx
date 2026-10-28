import type { ReactNode } from 'react'
import Avatar from './Avatar'
import PlayerFace from './PlayerFace'
import PlayerLink from './PlayerLink'
import PersonName from './PersonName'
import type { Sport, Superlative, SuperlativeDetail, TrendingCoverage } from '../api'

/**
 * The winner lines of one award (specs/008-season-superlatives; extracted from pages/Superlatives.tsx by
 * specs/047-hall-of-fame T030 so the History page's past-season Awards shows the SAME rendering, not a second copy).
 *
 * Four states, none of them blank: the award is unavailable (its reason), it has no winner (its emptyReason), it is
 * player-headed (JABARI_SMITH_JR: `playerHolders`), or it names team holders with per-holder detail lines, a
 * coverage note and an expandable list. `leagueId` is the league season the player links land on.
 */
export default function AwardWinners({
  s,
  hue,
  sport,
  leagueId,
  throughWeek,
}: {
  s: Superlative
  hue: number
  sport: Sport
  leagueId: string
  throughWeek: number | null
}) {
  const isPlayerHeadedKind = s.kind === 'JABARI_SMITH_JR'
  const isEmpty = s.holders.length === 0 && s.playerHolders.length === 0
  return (
    <>
      {renderBody()}
      {/* Spec 051 FR-007: the coverage line prints whenever the card is available, holders or not -- an
          empty card ("nobody has chased the trending list twice yet") still owes the reader what was judged. */}
      {s.available && s.trendingCoverage && (
        <p className="muted small sl-coverage">{trendingCoverageLine(s.trendingCoverage, throughWeek, sport)}</p>
      )}
    </>
  )

  function renderBody() {
    return (
    <>
      {!s.available ? (
        <p className="muted small">{s.reason}</p>
      ) : isEmpty ? (
        <p className="muted small">{s.emptyReason}</p>
      ) : isPlayerHeadedKind ? (
        <>
          <div className="sl-holders">
            {s.playerHolders.map((ph) => {
              const addRows = s.detail.filter(
                (d): d is Extract<SuperlativeDetail, { type: 'ADD' }> =>
                  d.type === 'ADD' && d.playerId === ph.playerId,
              )
              return (
                <div className="sl-holder sl-player-holder" key={ph.playerId}>
                  <span className="sl-holder-top">
                    <span className="sl-holder-name">
                      <PlayerFace sport={sport} sleeperId={ph.playerId} team={ph.team} position={ph.position ?? ''} name={ph.playerName} size={20} />
                      <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={ph.playerId} sport={sport}>
                        {ph.playerName}
                      </PlayerLink>
                      {ph.position ? ` (${ph.position})` : ''}
                    </span>
                  </span>
                  <span className="sl-holder-detail muted small">
                    {/* No team shown when null, rather than "Free agent": that
                        phrase already labels FA adds in the rows just below. */}
                    {ph.team ? `${ph.team} · ` : ''}{ph.adds} {ph.adds === 1 ? 'add' : 'adds'} by{' '}
                    {ph.distinctTeams} {ph.distinctTeams === 1 ? 'team' : 'teams'}
                  </span>
                  <ul className="sl-add-list">
                    {addRows.map((d, i) => (
                      <li className="sl-add-row" key={i}>
                        <Avatar
                          avatarId={d.avatarId}
                          seed={d.teamName}
                          label={d.teamName}
                          hue={hue}
                          className="sl-avatar"
                        />
                        <span className="sl-add-team">{d.teamName}</span>
                        <span className="sl-add-meta">
                          <span className="muted small">{`week ${d.week}`}</span>
                          <span className="muted small">{formatAddType(d)}</span>
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )
            })}
          </div>
        </>
      ) : (
        <>
          <div className="sl-holders">
            {s.holders.map((h) => {
              const holderLines = holderDetailLines(s, h.rosterId, throughWeek, leagueId, sport)
              return (
                <div className="sl-holder" key={h.rosterId}>
                  <span className="sl-holder-top">
                    <Avatar
                      avatarId={h.avatarId}
                      seed={String(h.managerId ?? h.rosterId)}
                      label={h.teamName}
                      hue={hue}
                      className="sl-avatar"
                    />
                    <span className="sl-holder-name"><PersonName teamName={h.teamName} username={h.username} /></span>
                  </span>
                  {/* Each mapped to its own line, not joined -- WAIVER_WIRE_WARRIOR
                      returns a total line plus up to three pickup lines (T040);
                      every other kind still returns exactly one, so this renders
                      identically for them. */}
                  {holderLines.map((line, li) => (
                    <span className="sl-holder-detail muted small" key={li}>
                      {line}
                    </span>
                  ))}
                </div>
              )
            })}
          </div>

          {s.coverage && (
            <p className="muted small sl-coverage">
              {s.coverage.weeksCovered} of {s.coverage.weeksCovered + s.coverage.weeksExcluded} weeks
              {s.coverage.reasons.length > 0 ? ` — ${s.coverage.reasons.join('; ')}` : ''}
            </p>
          )}

          {expandableRows(s).length > 0 && <DetailList s={s} />}
        </>
      )}
    </>
    )
  }
}

/**
 * Spec 051 FR-007 / F9: names what the count did NOT cover, so a low number can't be read as "chased nothing".
 * The date is the viewer's local one (F13). Team defenses are an NFL-only exclusion, so the clause is dropped for NBA.
 */
function trendingCoverageLine(c: TrendingCoverage, throughWeek: number | null, sport: Sport): string {
  const since = new Date(c.judgingSinceAt).toLocaleDateString()
  const through = throughWeek != null ? ` · pickups through week ${throughWeek}` : ''
  const defenses = sport === 'nfl' ? ', team defenses excluded' : ''
  return (
    `Judged since ${since} · ${c.judged} of ${c.totalPickups} pickups judged, ${c.notJudged} not ` +
    `(before trending history began or no list within a day)${through}${defenses}`
  )
}

/**
 * The figure this holder's own row(s) contributed -- the week, and for a
 * game the opponent, printed inline so the reader never has to expand
 * "Games" just to see FR-002's week (live-check finding, 2026-09-23).
 * Ties (e.g. three CLOSEST_GAME holders) each get their own line rather than
 * one shared figure, since a tie can span different weeks and opponents.
 */
function holderDetailLines(
  s: Superlative,
  rosterId: number,
  throughWeek: number | null,
  leagueId: string,
  sport: Sport,
): ReactNode[] {
  const who = (id: string, name: string) => (
    <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={id} sport={sport}>
      {name}
    </PlayerLink>
  )
  const rows = s.detail.filter((d): d is Extract<SuperlativeDetail, { rosterId: number }> =>
    'rosterId' in d && d.rosterId === rosterId,
  )
  if (s.kind === 'HIGHEST_WEEK' || s.kind === 'LOWEST_WEEK') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'WEEK_SCORE' }> => d.type === 'WEEK_SCORE')
      .map((d) => `${d.points.toFixed(2)} points · week ${d.week}`)
  }
  if (s.kind === 'BIGGEST_BLOWOUT' || s.kind === 'CLOSEST_GAME') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'GAME' }> => d.type === 'GAME')
      .map((d) => `${d.margin.toFixed(2)} points over ${d.opponentTeamName} · week ${d.week}`)
  }
  if (s.kind === 'CLOSE_WINS' || s.kind === 'CLOSE_LOSSES') {
    const weeks = rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'GAME' }> => d.type === 'GAME')
      .map((d) => d.week)
    return weeks.length > 0 ? [weeksLabel(weeks)] : []
  }
  // US2 (T035): the reading itself IS the figure -- "2.40 more wins than
  // their scores earned" -- so it belongs inline, with the span it covers.
  if (s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'LUCK' }> => d.type === 'LUCK')
      .map((d) => `${d.reading} · ${weekSpan(d.fromWeek, d.throughWeek)}`)
  }
  if (s.kind === 'MOST_BENCH_POINTS') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'BENCH_TOTAL' }> => d.type === 'BENCH_TOTAL')
      .map((d) => {
        const worst = d.biggestWeek
          ? ` · worst: week ${d.biggestWeek.week} (${d.biggestWeek.pointsLeft.toFixed(2)})`
          : ''
        return `${d.pointsLeft.toFixed(2)} points left on the bench · ${weekSpan(d.fromWeek, d.throughWeek)}${worst}`
      })
  }
  // US3 (T040): a total line, then up to three pickup lines (the backend
  // already limits detail to top 3 per holder) -- not folded into one line,
  // since a reader scanning pickups wants each player on its own row.
  if (s.kind === 'WAIVER_WIRE_WARRIOR') {
    const pickups = rows.filter(
      (d): d is Extract<SuperlativeDetail, { type: 'PICKUP' }> => d.type === 'PICKUP',
    )
    if (pickups.length === 0) return []
    const total: string[] =
      s.value != null
        ? [`${s.value.toFixed(2)} points from waiver pickups · weeks 1–${throughWeek ?? '?'}`]
        : []
    const pickupLines = pickups.map((d) => {
      const source = d.addType === 'WAIVER' ? 'waivers' : 'free agency'
      const weeksWord = d.startedWeeks.length === 1 ? 'week' : 'weeks'
      return (
        <>
          {who(d.playerId, d.playerName)}
          {` (${d.position}) — ${d.points.toFixed(2)} pts, added week ${d.addedWeek} ` +
            `off ${source}, started ${weeksWord} ${d.startedWeeks.join(', ')}`}
        </>
      )
    })
    return [...total, ...pickupLines]
  }
  // Spec 051 (T018): one line per counted pickup, the player linked like the other pickup lines.
  if (s.kind === 'TRENDING_TONY') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'TRENDING_PICKUP' }> => d.type === 'TRENDING_PICKUP')
      .map((d) => (
        <>
          {who(d.playerId, d.playerName)}
          {` · #${d.trendingRank} trending · week ${d.week} · ${d.addType === 'WAIVER' ? 'Waiver' : 'Free agent'}`}
        </>
      ))
  }
  // US4 (T049): the total, then each top absence -- "estimated" said on both
  // the total and every player line (FR-015), never left to be assumed once.
  if (s.kind === 'JOEL_EMBIID') {
    const absences = rows.filter(
      (d): d is Extract<SuperlativeDetail, { type: 'ABSENCE' }> => d.type === 'ABSENCE',
    )
    if (absences.length === 0) return []
    // The total ("62.40 estimated points lost") is the row's headline figure now
    // (the stat column, via standingFigure), so it is not printed a second time here.
    const absenceLines = absences.map(
      (d) => (
        <>
          {who(d.playerId, d.playerName)}
          {` (${d.position}) — ${d.weeksMissed} ${d.weeksMissed === 1 ? 'week' : 'weeks'} missed · ` +
            `${d.pointsPerGame.toFixed(2)} pts/game · ${d.estimatedPointsLost.toFixed(2)} pts lost ` +
            `(sat ${d.gamesMissed} ${d.gamesMissed === 1 ? 'game' : 'games'}, estimated)`}
        </>
      ),
    )
    return absenceLines
  }
  // US6 (T060): one line per player named -- a team can be on the list for
  // more than one player, each with its own source and reason. Plain text
  // only, never dangerouslySetInnerHTML (FR-016's reason is unmoderated
  // commissioner-entered text). The weeks it counted for THIS team (FR-002)
  // print on both sources, not just SUSPENDED -- a commissioner entry is
  // just as much a "which weeks" fact (live-check finding, 2026-09-23).
  if (s.kind === 'UNETHICAL') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'CONDUCT' }> => d.type === 'CONDUCT')
      .map((d) => {
        const weeksPart = d.weeks.length > 0 ? ` · ${weeksLabel(d.weeks)}` : ''
        const label =
          d.source === 'SUSPENDED'
            ? `Suspended${weeksPart}`
            : `Commissioner's call: ${d.reason ?? ''}${weeksPart}`
        return (
          <>
            {who(d.playerId, d.playerName)}
            {` — ${label}`}
          </>
        )
      })
  }
  return []
}

/** "week 3" for a single week, "weeks 3–5" for a run -- never "weeks 3–3". */
function weekSpan(from: number, to: number): string {
  return from === to ? `week ${from}` : `weeks ${from}–${to}`
}

/**
 * "week 2" for one week, "weeks 3–5" for a consecutive run, "weeks 3, 7, 9"
 * for scattered ones, and "weeks 3–5, 9" when both shapes appear -- a raw
 * comma-joined list of every captured week reads as noise once a suspension
 * spans more than two or three weeks.
 */
function weeksLabel(weeks: number[]): string {
  const sorted = [...weeks].sort((a, b) => a - b)
  const ranges: string[] = []
  let start = sorted[0]
  let prev = sorted[0]
  for (let i = 1; i <= sorted.length; i++) {
    const cur = sorted[i]
    if (cur === prev + 1) {
      prev = cur
      continue
    }
    ranges.push(start === prev ? `${start}` : `${start}–${prev}`)
    start = cur
    prev = cur
  }
  return `${sorted.length === 1 ? 'week' : 'weeks'} ${ranges.join(', ')}`
}

/** JABARI_SMITH_JR (US7): "Waiver ($N)" for a bid, "Waiver" for a null bid, "Free agent" for FA. $0 is a real bid and shows "$0", never confused with a null bid. */
function formatAddType(d: Extract<SuperlativeDetail, { type: 'ADD' }>): string {
  if (d.addType === 'FREE_AGENT') return 'Free agent'
  if (d.faabBid == null) return 'Waiver'
  return `Waiver ($${d.faabBid})`
}

function DetailList({ s }: { s: Superlative }) {
  const rows = expandableRows(s)
  const summary = s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST' ? 'Swing weeks' : 'Games'
  return (
    // Stop clicks/keys here so opening "Games" doesn't bubble to the card's
    // click-to-open-standings handler (amendment 12).
    <details className="sl-detail" onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      <summary className="muted small">{summary}</summary>
      <ul className="sl-detail-list">
        {rows.map((r, i) => (
          <li key={i}>{r}</li>
        ))}
      </ul>
    </details>
  )
}

/**
 * The expandable list's rows. Single-event and reading kinds already print
 * their week/reading on the holder line itself (holderDetailLines above), so
 * this only ever adds something NEW: WEEK_SCORE/GAME rows for the kinds that
 * don't yet fold their figure into the holder line, and swing weeks for luck
 * (which nest inside the LUCK row rather than being their own detail rows).
 * MOST_BENCH_POINTS has nothing beyond its inline summary, so it contributes
 * no rows -- the card never opens an empty "Games" list (never blank).
 */
function expandableRows(s: Superlative): string[] {
  if (s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST') {
    return s.detail
      .filter((d): d is Extract<SuperlativeDetail, { type: 'LUCK' }> => d.type === 'LUCK')
      .flatMap((d) =>
        d.swingWeeks.map(
          (w) =>
            `Week ${w.week}: ${w.result === 'WON' ? 'won' : 'lost'} with ${w.points.toFixed(2)} ` +
            `(${ordinal(w.weeklyRank)} that week) vs ${w.opponent}`,
        ),
      )
  }
  if (s.kind === 'MOST_BENCH_POINTS' || s.kind === 'WAIVER_WIRE_WARRIOR'
      || s.kind === 'JABARI_SMITH_JR' || s.kind === 'JOEL_EMBIID' || s.kind === 'UNETHICAL'
      || s.kind === 'TRENDING_TONY') {
    // Already fully represented on the holder line(s) above -- nothing new
    // to say behind an expandable panel, so the card never opens an empty one.
    return []
  }
  return s.detail.map(detailLine).filter((line) => line.length > 0)
}

function detailLine(d: SuperlativeDetail): string {
  switch (d.type) {
    case 'WEEK_SCORE':
      return `Week ${d.week}: ${d.points.toFixed(2)}`
    case 'GAME':
      return `Week ${d.week} vs ${d.opponentTeamName}: ${d.points.toFixed(2)}–${d.opponentPoints.toFixed(2)} (margin ${d.margin.toFixed(2)})`
    default:
      return ''
  }
}

/** "1st"/"2nd"/"3rd"/"4th…", matching ExpectedWins.tsx's own helper. */
function ordinal(n: number): string {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0])
}


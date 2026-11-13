import { Fragment, useMemo, useState, type ReactNode } from 'react'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult, HistoricalWeek } from '../api'
import {
  hindsightSeasonVerdict,
  isOldFormat,
  ordinal,
  outcomeHeadline,
  pointsPair,
  pts1,
  scheduleNote,
  sportOf,
  teamName,
} from '../historicalCopy'
import HistoricalBracket from './HistoricalBracket'

type Props = {
  game: HistoricalGameState
  result: HistoricalResult
  /** Spec 064: rendered right after the finish panel (the room passes the award panel here). */
  afterFinish?: ReactNode
}

function nameOf(cards: Map<number, HistoricalPlayerCard>, id: number): string {
  return cards.get(id)?.name ?? `Player ${id}`
}

const ROUND_WORDS: Record<string, string> = { ROUND_ONE: 'round one', SEMIFINAL: 'semifinal', FINAL: 'final' }

/** The bust and the passed-on steal. On a playoff-format page these lead the page (spec 063, "Queued for later"). */
function Callouts({ game, result }: Props) {
  const bust = result.biggestBust
  const steal = result.bestStealPassedOn
  return (
    <div className="hist-callouts">
      <div className="hist-callout hist-callout-bust">
        <h3>Biggest bust</h3>
        {bust ? (
          <p>
            <strong>{bust.player.name}</strong>, taken {ordinal(bust.pickNo)} overall, finished {ordinal(bust.seasonValueRank)} of{' '}
            {game.teams * game.rounds} drafted players on the season ({pts1(bust.seasonValue)} points).
          </p>
        ) : (
          <p className="muted">None: every player you took finished at or above his draft position.</p>
        )}
      </div>
      <div className="hist-callout hist-callout-steal">
        <h3>Best steal you passed on</h3>
        {steal ? (
          <p>
            At pick {steal.pickNo} you took <strong>{steal.taken.name}</strong> ({pts1(steal.takenValue)} points). Still there:{' '}
            <strong>{steal.passedOn.name}</strong> ({pts1(steal.passedOnValue)} points), {pts1(steal.passedOnValue - steal.takenValue)} more.
          </p>
        ) : (
          <p className="muted">Nobody left on the board outscored the player you took.</p>
        )}
      </div>
    </div>
  )
}

/**
 * Spec 065: every pick of yours, "drafted as RB2, finished as RB1". Lower is better for both, so the change is
 * drafted minus finished: positive means he finished ahead of where he was taken. The direction is said in a
 * word as well as a colour.
 */
function PickFinishes({ game, result }: Props) {
  const rows = result.pickFinishes
  if (!rows || rows.length === 0) return null
  const nfl = sportOf(game) === 'NFL'
  const seasonWeeks = result.format?.seasonWeeks ?? null
  return (
    <section className="panel hist-pickfin" aria-label="Your picks, drafted and finished">
      <h2>Where you drafted them, where they finished</h2>
      <p className="muted small">
        Drafted as: the order players at his position came off the board in this draft. Finished as: his rank at that
        position among every player on the board, drafted or not, by{' '}
        {nfl ? 'fantasy points over the whole real season' : "this game's points over the whole real season (each week's best single game)"}.
      </p>
      <div className="hist-table-wrap">
        <table className="hist-table">
          <thead>
            <tr>
              <th className="num">Pick</th>
              <th>Player</th>
              <th>Drafted as</th>
              <th>Finished as</th>
              <th>Change</th>
              <th className="num">Points</th>
              <th className="num">Weeks played</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const change = r.draftedPositionRank - r.finishPositionRank
              const round = Math.ceil(r.pickNo / game.teams)
              return (
                <tr key={r.pickNo}>
                  <td className="num">
                    {r.pickNo}
                    <span className="muted small"> · round {round}</span>
                  </td>
                  <td>{r.player.name}</td>
                  <td>
                    {r.position}
                    {r.draftedPositionRank}
                  </td>
                  <td>
                    <strong>
                      {r.position}
                      {r.finishPositionRank}
                    </strong>
                    <span className="muted small"> of {r.positionPlayers}</span>
                  </td>
                  <td>
                    {change > 0 ? (
                      <span className="hist-change up">
                        <span aria-hidden="true">▲</span> {change} better
                      </span>
                    ) : change < 0 ? (
                      <span className="hist-change down">
                        <span aria-hidden="true">▼</span> {-change} worse
                      </span>
                    ) : (
                      <span className="hist-change">Same spot</span>
                    )}
                  </td>
                  <td className="num">{pts1(r.seasonValue)}</td>
                  <td className="num">
                    {r.weeksPlayed}
                    {seasonWeeks != null ? <span className="muted small"> of {seasonWeeks}</span> : null}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/**
 * The greedy hindsight roster, pick by pick, beside the player you took at the same pick. The picks have been in
 * every stored result since spec 053, so this draws for old games too; only "would it have won" needs a result
 * that carries `hindsightSeason`.
 */
function HindsightRoster({ game, result }: Props) {
  const h = result.hindsight
  if (!h.picks || h.picks.length === 0) return null
  const season = result.hindsightSeason ?? null
  const mine = new Map(game.picks.filter((p) => p.draftSlot === game.userSlot).map((p) => [p.pickNo, p.player]))
  // Your own players' season points ride on pickFinishes; a result stored before it has no number to show.
  const myPoints = new Map((result.pickFinishes ?? []).map((f) => [f.pickNo, f.seasonValue]))
  const showMyPoints = myPoints.size > 0
  return (
    <div className="hist-hindsight">
      <h3>The hindsight roster</h3>
      <p className="muted small">
        At each of your picks, the player still on the board who added the most to this roster's season total. Points are each player's
        full-season total.
      </p>
      <div className="hist-table-wrap">
        <table className="hist-table">
          <thead>
            <tr>
              <th className="num">Pick</th>
              <th>Hindsight took</th>
              <th className="num">Points</th>
              <th>You took</th>
              {showMyPoints && <th className="num">Points</th>}
            </tr>
          </thead>
          <tbody>
            {h.picks.map((p) => {
              const took = mine.get(p.pickNo) ?? null
              const same = took != null && took.id === p.player.id
              return (
                <tr key={p.pickNo}>
                  <td className="num">
                    {p.pickNo}
                    <span className="muted small"> · round {Math.ceil(p.pickNo / game.teams)}</span>
                  </td>
                  <td>
                    <strong>{p.player.name}</strong>
                    <span className="muted small"> · {p.player.positions.join('/')}</span>
                  </td>
                  <td className="num">{pts1(p.seasonValue)}</td>
                  <td>
                    {took ? took.name : ''}
                    {same && <span className="hist-change up hist-same-pick">Same pick</span>}
                  </td>
                  {showMyPoints && <td className="num">{myPoints.has(p.pickNo) ? pts1(myPoints.get(p.pickNo)!) : ''}</td>}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <h3>Would it have won?</h3>
      {season ? (
        <>
          <p className={`hist-verdict${season.finish === 1 ? ' good' : ''}`}>{hindsightSeasonVerdict(season, game.teams)}</p>
          <p className="muted small">
            The same schedule with this roster in your seat. It was picked for the most points over the season, not the most wins.
            {season.takenFromBots > 0
              ? ` ${season.takenFromBots} of its players ${season.takenFromBots === 1 ? 'was' : 'were'} drafted later by another team. That team loses ${season.takenFromBots === 1 ? 'him' : 'them'} here and gets the best player left at his position by the pre-draft rankings. The other teams were not re-drafted.`
              : ' Every other team is as it was.'}
          </p>
        </>
      ) : (
        <p className="muted small">This game finished before the hindsight roster's season was replayed, so there is no answer for it.</p>
      )}
    </div>
  )
}

/** "Weeks 23 to 25 of the real season were not played." Null when every week was played. */
function unplayedLine(unused: number, firstUnused: number): string | null {
  if (unused <= 0) return null
  return unused === 1
    ? `Week ${firstUnused} of the real season was not played.`
    : `Weeks ${firstUnused} to ${firstUnused + unused - 1} of the real season were not played.`
}

/**
 * Spec 053 results: your finish, standings, the hindsight comparison (with its label, or "you beat
 * it"), bust and steal with the numbers, and the replay week by week with each starter's locked game.
 *
 * Spec 063: a result with a `format` also has a playoff bracket, a regular-season table with a playoff line and
 * playoff weeks in the week picker. A result without one (stored before playoffs) renders as it always did, plus
 * one line saying so. `isOldFormat` is the only place that question is asked.
 */
export default function HistoricalResults({ game, result, afterFinish }: Props) {
  const user = game.userSlot
  const standings = useMemo(() => [...result.standings].sort((a, b) => a.place - b.place), [result])
  const userRow = standings.find((s) => s.slot === user)
  const weeks = result.weeks
  const [weekNo, setWeekNo] = useState(weeks.length > 0 ? weeks[0].week : 0)
  const week = weeks.find((w) => w.week === weekNo)
  const cards = useMemo(() => {
    const m = new Map<number, HistoricalPlayerCard>()
    for (const p of game.picks) if (p.player) m.set(p.player.id, p.player)
    return m
  }, [game.picks])
  const lineupWeek = (result.lineups[String(user)] ?? []).find((l) => l.week === weekNo)
  const h = result.hindsight

  const old = isOldFormat(result)
  const format = result.format ?? null
  const playoffs = result.playoffs ?? null
  const finalOf = useMemo(() => new Map((result.finalPlaces ?? []).map((f) => [f.slot, f.place])), [result.finalPlaces])
  const roundOfWeek = useMemo(() => new Map((playoffs?.rounds ?? []).map((r) => [r.week, r])), [playoffs])
  const noPlayoffs = format != null && format.playoffWeeks.length === 0
  const lastPlayoffWeek = format && format.playoffWeeks.length > 0 ? Math.max(...format.playoffWeeks) : null
  const scheduleLine = format ? scheduleNote(format.regularSeasonWeeks, game.teams) : null
  const showFinishCol = !old && finalOf.size > 0

  const weekOption = (w: HistoricalWeek) => {
    const date = w.startDate ? ` (${w.startDate})` : ''
    if (old) return `${w.week}${date}`
    const r = roundOfWeek.get(w.week)
    return `${w.week}${r ? ` · ${ROUND_WORDS[r.name] ?? 'playoffs'}` : ''}${date}`
  }

  // A playoff week the fan's team did not play: say why, instead of showing an empty lineup.
  let noLineupWhy: string | null = null
  if (!old && !lineupWeek && week?.phase === 'PLAYOFF') {
    if (roundOfWeek.get(week.week)?.byeSlots.includes(user)) noLineupWhy = 'You had a bye this week.'
    else if (result.userOutcome === 'MISSED_PLAYOFFS') noLineupWhy = 'Your team did not make the playoffs, so there is no lineup this week.'
    else noLineupWhy = 'You were eliminated before this week.'
  }

  const unplayed = format ? unplayedLine(format.unusedWeeks, (lastPlayoffWeek ?? format.regularSeasonWeeks) + 1) : null

  return (
    <div className="hist-results">
      <section className="panel hist-finish" aria-label="Your finish">
        <p className="page-eyebrow">{game.season.label} season replayed</p>
        <h2 className={`hist-finish-title cond${!old && result.userOutcome === 'CHAMPION' ? ' champion' : ''}`}>
          {old ? `You finished ${ordinal(result.userFinish)} of ${game.teams}` : outcomeHeadline(result.userOutcome, result.userFinish, game.teams)}
        </h2>
        {userRow && (
          <p className="muted">
            {userRow.wins}-{userRow.losses}
            {userRow.ties > 0 ? `-${userRow.ties}` : ''} · {pts1(userRow.pointsFor)} points{old ? '' : ' in the regular season'}
          </p>
        )}
        {old && <p className="muted small">Played under the old format: no playoffs, finish is the standings rank.</p>}
        {noPlayoffs && <p className="muted small">This season was too short for playoffs.</p>}
      </section>
      {afterFinish}

      {!old && (
        <section className="panel hist-moments" aria-label="Your draft in two moments">
          <h2>Your draft in two moments</h2>
          <Callouts game={game} result={result} />
        </section>
      )}

      <section className="panel" aria-label="Your draft against hindsight">
        <h2>How your draft compares</h2>
        <div className="hist-compare">
          <div className="hist-stat">
            <span className="hist-stat-label muted small">{old ? 'You' : 'You, whole real season'}</span>
            <span className="hist-stat-num">{pts1(h.userPointsFor)}</span>
          </div>
          <div className="hist-stat">
            <span className="hist-stat-label muted small">{h.label}</span>
            <span className="hist-stat-num">{pts1(h.pointsFor)}</span>
          </div>
        </div>
        {h.userBeatIt ? (
          <p className="hist-verdict good">You beat the greedy hindsight draft.</p>
        ) : (
          <p className="hist-verdict">
            Hindsight finished {pts1(h.pointsFor - h.userPointsFor)} points ahead, with the other teams unchanged.
          </p>
        )}
        <HindsightRoster game={game} result={result} />
        {old && <Callouts game={game} result={result} />}
        {!result.pickFinishes && (
          <p className="muted small">
            This game finished before pick-by-pick finishes were saved, so there is no drafted-as and finished-as table for it.
          </p>
        )}
      </section>

      <PickFinishes game={game} result={result} />

      {!old && playoffs && <HistoricalBracket playoffs={playoffs} userSlot={user} />}

      {!old && format && (
        <p className="muted small hist-format-note">
          {unplayed ? `${unplayed} ` : ''}
          The draft comparisons below cover the whole real season.
        </p>
      )}

      <section className="panel" aria-label="Standings">
        <h2>{old ? 'Final standings' : 'Regular season'}</h2>
        {format && (
          <p className="muted small">
            {format.regularSeasonWeeks} {format.regularSeasonWeeks === 1 ? 'week' : 'weeks'}.
            {format.shortened ? ` The real season was only ${format.seasonWeeks} weeks long, so the regular season was cut short to fit the playoffs.` : ''}
            {scheduleLine ? ` ${scheduleLine}` : ''}
          </p>
        )}
        <table className="hist-table">
          <thead>
            <tr>
              <th className="num">{old ? 'Place' : 'Rank'}</th>
              <th>Team</th>
              <th className="num">W-L-T</th>
              <th className="num">Points for</th>
              {showFinishCol && <th className="num">Finished</th>}
            </tr>
          </thead>
          <tbody>
            {standings.map((s, i) => (
              <Fragment key={s.slot}>
                {playoffs && i === playoffs.seeds.length && (
                  <tr className="hist-cutline">
                    <td colSpan={showFinishCol ? 5 : 4}>Playoff line: the top {playoffs.seeds.length} advance</td>
                  </tr>
                )}
                <tr className={s.slot === user ? 'hist-me' : undefined} aria-current={s.slot === user ? 'true' : undefined}>
                  <td className="num">{s.place}</td>
                  <td>
                    {teamName(s.slot, user)}
                    <span className="muted small"> · drafted from slot {s.slot}</span>
                  </td>
                  <td className="num">
                    {s.wins}-{s.losses}-{s.ties}
                  </td>
                  <td className="num">{pts1(s.pointsFor)}</td>
                  {showFinishCol && <td className="num">{finalOf.has(s.slot) ? ordinal(finalOf.get(s.slot)!) : ''}</td>}
                </tr>
              </Fragment>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel" aria-label="Weekly results">
        <div className="panel-head">
          <h2>Week by week</h2>
          {weeks.length > 0 && (
            <label className="small muted">
              Week{' '}
              <select value={weekNo} onChange={(e) => setWeekNo(Number(e.target.value))} aria-label="Week">
                {weeks.map((w) => (
                  <option key={w.week} value={w.week}>
                    {weekOption(w)}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        {week ? (
          <>
            {!old && roundOfWeek.get(week.week) && (
              <p className="muted small hist-week-round">
                {ROUND_WORDS[roundOfWeek.get(week.week)!.name]?.replace(/^./, (c) => c.toUpperCase())}
              </p>
            )}
            <ul className="hist-matchups">
              {week.matchups.map((m) => {
                // A playoff game's winner is the bracket's: a game level to the hundredth is decided by seed, which the
                // scores alone cannot show (CR-06).
                const pm = !old
                  ? roundOfWeek
                      .get(week.week)
                      ?.matchups.find(
                        (x) =>
                          (x.highSlot === m.homeSlot && x.lowSlot === m.awaySlot) || (x.highSlot === m.awaySlot && x.lowSlot === m.homeSlot),
                      )
                  : undefined
                const homeWon = pm ? pm.winnerSlot === m.homeSlot : m.homePoints > m.awayPoints
                const awayWon = pm ? pm.winnerSlot === m.awaySlot : m.awayPoints > m.homePoints
                const [homeText, awayText] = pointsPair(m.homePoints, m.awayPoints)
                return (
                  <li key={`${m.homeSlot}-${m.awaySlot}`} className={m.homeSlot === user || m.awaySlot === user ? 'hist-me' : undefined}>
                    <span>{teamName(m.homeSlot, user)}</span>
                    <b className={homeWon ? 'win' : ''}>{homeText}</b>
                    <span className="muted">vs</span>
                    <b className={awayWon ? 'win' : ''}>{awayText}</b>
                    <span>{teamName(m.awaySlot, user)}</span>
                    {pm?.decidedBySeed && (
                      <span className="muted small hist-matchup-note">
                        Level on points. {teamName(pm.winnerSlot, user)} advanced as the better seed.
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
          </>
        ) : (
          <p className="muted">No weeks were played.</p>
        )}
        {noLineupWhy && <p className="hist-bye">{noLineupWhy}</p>}
        {lineupWeek && (
          <>
            <h3>Your lineup, week {lineupWeek.week}</h3>
            <p className="muted small">
              {sportOf(game) === 'NFL'
                ? "Each starter counts his score for the week; your best legal lineup is set for you."
                : 'Each starter counts his single best game of the week; the date is the game that was locked.'}
            </p>
            <table className="hist-table">
              <thead>
                <tr>
                  <th>Slot</th>
                  <th>Player</th>
                  <th>{sportOf(game) === 'NFL' ? 'Game' : 'Locked game'}</th>
                  <th className="num">Points</th>
                </tr>
              </thead>
              <tbody>
                {lineupWeek.starters.map((s, i) => (
                  <tr key={`${s.slot}-${i}`}>
                    <td>{s.slot}</td>
                    <td>{nameOf(cards, s.playerId)}</td>
                    <td>{s.gameDate ?? (sportOf(game) === 'NFL' ? `Week ${lineupWeek.week}` : 'did not play')}</td>
                    <td className="num">{pts1(s.points)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </section>
    </div>
  )
}

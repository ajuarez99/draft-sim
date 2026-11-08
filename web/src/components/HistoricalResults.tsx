import { useMemo, useState } from 'react'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult } from '../api'
import { ordinal, pts1, sportOf, teamName } from '../historicalCopy'

type Props = { game: HistoricalGameState; result: HistoricalResult }

function nameOf(cards: Map<number, HistoricalPlayerCard>, id: number): string {
  return cards.get(id)?.name ?? `Player ${id}`
}

/**
 * Spec 053 results: your finish, standings, the hindsight comparison (with its label, or "you beat
 * it"), bust and steal with the numbers, and the replay week by week with each starter's locked game.
 */
export default function HistoricalResults({ game, result }: Props) {
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
  const bust = result.biggestBust
  const steal = result.bestStealPassedOn

  return (
    <div className="hist-results">
      <section className="panel hist-finish" aria-label="Your finish">
        <p className="page-eyebrow">{game.season.label} season replayed</p>
        <h2 className="hist-finish-title cond">
          You finished {ordinal(result.userFinish)} of {game.teams}
        </h2>
        {userRow && (
          <p className="muted">
            {userRow.wins}-{userRow.losses}
            {userRow.ties > 0 ? `-${userRow.ties}` : ''} · {pts1(userRow.pointsFor)} points
          </p>
        )}
      </section>

      <section className="panel" aria-label="Standings">
        <h2>Final standings</h2>
        <table className="hist-table">
          <thead>
            <tr>
              <th className="num">Place</th>
              <th>Team</th>
              <th className="num">W-L-T</th>
              <th className="num">Points for</th>
            </tr>
          </thead>
          <tbody>
            {standings.map((s) => (
              <tr key={s.slot} className={s.slot === user ? 'hist-me' : undefined} aria-current={s.slot === user ? 'true' : undefined}>
                <td className="num">{s.place}</td>
                <td>
                  {teamName(s.slot, user)}
                  <span className="muted small"> · drafted from slot {s.slot}</span>
                </td>
                <td className="num">
                  {s.wins}-{s.losses}-{s.ties}
                </td>
                <td className="num">{pts1(s.pointsFor)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel" aria-label="Your draft against hindsight">
        <h2>How your draft compares</h2>
        <div className="hist-compare">
          <div className="hist-stat">
            <span className="hist-stat-label muted small">You</span>
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
        <div className="hist-callouts">
          <div className="hist-callout">
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
          <div className="hist-callout">
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
                    {w.week}
                    {w.startDate ? ` (${w.startDate})` : ''}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
        {week ? (
          <ul className="hist-matchups">
            {week.matchups.map((m) => (
              <li key={`${m.homeSlot}-${m.awaySlot}`} className={m.homeSlot === user || m.awaySlot === user ? 'hist-me' : undefined}>
                <span>{teamName(m.homeSlot, user)}</span>
                <b className={m.homePoints > m.awayPoints ? 'win' : ''}>{pts1(m.homePoints)}</b>
                <span className="muted">vs</span>
                <b className={m.awayPoints > m.homePoints ? 'win' : ''}>{pts1(m.awayPoints)}</b>
                <span>{teamName(m.awaySlot, user)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">No weeks were played.</p>
        )}
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

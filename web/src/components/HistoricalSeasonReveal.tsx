import { useEffect, useMemo, useRef, useState } from 'react'
import type { HistoricalGameState, HistoricalResult } from '../api'
import { ordinal, pts1, teamName } from '../historicalCopy'
import { recordText, seasonSteps, stepDelay, type PlayoffStep, type SeedStep, type WeekStep } from '../historicalReveal'

const ROUND_NAME: Record<PlayoffStep['name'], string> = { ROUND_ONE: 'Round one', SEMIFINAL: 'Semifinal', FINAL: 'Final' }

function seedLine(s: SeedStep, record: string, teams: number): string {
  const base = `Regular season over: ${record}, ${ordinal(s.place)} of ${teams}.`
  if (!s.playoffsHeld) return base
  return s.seed != null ? `${base} You are in the playoffs as the ${s.seed} seed.` : `${base} You missed the playoffs.`
}

function playoffLine(p: PlayoffStep, userSlot: number): string {
  if (p.bye) return `${ROUND_NAME[p.name]}: you had a bye.`
  const score = `${pts1(p.you ?? 0)} to ${pts1(p.them ?? 0)}`
  const who = teamName(p.opponentSlot ?? 0, userSlot)
  const how = p.decidedBySeed ? ' on the higher seed' : ''
  return p.won ? `${ROUND_NAME[p.name]}: you beat ${who}, ${score}${how}.` : `${ROUND_NAME[p.name]}: you lost to ${who}, ${score}${how}.`
}

/**
 * Spec 071: the season plays out before the results. Each regular-season week fills one square (W, L or T, a
 * letter as well as a colour) while the record counts up; then the seed, each playoff round the fan was in, and
 * the finish. "Skip to results" is there from the first frame. Nothing is announced week by week: a screen reader
 * gets the finish once, from the heading that takes focus at the end.
 */
export default function HistoricalSeasonReveal({
  game,
  result,
  onDone,
}: {
  game: HistoricalGameState
  result: HistoricalResult
  onDone: () => void
}) {
  const steps = useMemo(() => seasonSteps(game, result), [game, result])
  const weeks = steps.filter((s): s is WeekStep => s.kind === 'week')
  // How many steps are showing. The first week is on screen at once.
  const [shown, setShown] = useState(1)
  const finished = shown >= steps.length
  const endRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (finished) return
    const t = window.setTimeout(() => setShown((n) => n + 1), stepDelay(steps[shown - 1], weeks.length))
    return () => window.clearTimeout(t)
  }, [shown, finished, steps, weeks.length])

  useEffect(() => {
    if (finished) endRef.current?.focus()
  }, [finished])

  const visible = steps.slice(0, shown)
  const lastWeek = [...visible].reverse().find((s): s is WeekStep => s.kind === 'week') ?? null
  const seed = visible.find((s): s is SeedStep => s.kind === 'seed') ?? null
  const rounds = visible.filter((s): s is PlayoffStep => s.kind === 'playoff')
  const final = finished ? steps[steps.length - 1] : null
  const record = lastWeek ? recordText(lastWeek.wins, lastWeek.losses, lastWeek.ties) : '0-0'

  return (
    <section className="tm-reveal" aria-label={`The ${game.season.label} season`}>
      <p className="tm-reveal-kicker">The {game.season.label} season</p>
      <p className="tm-reveal-record" aria-hidden={final != null}>
        {record}
      </p>
      {weeks.length > 0 && (
        <ol className="tm-reveal-weeks" aria-label="Week by week">
          {weeks.map((w, i) => {
            const on = i < visible.filter((s) => s.kind === 'week').length
            return (
              <li
                key={w.week}
                className={`tm-reveal-week${on ? ` on ${w.outcome === 'W' ? 'win' : w.outcome === 'L' ? 'loss' : 'tie'}` : ''}`}
                aria-label={on ? `Week ${w.week}: ${w.outcome === 'W' ? 'won' : w.outcome === 'L' ? 'lost' : 'tied'}` : `Week ${w.week}: not played yet`}
              >
                {on ? w.outcome : ''}
              </li>
            )
          })}
        </ol>
      )}
      <p className="tm-reveal-line">
        {seed
          ? seedLine(seed, record, game.teams)
          : lastWeek
            ? `Week ${lastWeek.week}: you ${pts1(lastWeek.you)}, ${teamName(lastWeek.opponentSlot, game.userSlot)} ${pts1(lastWeek.them)}`
            : ''}
      </p>
      {rounds.length > 0 && (
        <ul className="tm-reveal-rounds">
          {rounds.map((p) => (
            <li key={p.name} className={p.won ? 'won' : 'lost'}>
              {playoffLine(p, game.userSlot)}
            </li>
          ))}
        </ul>
      )}
      {final && final.kind === 'final' && (
        <h2 ref={endRef} tabIndex={-1} className={`tm-reveal-final${final.champion ? ' champion' : ''}`}>
          {final.headline}
        </h2>
      )}
      <button type="button" className={finished ? 'tm-play' : 'tm-btn quiet'} onClick={onDone}>
        {finished ? 'See your results' : 'Skip to results'}
      </button>
    </section>
  )
}

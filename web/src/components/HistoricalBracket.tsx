import type { HistoricalPlayoffMatchup, HistoricalPlayoffs, HistoricalRound } from '../api'
import { pointsPair, teamName } from '../historicalCopy'

type Props = { playoffs: HistoricalPlayoffs; userSlot: number }

const ROUND_NAME: Record<HistoricalRound['name'], string> = {
  ROUND_ONE: 'Round one',
  SEMIFINAL: 'Semifinals',
  FINAL: 'Final',
}

/** Same words the table uses for the seed a team went in with. */
const seedLabel = (n: number) => `#${n}`

function Side({
  seed,
  slot,
  points,
  won,
  isFinal,
  userSlot,
}: {
  seed: number
  slot: number
  points: string
  won: boolean
  isFinal: boolean
  userSlot: number
}) {
  const me = slot === userSlot
  return (
    <li className={`hist-bk-side${won ? ' won' : ' lost'}${me ? ' me' : ''}`}>
      <span className="hist-bk-seed" title={`Seed ${seed}`}>
        {seedLabel(seed)}
      </span>
      <span className="hist-bk-team">{teamName(slot, userSlot)}</span>
      {/* The winner is marked by a word and a glyph as well as weight and tint, so colour is never the only cue. */}
      {won && (
        <span className="hist-bk-win">
          <span aria-hidden="true">✔ </span>
          {isFinal ? 'Champion' : 'Advances'}
        </span>
      )}
      {!won && <span aria-hidden="true" />}
      <b className="hist-bk-score">{points}</b>
    </li>
  )
}

function Matchup({ m, isFinal, userSlot }: { m: HistoricalPlayoffMatchup; isFinal: boolean; userSlot: number }) {
  const involvesMe = m.highSlot === userSlot || m.lowSlot === userSlot
  const [highText, lowText] = pointsPair(m.highPoints, m.lowPoints)
  return (
    <li className={`hist-bk-match${involvesMe ? ' me' : ''}${isFinal ? ' final' : ''}`}>
      <ul className="hist-bk-sides">
        <Side seed={m.highSeed} slot={m.highSlot} points={highText} won={m.winnerSlot === m.highSlot} isFinal={isFinal} userSlot={userSlot} />
        <Side seed={m.lowSeed} slot={m.lowSlot} points={lowText} won={m.winnerSlot === m.lowSlot} isFinal={isFinal} userSlot={userSlot} />
      </ul>
      {m.decidedBySeed && <p className="hist-bk-tie small">Level on points. The better seed advanced on a tie.</p>}
    </li>
  )
}

/**
 * Spec 063: the three-round playoff bracket. Round one lists the byes, then each round's matchups with both
 * scores; the user's team is marked everywhere it appears; the champion gets the biggest line on the page.
 * Columns stack below 720px (styles.css).
 */
export default function HistoricalBracket({ playoffs, userSlot }: Props) {
  const rounds = [...playoffs.rounds].sort((a, b) => a.round - b.round)
  const champ = playoffs.championSlot
  const seedOf = (slot: number) => playoffs.seeds.find((s) => s.slot === slot)?.seed
  const champIsMe = champ === userSlot
  return (
    <section className="panel hist-bracket" aria-label="Playoff bracket">
      <div className={`hist-champ${champIsMe ? ' me' : ''}`}>
        <span className="hist-champ-trophy" aria-hidden="true">
          ★
        </span>
        <div>
          <p className="hist-champ-label small">Champion</p>
          <p className="hist-champ-name cond">
            {champIsMe ? 'You won it all' : `${teamName(champ, userSlot)} won it all`}
            {seedOf(champ) != null && <span className="muted small"> · seed {seedOf(champ)}</span>}
          </p>
        </div>
      </div>
      <div className="hist-bk-cols">
        {rounds.map((r) => {
          const isFinal = r.name === 'FINAL'
          return (
            <div key={r.round} className="hist-bk-col">
              <h3 className="hist-bk-head">
                {ROUND_NAME[r.name] ?? `Round ${r.round}`} <span className="muted small">Week {r.week}</span>
              </h3>
              {r.byeSlots.length > 0 && (
                <ul className="hist-bk-byes">
                  {r.byeSlots.map((slot) => (
                    <li key={slot} className={slot === userSlot ? 'me' : undefined}>
                      <span className="hist-bk-seed">{seedOf(slot) != null ? seedLabel(seedOf(slot)!) : ''}</span>
                      <span className="hist-bk-team">{teamName(slot, userSlot)}</span>
                      <span className="muted small">bye</span>
                    </li>
                  ))}
                </ul>
              )}
              <ul className="hist-bk-matches">
                {r.matchups.map((m) => (
                  <Matchup key={`${m.highSlot}-${m.lowSlot}`} m={m} isFinal={isFinal} userSlot={userSlot} />
                ))}
              </ul>
            </div>
          )
        })}
      </div>
    </section>
  )
}

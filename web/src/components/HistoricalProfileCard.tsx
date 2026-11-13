import { Link } from 'react-router-dom'
import type { HistoricalProfile } from '../api'
import { BadgeTile } from './HistoricalAwardPanel'

function earnedOn(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/**
 * Spec 064: the level card and the trophy case on the Time Machine landing page. The bar and the sentence beside
 * it use the same two numbers (XP into the level, the level's size), so they cannot disagree.
 */
export default function HistoricalProfileCard({ profile }: { profile: HistoricalProfile }) {
  const size = Math.max(profile.nextLevelXp - profile.levelStartXp, 1)
  const into = Math.min(Math.max(profile.xp - profile.levelStartXp, 0), size)
  const t = profile.totals
  const earned = profile.badges.filter((b) => b.unlocked).length

  return (
    <>
      <section className="panel ach-level" aria-label="Your level">
        <div className="ach-level-head">
          <span className="ach-level-num cond" aria-hidden="true">
            {profile.level}
          </span>
          <div>
            <p className="page-eyebrow">Level {profile.level}</p>
            <h2 className="ach-level-name">{profile.levelName}</h2>
          </div>
        </div>
        <div className="ach-bar-row">
          <div
            className="ach-bar"
            role="progressbar"
            aria-label={`Progress to level ${profile.level + 1}`}
            aria-valuemin={0}
            aria-valuemax={size}
            aria-valuenow={into}
          >
            <span style={{ width: `${(into / size) * 100}%` }} />
          </div>
          <span className="mono small">
            {into} of {size} XP to level {profile.level + 1}
          </span>
        </div>
        <dl className="ach-totals">
          <div>
            <dt className="muted small">Games</dt>
            <dd className="mono">{t.games}</dd>
          </div>
          <div>
            <dt className="muted small">Weekly wins</dt>
            <dd className="mono">{t.weeklyWins}</dd>
          </div>
          <div>
            <dt className="muted small">Playoff wins</dt>
            <dd className="mono">{t.playoffWins}</dd>
          </div>
          <div>
            <dt className="muted small">Titles</dt>
            <dd className="mono">{t.titles}</dd>
          </div>
        </dl>
        {t.gamesFromSavedResults > 0 && (
          <p className="muted small">{t.gamesFromSavedResults} {t.gamesFromSavedResults === 1 ? 'game' : 'games'} counted from saved results</p>
        )}
        {profile.settleFailures > 0 && <p className="muted small">{profile.settleFailures} {profile.settleFailures === 1 ? 'game' : 'games'} could not be counted</p>}
      </section>

      <section className="panel" aria-label="Trophy case">
        <h2>Trophy case</h2>
        <p className="muted small">
          {earned} of {profile.badges.length} earned
        </p>
        <ul className="ach-grid">
          {profile.badges.map((b) => (
            <BadgeTile
              key={b.code}
              badge={b}
              footer={
                b.unlocked ? (
                  <>
                    {b.unlockedAt && <span className="muted"> · {earnedOn(b.unlockedAt)}</span>}
                    {b.gameId != null && (
                      <>
                        {' · '}
                        <Link to={`/history/${b.gameId}`}>See the game</Link>
                      </>
                    )}
                  </>
                ) : undefined
              }
            />
          ))}
        </ul>
      </section>
    </>
  )
}

import { useCallback, useEffect, useState } from 'react'
import {
  clearScoreOverride,
  getScoreOverride,
  saveScoreOverride,
  type ScoreOverrideTeam,
  type ScoreOverrideView,
} from '../api'
import CommissionerHonourNote from './CommissionerHonourNote'

/**
 * "239", "239.5", "239.25", ".5" -> a number; anything else (blank, negative, 3 decimals, text) -> null.
 * Never rounds: the server refuses more than two decimals too, so what is typed is what is stored.
 */
export function parsePoints(text: string): number | null {
  const t = text.trim()
  if (!/^(\d+(\.\d{1,2})?|\.\d{1,2})$/.test(t)) return null
  const n = Number(t)
  return Number.isFinite(n) && n < 1_000_000 ? n : null
}

/** The whole-week body, or the first problem in words. One entry per team; every box must be a valid number. */
export function buildScores(
  teams: Pick<ScoreOverrideTeam, 'rosterId' | 'teamName'>[],
  inputs: Record<number, string>,
): { scores: { rosterId: number; points: number }[] } | { error: string } {
  const scores: { rosterId: number; points: number }[] = []
  const blank = teams.filter((t) => (inputs[t.rosterId] ?? '').trim() === '')
  if (blank.length > 0) {
    return { error: `Enter a score for every team. Still blank: ${blank.map((t) => t.teamName).join(', ')}.` }
  }
  for (const t of teams) {
    const points = parsePoints(inputs[t.rosterId] ?? '')
    if (points == null) {
      return { error: `${t.teamName}: enter a score of 0 or more with at most two decimals.` }
    }
    scores.push({ rosterId: t.rosterId, points })
  }
  return { scores }
}

const SOURCE_WORDS = {
  COMMISSIONER: 'scores the commissioner entered',
  OFFICIAL: "Sleeper's official totals",
  FEED: "Sleeper's public feed",
} as const

function FeedWeeksNote({ weeks }: { weeks: number[] }) {
  if (weeks.length === 0) return null
  return (
    <p className="muted small cs-feed-weeks">
      {weeks.length === 1 ? `Week ${weeks[0]} still uses` : `Weeks ${weeks.join(', ')} still use`} the public feed, so a
      mismatch is expected.
    </p>
  )
}

type Props = {
  /** The Sleeper id of the season the REPORT shows, which can differ from the route's after a fallback. */
  leagueSleeperId: string
  week: number
  /** Called after a save or clear so the page can refetch its report. */
  onChanged: () => void
}

/**
 * The commissioner's score entry for one week (spec 057). Fetches its own view and renders nothing unless the
 * server says the caller can edit, so a member never sees controls that would only answer 403.
 */
export default function CommissionerScoresPanel({ leagueSleeperId, week, onChanged }: Props) {
  const [view, setView] = useState<ScoreOverrideView | null>(null)
  const [inputs, setInputs] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const adopt = useCallback((v: ScoreOverrideView) => {
    setView(v)
    const next: Record<number, string> = {}
    // Empty unless the commissioner already typed this score: the feed's number is not an entered one.
    for (const t of v.teams) next[t.rosterId] = t.overridePoints != null ? t.overridePoints.toFixed(2) : ''
    setInputs(next)
  }, [])

  useEffect(() => {
    let cancelled = false
    setView(null)
    setError(null)
    getScoreOverride(leagueSleeperId, week)
      .then((v) => {
        if (!cancelled) adopt(v)
      })
      .catch(() => {
        /* no view, no panel: a 404 or a network error is not something to show a reader here */
      })
    return () => {
      cancelled = true
    }
  }, [leagueSleeperId, week, adopt])

  if (!view || !view.canEdit) return null

  async function run(action: () => Promise<ScoreOverrideView>) {
    setBusy(true)
    setError(null)
    try {
      adopt(await action())
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function save() {
    if (!view) return
    const built = buildScores(view.teams, inputs)
    if ('error' in built) {
      setError(built.error)
      return
    }
    void run(() => saveScoreOverride(leagueSleeperId, week, built.scores))
  }

  const { totals, record } = view.checks
  const entered = view.source === 'COMMISSIONER'
  const hasOverrideRows = view.teams.some((t) => t.overridePoints != null)

  return (
    <details className="panel cs-panel">
      <summary className="cs-summary">
        Commissioner: enter week {week} scores
        <span className="cs-source small muted"> · in use: {SOURCE_WORDS[view.source]}</span>
      </summary>

      {!view.isFinal && (
        <p className="muted small">Week {week} is still being played. Scores can be entered once the week is final.</p>
      )}
      <p className="muted small cs-explain">
        Type each team&apos;s official score from the Sleeper app, for every team. They replace the public feed for
        this week and survive a re-ingest. Stored power rankings and playoff odds update on the commissioner&apos;s
        next recompute.
        {entered && view.enteredAt && ` Last entered ${new Date(view.enteredAt).toLocaleString()}.`}
      </p>

      <table className="cs-table">
        <thead>
          <tr>
            <th>Team</th>
            <th className="cs-num">Feed</th>
            <th className="cs-num">Official score</th>
          </tr>
        </thead>
        <tbody>
          {view.teams.map((t) => {
            const invalid = parsePoints(inputs[t.rosterId] ?? '') == null
            return (
              <tr key={t.rosterId}>
                <td>{t.teamName}</td>
                <td className="cs-num muted">{t.feedPoints.toFixed(2)}</td>
                <td className="cs-num">
                  <input
                    type="text"
                    inputMode="decimal"
                    value={inputs[t.rosterId] ?? ''}
                    placeholder={t.inUsePoints.toFixed(2)}
                    onChange={(e) => setInputs({ ...inputs, [t.rosterId]: e.target.value })}
                    aria-label={`${t.teamName} score for week ${week}`}
                    aria-invalid={invalid}
                    disabled={busy || !view.isFinal}
                  />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <div className="cs-checks">
        <h4 className="cs-h">Checks against Sleeper&apos;s own numbers</h4>
        {totals.status === 'UNAVAILABLE' ? (
          <p className="muted small">Season totals: no Sleeper total is stored to compare with yet.</p>
        ) : (
          <>
            <p className={totals.status === 'MATCH' ? 'small cs-ok' : 'small cs-bad'}>
              Season totals through week {totals.throughWeek}:{' '}
              {totals.status === 'MATCH' ? 'every team matches Sleeper.' : 'these do not match Sleeper.'}
            </p>
            {totals.status === 'MISMATCH' && <FeedWeeksNote weeks={totals.feedWeeks} />}
            {totals.status === 'MISMATCH' && (
              <ul className="cs-mismatch small">
                {totals.rows
                  .filter((r) => r.inUseTotal !== r.sleeperTotal)
                  .map((r) => (
                    <li key={r.rosterId}>
                      {r.teamName}: the app has {r.inUseTotal.toFixed(2)}, Sleeper has {r.sleeperTotal.toFixed(2)}
                    </li>
                  ))}
              </ul>
            )}
          </>
        )}
        {record.status === 'UNAVAILABLE' ? (
          <p className="muted small">Records: there is no Sleeper record to compare with for these weeks yet.</p>
        ) : (
          <>
            <p className={record.status === 'MATCH' ? 'small cs-ok' : 'small cs-bad'}>
              Win-loss records through week {record.throughWeek}:{' '}
              {record.status === 'MATCH' ? 'every team matches Sleeper.' : 'these do not match Sleeper.'}
            </p>
            {record.status === 'MISMATCH' && <FeedWeeksNote weeks={record.feedWeeks} />}
            {record.status === 'MISMATCH' && (
              <ul className="cs-mismatch small">
                {record.rows
                  .filter((r) => r.computed !== r.sleeper)
                  .map((r) => (
                    <li key={r.rosterId}>
                      {r.teamName}: the app counts {r.computed}, Sleeper has {r.sleeper}
                    </li>
                  ))}
              </ul>
            )}
          </>
        )}
        <p className="muted small">
          A mismatch is a warning, not a block: check the numbers you typed, then save anyway if they are right.
        </p>
      </div>

      <div className="cs-actions">
        <button type="button" className="wp-save" onClick={save} disabled={busy || !view.isFinal}>
          {busy ? 'Saving…' : `Save week ${week}`}
        </button>
        <button
          type="button"
          className="cs-clear"
          onClick={() => void run(() => clearScoreOverride(leagueSleeperId, week))}
          disabled={busy || !hasOverrideRows}
        >
          Clear entered scores
        </button>
      </div>
      {error && (
        <p className="wp-error" role="alert">
          {error}
        </p>
      )}
      <CommissionerHonourNote />
    </details>
  )
}

import { useId } from 'react'
import Avatar from './Avatar'
import PersonName from './PersonName'
import type { Superlative, SuperlativePlayerStanding } from '../api'

const MAX_PLAYER_ROWS = 10

/** Whole rank groups while the cumulative count stays <= 10; the first group that would overflow, and all after it, are collapsed. */
function splitPlayerGroups(rows: SuperlativePlayerStanding[]) {
  const groups: SuperlativePlayerStanding[][] = []
  for (const p of rows) {
    const last = groups[groups.length - 1]
    if (last && last[0].rank === p.rank) last.push(p)
    else groups.push([p])
  }
  const shown: SuperlativePlayerStanding[] = []
  let i = 0
  for (; i < groups.length; i++) {
    if (shown.length + groups[i].length > MAX_PLAYER_ROWS) break
    shown.push(...groups[i])
  }
  return { shownPlayers: shown, collapsedGroups: groups.slice(i) }
}

type Props = {
  s: Superlative
  title: string
  hue: number
  /** Built by the card from `standingFigure`, so this list and the card can't disagree on a figure (amendment 10). */
  figure: (v: number) => string
}

/**
 * specs/010-superlatives-full-standings: every roster's place for one award,
 * not just the winner(s). Since spec 013 US9 it expands IN PLACE under the
 * award's row (it used to be a modal); the content and every rule below are
 * unchanged.
 *
 * Renders `s.standings` in payload order and never re-sorts: the backend owns
 * the ranking direction (LOWEST_WEEK and UNLUCKIEST run low to high) and its
 * tie ranks (1, 1, 3), and a client-side sort would quietly disagree with both.
 * A row with no value says why (`missingReason`) instead of showing a 0 that
 * would read as a measured result.
 */
export default function SuperlativeStandings({ s, title, hue, figure }: Props) {
  const titleId = useId()
  const isPlayers = s.kind === 'JABARI_SMITH_JR'

  // T031 live finding: 41-way tie on NFL 2025. The backend sends everyone tied with the
  // 10th (correct), but 41 identical rows are noise. Group by rank in payload order (never
  // re-sorted), show whole groups while the running count stays <= 10, and fold the rest
  // (and every later group) behind a "Show all". Clicks stay inside this panel (it stops
  // propagation), so the <details> can't toggle the row that contains it.
  const { shownPlayers, collapsedGroups } = splitPlayerGroups(isPlayers ? s.playerStandings : [])
  const firstGroupCollapsed = shownPlayers.length === 0 && collapsedGroups.length > 0
  const collapsedCount = collapsedGroups.reduce((n, g) => n + g.length, 0)
  const collapsedAdds = collapsedGroups[0]?.[0]?.adds ?? 0
  const collapsedSummary = firstGroupCollapsed
    ? `${collapsedCount} ${collapsedCount === 1 ? 'player' : 'players'} tied with ${collapsedAdds} ${collapsedAdds === 1 ? 'add' : 'adds'} each`
    : `${collapsedCount} more ${collapsedCount === 1 ? 'player' : 'players'} with ${collapsedAdds} ${collapsedAdds === 1 ? 'add' : 'adds'} each`

  function playerRow(p: SuperlativePlayerStanding) {
    return (
      <li className={`sl-standing sl-player-standing${p.rank === 1 ? ' sl-standing-top' : ''}`} key={p.playerId}>
        <span className="sl-standing-rank">{p.rank}</span>
        <span className="sl-standing-who">
          <span className="sl-standing-name">
            {p.playerName}
            {p.position ? ` (${p.position})` : ''}
          </span>
          {p.team && <span className="muted small sl-standing-note">{p.team}</span>}
        </span>
        <span className="sl-standing-figure">
          {p.adds} {p.adds === 1 ? 'add' : 'adds'} by {p.distinctTeams} {p.distinctTeams === 1 ? 'team' : 'teams'}
        </span>
      </li>
    )
  }

  return (
    // role=region labelled by the award: the in-place successor of the modal's
    // role=dialog. stopPropagation: this renders inside the row's <article>,
    // whose own click handler opens it, and a click in here must not re-fire it.
    <div
      className="sl-standings-panel"
      role="region"
      aria-labelledby={titleId}
      style={{ ['--sl-hue' as string]: hue }}
      onClick={(e) => e.stopPropagation()}
    >
      <h5 id={titleId} className="sl-standings-title">{title}: full standings</h5>
      {isPlayers && <p className="muted small sl-standings-sub">This award ranks players, not teams.</p>}

      <ol className="sl-standings">
        {isPlayers
          ? shownPlayers.map(playerRow)
          : s.standings.map((r) => (
              <li
                className={`sl-standing${r.rank === 1 ? ' sl-standing-top' : ''}${r.hasValue ? '' : ' sl-standing-missing'}`}
                key={r.team.rosterId}
              >
                <span className="sl-standing-rank">{r.rank ?? '—'}</span>
                <span className="sl-standing-who">
                  <span className="sl-standing-name">
                    <Avatar
                      avatarId={r.team.avatarId}
                      seed={String(r.team.managerId ?? r.team.rosterId)}
                      label={r.team.teamName}
                      hue={hue}
                      className="sl-avatar"
                    />
                    <PersonName teamName={r.team.teamName} username={r.team.username} />
                  </span>
                  {r.note && <span className="muted small sl-standing-note">{r.note}</span>}
                </span>
                {r.hasValue && r.value != null ? (
                  <span className="sl-standing-figure">{figure(r.value)}</span>
                ) : (
                  <span className="sl-standing-figure muted small">{r.missingReason}</span>
                )}
              </li>
            ))}
      </ol>
      {isPlayers && collapsedGroups.length > 0 && (
        <details className="sl-standings-more">
          <summary className="muted small">
            {/* Spelled out: a bare "7" beside "41 more" read as 741 (T031). */}
            Rank {collapsedGroups[0][0].rank} ·{' '}
            {collapsedSummary}
            <span className="sl-standings-more-hint"> — Show all</span>
          </summary>
          <ol className="sl-standings">{collapsedGroups.flat().map(playerRow)}</ol>
        </details>
      )}
    </div>
  )
}

import type { BenchOption, LineupCheck as LineupCheckData, LineupFlag, Sport } from '../api'
import { injuryText, statusAsOfLabel } from '../statCopy'
import PlayerLink from './PlayerLink'

/**
 * specs/042 US3: "Check your lineup" on league home. Your live starters that are empty, out, listed or have no
 * game this week, each with the bench players eligible for that slot.
 *
 * <b>Facts, never verdicts</b> (SC-006): a row says what Sleeper's tag or the schedule says, an option says who is
 * eligible and what he has averaged. No row tells you to do anything, and the copy never claims a change can
 * be made today -- whether this league's lineups lock weekly or daily is not established (t001 (e)), so the
 * "Lineups lock for the week" line is deliberately absent.
 *
 * Every status carries its as-of time printed from the server's instant, or says "status time unknown"
 * (never "fresh"). Which statuses mean "out" is the server's rule (the reason code), not restated here.
 */

/** The one sentence a flag gets, from its reason code. Every code the server sends has one. */
export function flagSentence(f: LineupFlag, week: number | null): string {
  switch (f.reason) {
    case 'EMPTY_SLOT':
      return 'Empty slot'
    case 'OUT': {
      const tag = injuryText(f.injuryStatus, f.playerStatus, true)
      return tag && tag.toLowerCase() !== 'out' ? `Out (${tag})` : 'Out'
    }
    case 'LISTED':
      return injuryText(f.injuryStatus, f.playerStatus, false) ?? 'Listed'
    case 'NO_GAME_IN_WEEK':
      return `${f.player?.team ?? 'His team'} has no game${week != null ? ` in week ${week}` : ' this week'}`
    case 'UNKNOWN_PLAYER':
      return 'We don’t have this player’s details'
  }
}

function optionsOrderSentence(f: LineupFlag): string | null {
  switch (f.optionsOrder) {
    case 'FP_PER_GAME':
      return 'Sorted by fantasy points per game, highest first.'
    case 'NAME':
      return 'Listed alphabetically.'
    case 'SLOT_UNSUPPORTED':
      return null
  }
}

function games(n: number): string {
  return `${n} ${n === 1 ? 'game' : 'games'}`
}

function OptionLine({ o, leagueId, sport }: { o: BenchOption; leagueId: string; sport: Sport }) {
  return (
    <li className="lc-option">
      <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={o.player.sleeperPlayerId} sport={sport}>
        {o.player.name ?? o.player.sleeperPlayerId}
      </PlayerLink>
      {o.player.positions.length > 0 && <span className="muted small"> {o.player.positions.join('/')}</span>}
      <span className="muted small lc-option-fact">
        {o.fpPerGame != null && o.fpSeason != null
          ? `${o.fpPerGame.toFixed(1)} fp/g · ${o.fpSeason} avg · ${games(o.fpGames)}`
          : 'no average yet'}
        {o.gamesLeft != null && ` · ${games(o.gamesLeft)} left this week`}
        {o.injuryStatus && ` · ${injuryText(o.injuryStatus, null, false) ?? `Listed: ${o.injuryStatus}`}`}
      </span>
    </li>
  )
}

function FlagRow({ f, check, leagueId, sport }: { f: LineupFlag; check: LineupCheckData; leagueId: string; sport: Sport }) {
  const status = f.reason === 'OUT' || f.reason === 'LISTED'
  const asOf = statusAsOfLabel(check.statusAsOf)
  const order = optionsOrderSentence(f)
  return (
    <li className="lc-flag">
      <div className="lc-flag-head">
        <span className="lc-slot">{f.slot}</span>
        {f.player ? (
          <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={f.player.sleeperPlayerId} sport={sport}>
            <strong>{f.player.name ?? f.player.sleeperPlayerId}</strong>
          </PlayerLink>
        ) : (
          <strong>Nobody in this slot</strong>
        )}
        <span className={`lc-reason lc-${f.reason.toLowerCase()}`}>{flagSentence(f, check.week)}</span>
        {status && <span className="muted small">{asOf ? `as of ${asOf}` : 'status time unknown'}</span>}
      </div>
      {f.reason === 'UNKNOWN_PLAYER' ? null : f.optionsOrder === 'SLOT_UNSUPPORTED' ? (
        <p className="muted small lc-options-note">Bench options aren’t shown for a {f.slot} slot yet.</p>
      ) : f.options.length === 0 ? (
        <p className="muted small lc-options-note">
          {`No bench player is eligible for ${f.slot} who isn’t out${check.gameCheck ? ' and has a game this week' : ''}.`}
        </p>
      ) : (
        <>
          <p className="muted small lc-options-note">Bench options{order ? `. ${order}` : ''}</p>
          <ul className="lc-options">
            {f.options.map((o) => (
              <OptionLine key={o.player.sleeperPlayerId} o={o} leagueId={leagueId} sport={sport} />
            ))}
          </ul>
        </>
      )}
    </li>
  )
}

/** Why the game check did not run, said accurately: no schedule rows, no known week, or a week the schedule lacks. */
function gameCheckSentence(check: LineupCheckData, sport: Sport): string {
  switch (check.gameCheckReason) {
    case 'WEEK_UNKNOWN':
      return 'Sleeper hasn’t said which week it is, so games this week aren’t checked.'
    case 'WEEK_NOT_IN_SCHEDULE':
      return `The stored schedule doesn’t include ${check.week != null ? `week ${check.week}` : 'this week'}, so games this week aren’t checked.`
    default:
      return sport === 'nfl' ? 'No game check for football yet.' : 'The schedule isn’t loaded, so games this week aren’t checked.'
  }
}

export default function LineupCheck({ check, leagueId, sport }: { check: LineupCheckData; leagueId: string; sport: Sport }) {
  // Not a member / not in season: the section simply isn't there. A failed Sleeper call says so.
  if (!check.available && check.reason !== 'ROSTERS_FAILED') return null
  return (
    <section className="section lh-lineup" aria-labelledby="lh-lineup-h">
      <h2 className="section-title" id="lh-lineup-h">
        Check your lineup
      </h2>
      {!check.available ? (
        <p className="muted small">Couldn’t read your live lineup from Sleeper right now.</p>
      ) : (
        <>
          {check.flags.length === 0 ? (
            <p>All starters are active</p>
          ) : (
            <ul className="lc-flags">
              {check.flags.map((f, i) => (
                <FlagRow key={`${f.slot}-${i}`} f={f} check={check} leagueId={leagueId} sport={sport} />
              ))}
            </ul>
          )}
          {!check.gameCheck ? (
            <p className="muted small lc-note">{gameCheckSentence(check, sport)}</p>
          ) : (
            <p className="muted small lc-note">
              {statusAsOfLabel(check.scheduleAsOf)
                ? `Games from the schedule as of ${statusAsOfLabel(check.scheduleAsOf)}.`
                : 'Games from the schedule; schedule time unknown.'}
            </p>
          )}
          {check.flags.length === 0 && (
            <p className="muted small lc-note">
              {statusAsOfLabel(check.statusAsOf)
                ? `Sleeper’s injury tags as of ${statusAsOfLabel(check.statusAsOf)}.`
                : 'Sleeper’s injury tags; status time unknown.'}
            </p>
          )}
          <p className="muted small lc-note">This shows tags and eligibility only. It doesn’t judge any lineup.</p>
        </>
      )}
    </section>
  )
}

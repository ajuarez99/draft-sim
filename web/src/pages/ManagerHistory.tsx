import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link, useParams } from 'react-router-dom'
import {
  getManagerHistory,
  type CareerProfile,
  type ManagerHistory as ManagerHistoryData,
  type Rank,
  type Sport,
  type StandingRow,
  type Unavailable,
  type WaiverTendency,
} from '../api'
import { reachGapText } from '../managerBehaviour'
import { ordinal } from '../rankOrder'
import { useRailContextSlot } from '../appSlots'
import Avatar from '../components/Avatar'
import PageHeader from '../components/PageHeader'

// Same order draftHistory already renders in (Sport.values() server-side:
// NFL, NBA) -- not exported from destinations.ts, so restated here rather
// than reached across files for two literals.
const SPORT_ORDER: Sport[] = ['nfl', 'nba']

/**
 * claude/league-suite.md Phase A: one manager's record across every ingested
 * season, plus their career-wide draft-side numbers -- kept visually separate
 * (two panels) rather than one flat table, since one is "what happened"
 * (record, points) and the other is "what this app thinks about it" (reach,
 * tilt) -- the same distinction the board's own provenance labels enforce.
 */
export default function ManagerHistory() {
  const { managerId } = useParams<{ managerId: string }>()
  const rail = useRailContextSlot()
  const [data, setData] = useState<ManagerHistoryData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!managerId) return
    setData(null)
    setError(null)
    getManagerHistory(Number(managerId))
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [managerId])

  if (error) {
    return (
      <div className="content">
        <section className="panel">
          <div className="error">{error}</div>
        </section>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="content">
        <section className="panel">
          <p className="muted small">Loading manager history…</p>
        </section>
      </div>
    )
  }

  // specs/006-deeper-history-both-sports US1 (FR-002): grouped by sport,
  // never summed across sport. baseline.md's T002 measured the bug this
  // replaces -- ManagerHistory.tsx used to add NBA wins to NFL wins into one
  // "29-25 across 6 seasons" header, because nothing on the row said which
  // sport it was. Sports with no seasons are omitted (T020) rather than
  // rendered as an empty block -- the same "nothing to say" rule
  // draftHistory below already follows.
  //
  // T076: the rows come from `careers[].seasons`, which is now the response's
  // only season list -- the flat `seasons[]` this used to filter is gone. The
  // two were always the same rows; the difference is that a career's rows
  // arrive already belonging to one sport, so there is no longer any list on
  // this page that CAN be totalled across sports by mistake. That is the
  // whole point of the removal, not a refactor on the way to it.
  // flatMap, not map().filter(): it narrows `career` to present, which is now
  // guaranteed for any sport this page renders -- the rows ARE the career's
  // rows. That removes the `?? rows.length` fallback the rail used to need
  // against a server too old to send careers[].
  const careersBySport = SPORT_ORDER.flatMap((sport) => {
    const career = data.careers?.find((c) => c.sport === sport)
    return career && career.seasons.length > 0 ? [{ sport, career, rows: career.seasons }] : []
  })

  const name = data.manager ?? `manager ${data.managerId}`

  return (
    <>
      {/* Portaled, not resolved by AppShell the way league context is: a
          manager's name isn't in the URL, and this page has already fetched
          it. The rail resolves what the path alone can tell it; a page hands
          over what only the page knows. claude/site-wide-shell-propagation.md
          Phase 3. */}
      {rail.node &&
        createPortal(
          <div className="app-rail-section">
            <span className="app-rail-label">Manager</span>
            <div className="rail-league-id" title={name}>
              <Avatar avatarId={data.avatarId} seed={String(data.managerId)} label={name} />
              <span className="rail-league-text app-rail-row-label">
                <span className="rail-league-name">{name}</span>
                {/* FR-002: a season count is one of the totals that must not
                    span sports, same as the header below -- so this is a
                    count per sport, never a figure spanning them. styles.css
                    already sizes a sport-pill inside rail-league-sub for
                    exactly this.
                    FR-007: and it is the SAME count the header and the career
                    panel state. Live verification on 2026-09-21 caught this
                    rail reading "NBA 3" beside a header reading "2 seasons"
                    -- the third place on one screen where a number labelled
                    "seasons" meant something different. `seasonsCounted`
                    is the one source, and since T076 the only one -- a
                    sport whose seasons are all uncounted reads 0 here, which
                    is what "counted seasons" means and what the panel below
                    says too. */}
                <span className="rail-league-sub">
                  {careersBySport.map(({ sport, career }, i) => (
                    <span key={sport}>
                      {i > 0 && ' · '}
                      <span className={`sport-pill ${sport}`}>{sport.toUpperCase()}</span>{' '}
                      {career.seasonsCounted}
                    </span>
                  ))}
                </span>
              </span>
            </div>
            <Link to="/managers" className="app-rail-row" title="All managers" aria-label="All managers">
              <span className="app-rail-glyph" aria-hidden="true">
                ●
              </span>
              <span className="app-rail-row-label">All managers</span>
            </Link>
          </div>,
          rail.node,
        )}

      <div className="content">
      <PageHeader
        eyebrow="Manager"
        title={
          <span className="page-title-avatar">
            <Avatar avatarId={data.avatarId} seed={String(data.managerId)} label={name} /> {name}
          </span>
        }
        sub={
          // Rethought for FR-002: the old sub was one combined record
          // ("29-25 across 6 seasons") that added basketball wins to
          // football wins. There is no honest single number to replace it
          // with -- a manager who plays two sports has two records, not one
          // blended one -- so this renders one clause per sport instead,
          // each with its own record/season-count/titles, never combined.
          // A one-sport manager gets one clause, which reads exactly like
          // the old single-sport case did.
          //
          // Read off `careers`, NOT recomputed from `seasons`. Live
          // verification on 2026-09-21 caught exactly that bug: this header
          // said "NBA 17-21 across 3 seasons" while the career panel below
          // said "17-21-1 over 2 seasons" -- two season counts for one
          // manager on one page, because the header counted every listed
          // row and the panel counted only seasons with a scored week
          // (NBA 2026 is ingested and unplayed). It also dropped the tie.
          // The fix is the rule the repo already learned the hard way from a
          // ballot tally printed under a lagging week label: a number and
          // its label come from ONE source. `seasonsCounted` is that source
          // (FR-007), and `careers` is where it lives.
          <>
            {careersBySport.map(({ sport, career }, i) => {
              // Read straight off the career, with no row-summing fallback:
              // since T076 the rows on this page ARE the career's rows, so a
              // fallback could only ever produce a SECOND answer to a question
              // this object already answers -- the shape of defect this whole
              // feature existed to remove.
              const wins = career.wins
              const losses = career.losses
              const ties = career.ties
              // Counted seasons, matching the panel. A season with no scored
              // week (NBA 2026) is listed in the table above and counts
              // towards nothing, which is the same rule everywhere on this
              // page.
              const seasons = career.seasonsCounted
              const titles = career.titles
              return (
                <span key={sport}>
                  {i > 0 && ' · '}
                  <span className={`sport-pill ${sport}`}>{sport.toUpperCase()}</span>{' '}
                  {wins}-{losses}
                  {ties > 0 && `-${ties}`} across {seasons} season{seasons === 1 ? '' : 's'}
                  {titles > 0 && ` · ${titles} title${titles === 1 ? '' : 's'}`}
                </span>
              )
            })}
          </>
        }
      />

      {/* `manager-seasons` names this section so a test (or a future style)
          can scope to the standings tables alone: `.manager-sport-block` with
          a `.panel-head .sport-pill` inside it now occurs three times on this
          page -- here, in the career panel, and in draft history. */}
      <section className="panel manager-seasons">
        {/* The career line that used to open this panel is the page header's
            sub now -- it describes the manager, not the standings table.
            One block per sport (T017), following the same pattern
            draftHistory already uses below, rather than inventing a second
            way to split a manager's history by sport. */}
        {careersBySport.map(({ sport, rows }) => (
          <div key={sport} className="manager-sport-block">
            <div className="panel-head">
              <span className={`sport-pill ${sport}`}>{sport.toUpperCase()}</span>
            </div>
            <div className="table-wrap">
              <table className="standings">
                <thead>
                  <tr>
                    <th></th>
                    <th>Season</th>
                    <th>League</th>
                    <th className="mono">W</th>
                    <th className="mono">L</th>
                    <th className="mono">PF</th>
                    <th className="mono">PA</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s: StandingRow) => (
                    <tr key={`${s.sleeperLeagueId}-${s.rosterId}`}>
                      <td>{s.champion && <span title="Champion">🏆</span>}</td>
                      <td>
                        {s.sleeperLeagueId ? (
                          <Link to={`/leagues/${s.sleeperLeagueId}/history`}>{s.season}</Link>
                        ) : (
                          s.season
                        )}
                      </td>
                      {/* T019: the season alone is no longer an identifier --
                          this manager has two 2026 football rows, one per
                          league (West Coast Fantasy Football and (Foot) Ball
                          Knowers), and nothing but the league name tells
                          them apart. */}
                      <td>{s.leagueName ?? '—'}</td>
                      <td className="mono">{s.wins ?? '—'}</td>
                      <td className="mono">{s.losses ?? '—'}</td>
                      <td className="mono">{s.pointsFor?.toFixed(2) ?? '—'}</td>
                      <td className="mono">{s.pointsAgainst?.toFixed(2) ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
      </section>

      {/* specs/006-deeper-history-both-sports US3 (T042/T043): the requested
          career profile, one block per sport, built entirely off `careers`
          (ManagerCareerService) rather than re-derived from the flat
          `seasons` table above -- two implementations of "how many games did
          they win" is exactly the defect class T029's OneEfficiencyImplementationTest
          exists to catch one level down, and duplicating the arithmetic here
          would reintroduce it one level up. */}
      <CareerPanel careers={data.careers} />

      <section className="panel">
        <div className="panel-head">
          <h2>What this app thinks about their drafting</h2>
        </div>
        <p className="muted small">
          Fitted from their own draft history, the same numbers the simulator uses -- not the
          record above, which is what actually happened on the scoreboard.
        </p>
        {data.draftHistory.length === 0 ? (
          <p className="muted">No drafts observed yet -- drafts like the room, no history to fit from.</p>
        ) : (
          // One block per sport. The same person can appear twice here, which
          // is the honest answer rather than a chosen one -- these are two
          // separate fits off two separate sets of picks.
          data.draftHistory.map((h) => (
            <div key={h.sport} className="manager-sport-block">
              <p className="small">
                <span className={`sport-pill ${h.sport}`}>{h.sport.toUpperCase()}</span>
                {h.picksScored > 0 ? (
                  <>
                    Reach bias: <span className="mono">{h.reachBias?.toFixed(2) ?? '—'}</span> over{' '}
                    {h.draftsObserved} draft{h.draftsObserved === 1 ? '' : 's'} ({h.provenance.toLowerCase()})
                  </>
                ) : (
                  // No reach number rather than a reach number of zero -- with
                  // no scoreable picks, `reachBias` is the league mean wearing
                  // this manager's name. Printing "0.00" there would read as a
                  // finding. Same rule as SeatPopover and /managers.
                  <>
                    {h.draftsObserved} draft{h.draftsObserved === 1 ? '' : 's'} observed, no reach number
                  </>
                )}
              </p>
              {h.picksScored === 0 && (
                <p className="muted tiny">{reachGapText(h)}</p>
              )}
              {h.positionalTilt && Object.keys(h.positionalTilt).length > 0 && (
                <p className="small mono">
                  {Object.entries(h.positionalTilt)
                    .map(([pos, tilt]) => `${pos} ${tilt.toFixed(2)}×`)
                    .join(' · ')}
                </p>
              )}
            </div>
          ))
        )}
      </section>
      </div>
    </>
  )
}

const RANK_FIGURE_LABEL: Record<Rank['figure'], string> = {
  winRate: 'Win rate',
  pointsPerSeason: 'Points per season',
  averageEfficiency: 'Average efficiency',
}

const UNAVAILABLE_FIGURE_LABEL: Record<Unavailable['figure'], string> = {
  playoffAppearances: 'Playoff appearances',
  tradesPerSeason: 'Trades per season',
}

/**
 * specs/006-deeper-history-both-sports US3 (T042/T043): the career profile
 * the whole feature was named for -- record, win rate, points, efficiency,
 * wins above expected, titles and ranks, one block per sport, each stating
 * the seasons it rests on. Absent entirely when the manager has no roster-
 * season in any sport, matching the seasons table's and draftHistory's own
 * "nothing to say" rule.
 */
function CareerPanel({ careers }: { careers: CareerProfile[] }) {
  if (careers.length === 0) return null
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Career profile</h2>
      </div>
      <p className="muted small">
        Every figure below comes from the same per-week optimal-lineup computation the Roster
        management page uses -- never Sleeper's own stored season potential, which reads more
        flattering for the same manager-season. Each figure states the seasons it covers: with one
        or two played seasons per manager in this database, an unlabeled average would read as a
        career number that is really a single season's.
      </p>
      {careers.map((c) => (
        <CareerBlock key={c.sport} career={c} />
      ))}
    </section>
  )
}

function CareerBlock({ career: c }: { career: CareerProfile }) {
  const record = `${c.wins}-${c.losses}${c.ties > 0 ? `-${c.ties}` : ''}`
  return (
    <div className="manager-sport-block">
      <div className="panel-head">
        <span className={`sport-pill ${c.sport}`}>{c.sport.toUpperCase()}</span>
      </div>

      <div className="margin-cards">
        <CareerStat value={record} label="Record" seasonsCounted={c.seasonsCounted} />
        <CareerStat
          value={c.winRate == null ? '—' : `${(c.winRate * 100).toFixed(1)}%`}
          label="Win rate"
          seasonsCounted={c.seasonsCounted}
          note={c.winRate == null ? 'no games played yet' : undefined}
        />
        <CareerStat
          value={c.pointsPerSeason == null ? '—' : c.pointsPerSeason.toFixed(2)}
          label="Points per season"
          seasonsCounted={c.seasonsCounted}
          note={
            c.pointsPerSeason == null
              ? 'no counted season yet'
              : `${c.pointsFor.toFixed(2)} for, ${c.pointsAgainst.toFixed(2)} against`
          }
        />
        <CareerStat
          value={c.averageEfficiency == null ? '—' : `${(c.averageEfficiency * 100).toFixed(1)}%`}
          label="Average efficiency"
          seasonsCounted={c.seasonsCounted}
          // T042/FR-006: weeksCounted/weeksExcluded ride beside averageEfficiency
          // wherever it is shown -- an excluded week must stay visible, the
          // same argument RosterManagement's own ExcludedNote makes.
          note={
            c.averageEfficiency == null
              ? 'no scored week with a per-player breakdown yet'
              : `${c.weeksCounted} week${c.weeksCounted === 1 ? '' : 's'} counted` +
                (c.weeksExcluded > 0
                  ? `, ${c.weeksExcluded} excluded for want of a per-player breakdown`
                  : '')
          }
        />
        <CareerStat
          value={
            c.winsAboveExpected == null
              ? '—'
              : `${c.winsAboveExpected > 0 ? '+' : ''}${c.winsAboveExpected.toFixed(2)}`
          }
          label="Wins above expected"
          seasonsCounted={c.seasonsCounted}
          note={
            c.winsAboveExpected == null ? 'no counted season produced a computable figure' : undefined
          }
        />
        <CareerStat
          value={String(c.titles)}
          label={c.titles === 1 ? 'Title' : 'Titles'}
          seasonsCounted={c.seasonsCounted}
        />
      </div>

      <RankList ranks={c.ranks} />
      <UnavailableList items={c.unavailable} />
      <WaiverPanel waivers={c.waivers} />
    </div>
  )
}

/**
 * specs/006-deeper-history-both-sports T075 (US6). Reuses CareerStat's own
 * margin-card layout rather than a flat row of numbers -- this is the same
 * "one headline value plus context underneath" shape every other career
 * figure on this block already uses.
 *
 * FAAB figures are ALWAYS a percentage of a season's own starting budget,
 * never a dollar amount: research R8 measured budgets ranging 100 -> 10000
 * across this database's seasons, a 50x spread within a single manager's own
 * career. `waivers.faab` is null (not a card of zeroes) when no counted
 * season this manager played ran FAAB at all -- `faabExcludedSeasons` below
 * names every one of those seasons and why, the same "stated reason, never a
 * silent absence" rule `UnavailableList` above already follows.
 */
function WaiverPanel({ waivers }: { waivers: WaiverTendency }) {
  return (
    <div className="career-waivers">
      <h3 className="career-waivers-head">Waiver activity</h3>
      <div className="margin-cards">
        <CareerStat
          value={waivers.movesPerSeason.toFixed(1)}
          label="Moves per season"
          seasonsCounted={waivers.seasonsCounted}
          note="waiver claims and free-agent adds, whatever the outcome -- trades cannot be attributed to one manager (see below)"
        />
        {waivers.faab && (
          <>
            <CareerStat
              value={`${(waivers.faab.typicalBidPct * 100).toFixed(1)}%`}
              label="Typical FAAB bid"
              seasonsCounted={waivers.seasonsCounted}
              note="of that season's own starting budget -- a dollar figure does not compare across seasons of different budgets"
            />
            <CareerStat
              value={`${(waivers.faab.largestBidPct * 100).toFixed(1)}%`}
              label="Largest FAAB bid"
              seasonsCounted={waivers.seasonsCounted}
              note="of that season's own starting budget"
            />
            <CareerStat
              value={`${(waivers.faab.spentPerSeasonPct * 100).toFixed(1)}%`}
              label="FAAB spent per season"
              seasonsCounted={waivers.seasonsCounted}
              note={
                `${waivers.faab.claimsPerSeason.toFixed(1)} bid${waivers.faab.claimsPerSeason === 1 ? '' : 's'}` +
                ` per season, ${(waivers.faab.bidSuccessRate * 100).toFixed(0)}% won`
              }
            />
          </>
        )}
      </div>
      {!waivers.faab && (
        <p className="muted tiny">
          No FAAB bids in any counted season -- see below for which seasons used waiver priority instead.
        </p>
      )}
      {waivers.faabExcludedSeasons.length > 0 && (
        <ul className="career-unavailable">
          {waivers.faabExcludedSeasons.map((e) => (
            <li key={`${e.season}-${e.leagueName}`} className="muted tiny">
              {e.season} ({e.leagueName}): {e.reason}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * One career figure. Reuses the record book's margin-card (value + label)
 * rather than a flat uniform box (feedback_avoid_flat_uniform_cards.md) --
 * this panel's content is card-shaped the same way a margin record is: one
 * headline number plus context underneath, not a grid of rows.
 *
 * `seasonsCounted` is stated on EVERY card, not once per block (FR-007,
 * SC-008: "every career figure on screen states the number of seasons it
 * covers"). A record and a win rate rest on the same seasons but are not the
 * same claim, so each gets its own caption rather than one shared above the
 * grid that a reader could miss while scanning a single stat.
 */
function CareerStat({
  value,
  label,
  seasonsCounted,
  note,
}: {
  value: string
  label: string
  seasonsCounted: number
  note?: string
}) {
  return (
    <div className="margin-card">
      <div className="margin-card-head">
        <span>
          <span className="margin-value">{value}</span>
          <span className="margin-label"> {label}</span>
        </span>
      </div>
      <p className="muted tiny career-stat-seasons">
        over {seasonsCounted} season{seasonsCounted === 1 ? '' : 's'}
      </p>
      {note && <p className="muted tiny">{note}</p>}
    </div>
  )
}

/**
 * FR-008 / research R5: a rank always carries the population it was ranked
 * against and the league chain it was ranked within -- a bare "#2" is not a
 * rank. T032 asserts this literally: every rendered rank shows its
 * population and league name, never a bare ordinal.
 */
function RankList({ ranks }: { ranks: Rank[] }) {
  if (ranks.length === 0) return null
  return (
    <ul className="career-ranks">
      {ranks.map((r) => (
        <li className="career-rank-row" key={`${r.figure}-${r.sleeperLeagueId}`}>
          <span className="career-rank-figure">{RANK_FIGURE_LABEL[r.figure]}</span>
          <span className="career-rank-position mono">
            {ordinal(r.position)} of {r.population}
          </span>
          <span className="career-rank-pop muted">in {r.leagueName}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * T043 / FR-011: figures this app genuinely cannot answer, named rather than
 * printed as a zero. `unavailable` is present on every CareerProfile even
 * when empty (ManagerCareerService.UNAVAILABLE is unconditional) -- read off
 * the wire array rather than hardcoded here, so a server that starts
 * answering one of these stops naming it without a frontend change.
 */
function UnavailableList({ items }: { items: Unavailable[] }) {
  if (items.length === 0) return null
  return (
    <ul className="career-unavailable">
      {items.map((u) => (
        <li key={u.figure} className="muted tiny">
          {(UNAVAILABLE_FIGURE_LABEL[u.figure] ?? u.figure) + ': ' + u.reason}
        </li>
      ))}
    </ul>
  )
}

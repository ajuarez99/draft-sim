import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { crestLetter, hueForName } from '../hue'
import { leagueLineages } from '../leagueLineage'
import { roundPickLabel } from '../roundPickLabel'
import { relativeTime } from '../relativeTime'
import { SkeletonRows } from '../components/Skeleton'
import SportFilterRail from '../components/SportFilterRail'
import { useSportFilter, type SportFilter } from '../sportFilter'
import StartMockModal, { type MockableLeague } from '../components/StartMockModal'
import PageHeader from '../components/PageHeader'
import { useRailContextSlot } from '../appSlots'
import { useUser } from '../user'
import {
  getDrafts,
  getMockSessions,
  getSleeperUserLeagues,
  ingestAdp,
  ingestBoard,
  ingestLeague,
  ingestPlayers,
  trackDraft,
  type DraftSummary,
  type MockSessionSummary,
  type Sport,
  type SleeperLeague,
  type TrackResponse,
} from '../api'

// claude/user-identity-and-onboarding.md §5d: driven from the frontend rather
// than POST /api/ingest/all/{id} so a first-ever ingest -- three sequential
// Sleeper crawls plus a rebuild -- shows staged progress instead of a single
// 40-second spinner, and so it can't exceed a platform's 30-60s HTTP timeout.
// Each stage's own function closes over the league being set up.
function setupStages(l: SleeperLeague) {
  return [
    { label: 'Fetching players', run: () => ingestPlayers(l.sport) },
    { label: "Reading your league's history", run: () => ingestLeague(l.sleeperLeagueId) },
    { label: 'Checking market prices', run: () => ingestAdp(l.sport) },
    { label: 'Building the board', run: () => ingestBoard(l.sport) },
  ]
}

type SetupState = { stageIndex: number; failedLabel: string | null }

// A complete draft has real picks to show (CompletedDraftBoard); anything
// else -- pre_draft, drafting, or a null/unrecognized status -- has none yet,
// so it goes to DraftView's simulator instead, same as it always has.
function draftRoute(d: { sleeperDraftId: string; status: string | null }): string {
  return (d.status ?? 'unknown') === 'complete'
    ? `/drafts/${d.sleeperDraftId}/board`
    : `/drafts/${d.sleeperDraftId}`
}

function sportTitle(f: SportFilter): string {
  if (f === 'all') return 'All sports'
  return `${f.toUpperCase()} leagues`
}

/**
 * design_handoff_multisport_mock_drafts/README.md: the redesigned home
 * screen. Two real problems it fixes -- mocks always defaulted to football
 * with no way to say otherwise, and every mock draft row looked identical
 * ("12-team mock from 1.05 / through 1.04") with no sport, league or recency
 * signal. A sidebar sport filter scopes both the leagues grid and the mock
 * list; "Start a mock draft" opens a two-step sport-then-league modal instead
 * of jumping straight to /mock/new.
 *
 * Both sports are mockable as of claude/nba-mock-drafts.md. This comment used
 * to explain why NBA was shipped visible-but-disabled ("Soon"): the backend
 * refused anything but Sport.NFL. It no longer does, so the gate is gone from
 * here, from the league cards' "Mock it" and from StartMockModal.
 */
export default function DraftPicker() {
  const user = useUser()
  const navigate = useNavigate()
  const location = useLocation()
  const rail = useRailContextSlot()
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null)
  const [mocks, setMocks] = useState<MockSessionSummary[] | null>(null)
  // Split from one shared `error` the fetch and the add-league form used to
  // share: fetch errors render at the top of "Your leagues" same as before,
  // but that panel is far from the "League data" disclosure the add form now
  // lives inside (B1), and a failed add needs to surface next to the button
  // that caused it, not scrolled away under a different heading.
  const [fetchError, setFetchError] = useState<string | null>(null)
  const [addError, setAddError] = useState<string | null>(null)
  const [leagueId, setLeagueId] = useState('')
  const [adding, setAdding] = useState(false)
  const [tracking, setTracking] = useState<string | null>(null)
  const [tracked, setTracked] = useState<Record<string, TrackResponse | { failed: string }>>({})

  // "From Sleeper": leagues this user belongs to on Sleeper that have no
  // `league` row in this app's DB yet (§5c/§5d). null while loading, distinct
  // from [] once it's known there's genuinely nothing to set up.
  const [sleeperLeagues, setSleeperLeagues] = useState<SleeperLeague[] | null>(null)
  const [sleeperError, setSleeperError] = useState<string | null>(null)
  const [setupState, setSetupState] = useState<Record<string, SetupState>>({})

  // Shared with /managers, the other page that lists both sports at once --
  // one key, so the choice follows you between them (sportFilter.ts).
  const [sportFilter, setSportFilter] = useSportFilter()

  const [modalSeed, setModalSeed] = useState<{ sport: Sport; leagueId: string | null } | null>(null)

  // The rail's "Mock drafts" row is global but the modal is not -- it needs
  // the league list this page has already fetched. So the rail navigates here
  // carrying a request (AppShell.openMockModal) and this opens it on arrival.
  // Keyed on location.key so the same request fires when the row is clicked
  // while already on Home, and cleared immediately so a reload or a Back into
  // this entry doesn't reopen a modal nobody asked for a second time.
  useEffect(() => {
    const st = location.state as
      | { openMock?: boolean; mockSeed?: { sport: Sport; leagueId: string } | null }
      | null
    if (st?.openMock) {
      // The seed is set when the request came from a league's own rail ("Mock
      // it" on /leagues/:id/power, say) rather than from the global Menu row,
      // so the modal opens on that league instead of making someone pick the
      // one they were already looking at.
      openMockModal(st.mockSeed ?? undefined)
      navigate('/', { replace: true, state: null })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.key])

  function refetch() {
    getDrafts().then(setDrafts).catch((e) => setFetchError(e.message))
    getMockSessions().then(setMocks).catch(() => {}) // non-critical -- the picker still works without it
  }

  function refetchSleeperLeagues() {
    if (!user) return
    setSleeperError(null)
    getSleeperUserLeagues(user.sleeperUserId)
      .then((ls) => {
        setSleeperLeagues(ls)
        setSleeperError(null)
      })
      .catch((e) => {
        // Was a silent no-op. "Your leagues" does still work without this, but
        // swallowing the failure means someone whose league list is broken sees
        // an app confidently telling them they have nothing to set up -- which
        // is indistinguishable from the genuine empty state and gives them
        // nothing to retry. Say it failed instead.
        setSleeperError(e instanceof Error ? e.message : String(e))
      })
  }

  useEffect(refetch, [])
  useEffect(refetchSleeperLeagues, [user?.sleeperUserId])

  async function setupLeague(l: SleeperLeague) {
    const stages = setupStages(l)
    for (let i = 0; i < stages.length; i++) {
      setSetupState((prev) => ({ ...prev, [l.sleeperLeagueId]: { stageIndex: i, failedLabel: null } }))
      try {
        await stages[i].run()
      } catch (e) {
        setSetupState((prev) => ({
          ...prev,
          [l.sleeperLeagueId]: {
            stageIndex: i,
            failedLabel: `${stages[i].label}: ${e instanceof Error ? e.message : String(e)}`,
          },
        }))
        return
      }
    }
    setSetupState((prev) => {
      const next = { ...prev }
      delete next[l.sleeperLeagueId]
      return next
    })
    // The now-ingested league should move from "From Sleeper" to "Your
    // leagues" -- both lists have to refresh for that card to actually move.
    refetch()
    refetchSleeperLeagues()
  }

  // /track now runs one poll tick synchronously before it answers, so this is
  // also the status refresh -- one button doing both jobs. The number that
  // matters on draft night is seatsMapped: 0 means every seat in the room is a
  // league-average bot, and it is better to find that out here than at 8:15.
  async function track(sleeperDraftId: string) {
    setTracking(sleeperDraftId)
    setFetchError(null)
    try {
      const r = await trackDraft(sleeperDraftId)
      setTracked((prev) => ({ ...prev, [sleeperDraftId]: r }))
      refetch()
    } catch (e) {
      setTracked((prev) => ({
        ...prev,
        [sleeperDraftId]: { failed: e instanceof Error ? e.message : String(e) },
      }))
    } finally {
      setTracking(null)
    }
  }

  // The field says "link or ID" now, so a pasted Sleeper URL has to work --
  // https://sleeper.com/leagues/1391509063170293760/team is what is actually
  // in someone's clipboard, and asking them to cut the id out of it by hand is
  // the kind of chore this screen was full of. Sleeper ids are long digit
  // runs; nothing else in one of these URLs is, so the first one is the id.
  function leagueIdFrom(input: string): string {
    const trimmed = input.trim()
    if (/^\d+$/.test(trimmed)) return trimmed
    return trimmed.match(/\d{6,}/)?.[0] ?? trimmed
  }

  async function addDraft() {
    if (!leagueId.trim()) return
    setAdding(true)
    setAddError(null)
    try {
      await ingestLeague(leagueIdFrom(leagueId))
      setLeagueId('')
      refetch()
    } catch (e) {
      setAddError(e instanceof Error ? e.message : String(e))
    } finally {
      setAdding(false)
    }
  }

  function openMockModal(seed?: { sport?: Sport; leagueId?: string }) {
    setModalSeed({
      sport: seed?.sport ?? (sportFilter === 'all' ? 'nfl' : sportFilter),
      leagueId: seed?.leagueId ?? null,
    })
  }

  // Modal submit hands off to the existing seat-setup screen rather than
  // creating the session directly -- MockSetup's per-seat real-manager
  // assignment (claude/next-features-roadmap.md's manager-tendencies-and-
  // mock-seating work) is valued functionality this redesign must not skip.
  function startMock(opts: {
    sport: Sport
    teams: number
    rounds: number
    sourceSleeperLeagueId: string
    sourceLeagueName: string
  }) {
    setModalSeed(null)
    navigate('/mock/new', { state: opts })
  }

  const lineages = drafts ? leagueLineages(drafts) : []
  const counts = {
    all: lineages.length,
    nfl: lineages.filter((l) => l.current.sport === 'nfl').length,
    nba: lineages.filter((l) => l.current.sport === 'nba').length,
  }
  const visibleLineages = lineages.filter((l) => sportFilter === 'all' || l.current.sport === sportFilter)
  const mockableLeagues: MockableLeague[] = lineages.map((l) => ({
    sleeperLeagueId: l.current.sleeperLeagueId,
    leagueName: l.current.leagueName,
    sport: l.current.sport,
    teams: l.current.teams,
    rounds: l.current.rounds,
    season: l.current.season,
  }))

  // Mocks now carry their own sport, so the sidebar filter scopes them the
  // same way it scopes the leagues grid. This used to blank the whole list
  // under the NBA filter, because every mock was football and there was no
  // field to filter on.
  const visibleMocks =
    sportFilter === 'all' ? mocks : mocks?.filter((m) => m.sport === sportFilter)
  const visibleSleeperLeagues =
    sportFilter === 'nba' ? [] : sleeperLeagues?.filter((l) => sportFilter === 'all' || l.sport === sportFilter)

  // The hero's whole point is to ground "start a mock draft" in something
  // real instead of a generic empty-state sentence. `drafts` is already
  // newest-first off the backend (DraftRepository.allWithLeague: start_time
  // desc, season desc, id desc), so the first row IS "the league you were
  // last looking at" -- no separate last-used tracking to build or fake.
  const recentLeague = drafts && drafts.length > 0 ? drafts[0] : null

  return (
    <>
      {/* The sport filter was part of the rail's own markup while the rail was
          Home-only. It is a page control living in global chrome, so the
          promotion (claude/site-wide-shell-propagation.md §C step 2) moved it
          out: the rail exposes a context region and Home portals into it. The
          counts come from `drafts`, which only this page fetches -- pushing
          that fetch up into the shell so the rail could own the filter is the
          trade this avoids. */}
      {rail.node &&
        createPortal(
          <SportFilterRail
            sportFilter={sportFilter}
            onSportFilterChange={setSportFilter}
            counts={counts}
            collapsed={rail.collapsed}
          />,
          rail.node,
        )}

      <div className="content home-content">
        <PageHeader
          eyebrow="Viewing"
          title={sportTitle(sportFilter)}
          sub={
            drafts == null ? (
              'Bots fill every seat but yours, and you take your own picks on your turn.'
            ) : recentLeague ? (
              <>
                <strong>{recentLeague.leagueName}</strong> is a {recentLeague.teams}-team,{' '}
                {recentLeague.rounds}-round league — get more reps before its next draft, in a
                room only you control.
              </>
            ) : (
              'Bots fill every seat but yours — add a league below to seat your real managers instead of them.'
            )
          }
          actions={
            <button type="button" className="home-hero-cta" onClick={() => openMockModal()}>
              Start a mock draft
            </button>
          }
        />

        <section className="panel">
          <div className="panel-head">
            <h2>Your leagues</h2>
          </div>

          {fetchError && <div className="error">{fetchError}</div>}

          {/* null is "still fetching", [] is "genuinely none" -- the two used to
              render identically (nothing), so an empty heading sat over a fetch
              in flight and then rows appeared under it. */}
          {drafts == null && <SkeletonRows count={2} label="Loading your leagues" />}

          {drafts && visibleLineages.length === 0 && (
            <p className="muted">
              {/* First-run state (§5c): a genuinely empty "Your leagues" reads as
                  a failed fetch unless it says what to do next -- and when
                  "From Sleeper" already has something, the honest next step is
                  that list, not the manual add-by-id form further down. */}
              {sportFilter !== 'all'
                ? `No ${sportFilter.toUpperCase()} leagues yet.`
                : sleeperLeagues && sleeperLeagues.some((l) => !l.ingested)
                  ? 'No leagues set up yet — pick one from Sleeper below.'
                  : 'No leagues yet — add one below.'}
            </p>
          )}

          {drafts && visibleLineages.length > 0 && (
            // Cards, not `.draft-list` rows. A row was right while a league was
            // one line of content -- name, season, size, status. It now carries
            // an identity, a draft, a history and a power ranking, and the row
            // answered that by growing a column of three identical chips 1400px
            // from the league's own name. See styles.css DENSITY: re-judge the
            // shape when the content changes rather than defending the old call.
            <div className="league-grid">
              {visibleLineages.map(({ current: d, seasons }) => {
                // draft.status is nullable in the DB. Reading .replace() off it
                // threw a TypeError during render, and with no error boundary
                // above this component that took out the entire picker screen --
                // one unstarted draft row was enough to make the app unusable.
                const status = d.status ?? 'unknown'
                const t = tracked[d.sleeperDraftId]
                const live = status === 'pre_draft' || status === 'drafting'
                const complete = status === 'complete'
                // Hashed on the NAME, not the id: every season of a league is a
                // separate Sleeper league id, so hashing the id gave the two
                // "(Foot) Ball Knowers" cards different colors -- the exact
                // opposite of what the crest is for.
                const hue = hueForName(d.leagueName)
                const crest = crestLetter(d.leagueName)
                return (
                  <article key={d.sleeperLeagueId} className={`league-card${live ? ' live' : ''}`}>
                    <header className="league-card-head">
                      {/* Same hue-derived crest the board's column headers and
                          the manager cards use, so two seasons of one league
                          read as one league instead of two identical strings. */}
                      <span
                        className="avatar league-crest"
                        style={{ background: `oklch(30% 0.05 ${hue})`, color: `oklch(84% 0.12 ${hue})` }}
                        aria-hidden="true"
                      >
                        {crest}
                      </span>
                      <span className="league-card-title">
                        <Link to={draftRoute(d)} className="league-card-name">
                          {d.leagueName}
                        </Link>
                        <span className="league-card-sub">
                          {/* The sport pill -- load-bearing now that the sidebar
                              filter mixes both sports in "All sports". */}
                          <span className={`sport-pill ${d.sport}`}>{d.sport.toUpperCase()}</span>
                          {d.teams} managers &middot; {d.rounds} rounds
                        </span>
                      </span>
                      <span className="league-card-season cond">{d.season}</span>
                    </header>

                    {/* Every season of a Sleeper league is its own league object,
                        so this list used to render one card per season -- two
                        "West Coast Fantasy Football" cards side by side, each
                        carrying an identical History and Power rankings link to
                        the same two pages. Those are league-scoped and walk the
                        whole chain themselves; only the draft board is per
                        season. So one card per league, and the older seasons
                        become links to their own boards. */}
                    {seasons.length > 1 && (
                      <div className="league-seasons">
                        <span className="league-seasons-label">Seasons</span>
                        {seasons.map((s) => (
                          <Link
                            key={s.sleeperLeagueId}
                            to={draftRoute(s)}
                            className={`league-season-link${s.sleeperLeagueId === d.sleeperLeagueId ? ' on' : ''}`}
                            title={`${s.season} ${s.status === 'complete' ? 'draft board' : 'mock draft'} · ${s.teams} managers`}
                          >
                            {s.season}
                          </Link>
                        ))}
                      </div>
                    )}

                    <div className="league-card-links">
                      {/* One primary destination, then two peers -- not three
                          identical chips. The draft is what this app is for.
                          A complete draft has real picks to show; anything else
                          -- live or not yet drafted -- has none yet, so it opens
                          the simulator (mock the room from its own tendencies)
                          instead of an empty "come back later" screen. */}
                      <Link className="league-link primary" to={draftRoute(d)}>
                        {live ? 'Draft room' : complete ? 'Draft board' : 'Mock draft'}
                      </Link>
                      {/* Both sports since claude/nba-power-rankings.md. These
                          used to be football-only because power rankings'
                          wire format was football-shaped down to the NFL
                          week/season fields it returned; it no longer is, and
                          /history never was. */}
                      <Link className="league-link" to={`/leagues/${d.sleeperLeagueId}/history`}>
                        History
                      </Link>
                      <Link className="league-link" to={`/leagues/${d.sleeperLeagueId}/power`}>
                        Power rankings
                      </Link>
                      {/* claude/league-analysis.md, and football-only unlike its
                          two neighbours: this page's roster projections read
                          Sleeper's pts_ppr, which has no basketball equivalent.
                          A third peer rather than a fourth chip competing with
                          the primary -- Analysis is the other half of Power
                          rankings and belongs beside it. */}
                      {d.sport === 'nfl' && (
                        <Link className="league-link" to={`/leagues/${d.sleeperLeagueId}/analysis`}>
                          Analysis
                        </Link>
                      )}
                    </div>

                    <footer className="league-card-foot">
                      {/* A finished draft is the resting state and says so
                          quietly; one that is live or about to be is the only
                          thing on this card worth an accent. */}
                      {live ? (
                        <Link className="league-card-live" to={`/drafts/${d.sleeperDraftId}/live`}>
                          <span className="league-live-dot" />
                          {status === 'drafting' ? 'Drafting now — follow live' : 'Draft not started — follow live'}
                        </Link>
                      ) : (
                        <span className="league-card-status">Draft complete</span>
                      )}

                      <span className="league-card-foot-spacer" />

                      {t &&
                        ('failed' in t ? (
                          <span className="tiny track-note failed">{t.failed}</span>
                        ) : typeof t.seatsMapped !== 'number' ? (
                          // A backend older than the seatsMapped field answers
                          // 200 with it simply absent. Say what came back rather
                          // than rendering "undefined/undefined seats mapped" --
                          // verified live 2026-09-02 against a pre-restart 8080.
                          <span className="tiny track-note">{t.status ?? 'unknown'} · no seat count from this backend</span>
                        ) : (
                          // "12/14 seats mapped" described a data structure. What
                          // the reader needs on draft night is whether any seat is
                          // still an unmodelled league-average bot -- so the full
                          // house says so quietly and anything short of it is the
                          // thing that stands out.
                          <span className={`tiny track-note${t.seatsMapped < t.teams ? ' failed' : ''}`}>
                            {t.seatsMapped === t.teams
                              ? `All ${t.teams} managers identified`
                              : `Only ${t.seatsMapped} of ${t.teams} managers identified`}
                            {/* observed: false means Sleeper was unreachable and
                                `status` is the stale DB value -- label it rather
                                than showing it as fact. */}
                            {t.observed === false ? ' · stale' : ''}
                          </span>
                        ))}

                      <button
                        className="league-card-refresh"
                        onClick={() => track(d.sleeperDraftId)}
                        disabled={tracking === d.sleeperDraftId}
                        title="Check Sleeper now and refresh this draft's status and seat mapping"
                      >
                        {tracking === d.sleeperDraftId ? 'Checking…' : 'Refresh'}
                      </button>

                      {/* Every league's own fast path into the modal, preset to
                          its sport and itself -- design_handoff_multisport_
                          mock_drafts's "fastest correct path" for starting a
                          mock. */}
                      <button
                        type="button"
                        className="league-card-mockit"
                        onClick={() => openMockModal({ sport: d.sport, leagueId: d.sleeperLeagueId })}
                      >
                        Mock it
                      </button>
                    </footer>
                  </article>
                )
              })}
            </div>
          )}
        </section>

        {/* The list failed rather than came back empty. Without this the section
            below simply doesn't render, so a broken lookup and "you have nothing
            to set up" look identical. */}
        {sleeperError && (
          <section className="panel">
            <div className="panel-head">
              <h2>From Sleeper</h2>
            </div>
            <div className="error">
              Couldn’t load your Sleeper leagues ({sleeperError}).{' '}
              <button className="link-button" onClick={refetchSleeperLeagues}>
                Try again
              </button>
            </div>
          </section>
        )}

        {/* §5c: leagues Sleeper says this user belongs to that have no `league`
            row here yet. Same card shape as "Your leagues" (crest, sport pill,
            size) so the two lists read as one thing rather than a second,
            different-looking screen -- only omitted entirely once it's known
            there's nothing to show, same null-vs-empty distinction as above.
            Hidden for the NBA filter per design_handoff_multisport_mock_drafts
            ("shown for All sports and NFL only"). */}
        {visibleSleeperLeagues && visibleSleeperLeagues.some((l) => !l.ingested) && (
          <section className="panel">
            <div className="panel-head">
              <h2>From Sleeper</h2>
            </div>
            <div className="league-grid">
              {visibleSleeperLeagues
                .filter((l) => !l.ingested)
                .map((l) => {
                  const hue = hueForName(l.name)
                  const crest = crestLetter(l.name)
                  const setup = setupState[l.sleeperLeagueId]
                  return (
                    <article key={l.sleeperLeagueId} className="league-card">
                      <header className="league-card-head">
                        <span
                          className="avatar league-crest"
                          style={{ background: `oklch(30% 0.05 ${hue})`, color: `oklch(84% 0.12 ${hue})` }}
                          aria-hidden="true"
                        >
                          {crest}
                        </span>
                        <span className="league-card-title">
                          <span className="league-card-name">{l.name}</span>
                          <span className="league-card-sub">
                            <span className={`sport-pill ${l.sport}`}>{l.sport.toUpperCase()}</span>
                            {l.totalRosters} managers
                          </span>
                        </span>
                        <span className="league-card-season cond">{l.season}</span>
                      </header>

                      {setup ? (
                        // In-place progress card (§5d) rather than a spinner --
                        // naming the stage is what turns a 2-4s wait into
                        // something visibly working instead of a frozen button.
                        <div className="league-card-links">
                          {setup.failedLabel ? (
                            <>
                              <span className="tiny track-note failed">{setup.failedLabel}</span>
                              <button className="league-link primary" onClick={() => setupLeague(l)}>
                                Retry
                              </button>
                            </>
                          ) : (
                            <span className="tiny track-note">
                              {setupStages(l)[setup.stageIndex]?.label ?? 'Setting up'}…
                            </span>
                          )}
                        </div>
                      ) : (
                        <div className="league-card-links">
                          <button className="league-link primary" onClick={() => setupLeague(l)}>
                            Set up
                          </button>
                        </div>
                      )}
                    </article>
                  )
                })}
            </div>
          </section>
        )}

        <section className="panel">
          <div className="panel-head">
            <h2>Mock drafts</h2>
            <button type="button" className="chip on" onClick={() => openMockModal()}>
              New mock
            </button>
          </div>

          {mocks == null && <SkeletonRows count={2} label="Loading your mock drafts" />}

          {visibleMocks && visibleMocks.length === 0 && (
            <p className="muted">
              {sportFilter === 'nba'
                ? "NBA mock drafts aren't available yet."
                : 'No mock drafts yet — start one above.'}
            </p>
          )}

          {visibleMocks && visibleMocks.length > 0 && (
            <div className="draft-list mock-list">
              {visibleMocks.map((m) => {
                const totalPicks = m.teams * m.rounds
                const picksMade = m.status === 'COMPLETE' ? totalPicks : Math.max(0, m.currentPickNo - 1)
                const pct = Math.round((picksMade / totalPicks) * 100)
                const barPct = Math.max(pct, picksMade > 0 || m.status === 'COMPLETE' ? 3 : 0)
                return (
                  <Link key={m.id} to={`/mock/${m.id}`} className="draft-row mock-row">
                    <span className={`sport-pill ${m.sport} mock-row-sport`}>{m.sport.toUpperCase()}</span>
                    <span className="mock-row-identity">
                      <span className="draft-row-league">
                        {m.teams}-team {m.sport.toUpperCase()} mock
                        {m.sourceLeagueName ? ` · ${m.sourceLeagueName}` : ''}
                      </span>
                      <span className="muted small">
                        {m.rounds} rounds · your pick {roundPickLabel(m.userSlot, m.teams)} ·{' '}
                        {m.status === 'COMPLETE'
                          ? `finished at ${roundPickLabel(totalPicks, m.teams)}`
                          : `paused at ${roundPickLabel(Math.max(1, m.currentPickNo - 1), m.teams)}`}
                      </span>
                    </span>
                    <span className="mock-row-progress">
                      <span className="mock-row-progress-track">
                        <span
                          className={`mock-row-progress-fill${m.status === 'COMPLETE' ? ' complete' : ''}`}
                          style={{ width: `${barPct}%` }}
                        />
                      </span>
                      <span className="muted tiny">
                        {picksMade}/{totalPicks} picks · {pct}%
                      </span>
                    </span>
                    <span className="mock-row-time muted small">{relativeTime(m.createdAt)}</span>
                    <span className={`chip mock-row-cta${m.status === 'COMPLETE' ? '' : ' on'}`}>
                      {m.status === 'COMPLETE' ? 'View board' : 'Resume'}
                    </span>
                  </Link>
                )
              })}
            </div>
          )}
        </section>

        {/* B1: "Add a league" was its own panel, a third of the front door
            given to a chore almost nobody does more than a few times a year.
            A native <details> gets the collapse/expand behaviour, focus
            handling and no-JS-state cost for free -- there's no reason to
            reach for a controlled `open` boolean when the browser already
            does this correctly. */}
        <details className="panel add-draft league-data">
          <summary className="league-data-summary">
            <span>League data</span>
            <span className="muted small">Add a Sleeper league by link or ID</span>
          </summary>
          <p className="muted small">
            Paste a Sleeper league link or ID to add its draft history. Load the player pool
            and board separately first if this is a brand new install.
          </p>
          {addError && <div className="error">{addError}</div>}
          <div className="controls">
            <label>
              Sleeper league link or ID
              <input
                value={leagueId}
                onChange={(e) => setLeagueId(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && addDraft()}
                size={22}
              />
            </label>
            <button onClick={addDraft} disabled={adding || !leagueId.trim()}>
              {adding ? 'Adding…' : 'Add league'}
            </button>
          </div>
        </details>
      </div>

      {modalSeed && (
        <StartMockModal
          leagues={mockableLeagues}
          initialSport={modalSeed.sport}
          initialLeagueId={modalSeed.leagueId}
          onClose={() => setModalSeed(null)}
          onStart={startMock}
        />
      )}
    </>
  )
}

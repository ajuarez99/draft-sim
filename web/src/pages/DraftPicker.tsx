import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { hueFor } from '../hue'
import { leagueLineages } from '../leagueLineage'
import { roundPickLabel } from '../roundPickLabel'
import { SkeletonRows } from '../components/Skeleton'
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

export default function DraftPicker() {
  const user = useUser()
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

  // The hero's whole point is to ground "start a mock draft" in something
  // real instead of a generic empty-state sentence. `drafts` is already
  // newest-first off the backend (DraftRepository.allWithLeague: start_time
  // desc, season desc, id desc), so the first row IS "the league you were
  // last looking at" -- no separate last-used tracking to build or fake.
  const recentLeague = drafts && drafts.length > 0 ? drafts[0] : null

  return (
    <div className="content">
      {/* B1: this screen was three panels of equal weight and no primary
          action -- the only real CTA ("New mock draft") was a small pill
          200px down inside the middle panel. This hero says what a visitor's
          next click actually is and grounds it in the league they were last
          looking at, using its real name/size/rounds rather than a generic
          "get started" line. */}
      <section className="panel home-hero">
        <div className="home-hero-body">
          <p className="home-hero-kicker cond">Practice round</p>
          {/* h2, not h1: App.tsx's wordmark is the page's h1 and there can
              only be one. Every other section on this screen is an h2, and
              the hero is a section like the rest -- it is sized by its own
              class, not by outranking them in the outline. */}
          <h2 className="home-hero-title">Start a mock draft</h2>
          <p className="home-hero-sub">
            {drafts == null ? (
              // Still fetching -- say the generic version rather than
              // guessing at a league that might not be the one that lands.
              'Bots fill every seat but yours, and you take your own picks on your turn.'
            ) : recentLeague ? (
              <>
                <strong>{recentLeague.leagueName}</strong> is a {recentLeague.teams}-team,{' '}
                {recentLeague.rounds}-round league — get more reps before its next draft, in a
                room only you control.
              </>
            ) : (
              'Bots fill every seat but yours — add a league below to seat your real managers instead of them.'
            )}
          </p>
        </div>
        {/* Same destination as the "New mock draft" pill below, on purpose --
            that pill stays where it is because it's the right contextual
            action once you're already looking at the mock-drafts list. This
            is the one a first-time or returning visitor actually sees first. */}
        <Link className="home-hero-cta" to="/mock/new">
          Start a mock draft
        </Link>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2>Your leagues</h2>
        </div>

        {fetchError && <div className="error">{fetchError}</div>}

        {/* null is "still fetching", [] is "genuinely none" -- the two used to
            render identically (nothing), so an empty heading sat over a fetch
            in flight and then rows appeared under it. */}
        {drafts == null && <SkeletonRows count={2} label="Loading your leagues" />}

        {drafts && drafts.length === 0 && (
          <p className="muted">
            {/* First-run state (§5c): a genuinely empty "Your leagues" reads as
                a failed fetch unless it says what to do next -- and when
                "From Sleeper" already has something, the honest next step is
                that list, not the manual add-by-id form further down. */}
            {sleeperLeagues && sleeperLeagues.some((l) => !l.ingested)
              ? 'No leagues set up yet — pick one from Sleeper below.'
              : 'No leagues yet — add one below.'}
          </p>
        )}

        {drafts && drafts.length > 0 && (
          // Cards, not `.draft-list` rows. A row was right while a league was
          // one line of content -- name, season, size, status. It now carries
          // an identity, a draft, a history and a power ranking, and the row
          // answered that by growing a column of three identical chips 1400px
          // from the league's own name. See styles.css DENSITY: re-judge the
          // shape when the content changes rather than defending the old call.
          <div className="league-grid">
            {leagueLineages(drafts).map(({ current: d, seasons }) => {
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
              const hue = hueFor(d.leagueName)
              // Leading punctuation is common in league names ("(Foot) Ball
              // Knowers" would crest as "("), so take the first character that
              // actually carries identity.
              const crest = (d.leagueName.match(/[\p{L}\p{N}]/u)?.[0] ?? '?').toUpperCase()
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
                        {/* The sport pill. This list is deliberately one mixed
                            list with no switcher (multi-sport-and-rebrand.md
                            Phase 6), which only works if each card says what
                            it is -- "Ball Knowers" and "(Foot) Ball Knowers"
                            are two real leagues in two different sports, and
                            without this the reader tells them apart by
                            spotting a parenthesis. It sits in the sub-line
                            rather than beside the season because it qualifies
                            the shape below it: 14 rounds means something
                            different in each sport. */}
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
                    {/* Football only, and hidden rather than disabled -- a
                        greyed-out link invites a click and then explains
                        itself; an absent one just isn't a promise.

                        Power rankings are football-shaped down to the wire
                        format: /api/leagues/{id}/power returns a field
                        literally named `nflState` carrying the NFL week and
                        season. History is closer to sport-agnostic (standings
                        are wins, losses and points), but its own drill-down,
                        /api/managers/{id}/history, calls fit(Sport.NFL)
                        unconditionally -- so an NBA standings row would link
                        to that manager's FOOTBALL reach bias and tilt, and ten
                        of twelve managers here are the same Sleeper id in both
                        leagues. Both come back the moment that endpoint takes
                        a sport; see claude/merge-review-multi-sport.md S1/S2.

                        Today the NBA league also has no ingested standings, so
                        these two led to three empty season headers and an
                        empty table. */}
                    {d.sport === 'nfl' && (
                      <>
                        <Link className="league-link" to={`/leagues/${d.sleeperLeagueId}/history`}>
                          History
                        </Link>
                        <Link className="league-link" to={`/leagues/${d.sleeperLeagueId}/power`}>
                          Power rankings
                        </Link>
                      </>
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
          there's nothing to show, same null-vs-empty distinction as above. */}
      {sleeperLeagues && sleeperLeagues.some((l) => !l.ingested) && (
        <section className="panel">
          <div className="panel-head">
            <h2>From Sleeper</h2>
          </div>
          <div className="league-grid">
            {sleeperLeagues
              .filter((l) => !l.ingested)
              .map((l) => {
                const hue = hueFor(l.name)
                const crest = (l.name.match(/[\p{L}\p{N}]/u)?.[0] ?? '?').toUpperCase()
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
          <Link className="chip on" to="/mock/new">
            New mock draft
          </Link>
        </div>

        {mocks == null && <SkeletonRows count={2} label="Loading your mock drafts" />}

        {mocks && mocks.length === 0 && <p className="muted">No mock drafts yet — start one above.</p>}

        {mocks && mocks.length > 0 && (
          <div className="draft-list">
            {mocks.map((m) => (
              <Link key={m.id} to={`/mock/${m.id}`} className="draft-row">
                {/* "Mock draft #7" is only distinguishable from "#8" by a
                    number the reader never chose. Size, seat and how far it
                    got are what actually tell two saved mocks apart. */}
                <span className="draft-row-league">
                  {m.teams}-team mock <span className="muted small">from {roundPickLabel(m.userSlot, m.teams)}</span>
                </span>
                <span className={`chip status-${m.status === 'COMPLETE' ? 'complete' : 'drafting'}`}>
                  {m.status === 'COMPLETE'
                    ? 'Complete'
                    : `through ${roundPickLabel(Math.max(1, m.currentPickNo - 1), m.teams)}`}
                </span>
              </Link>
            ))}
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
  )
}

import { useEffect, type ComponentType, type ReactNode } from 'react'
import { Route, Routes, useParams } from 'react-router-dom'
import SignIn from './pages/SignIn'
import SeasonPage from './pages/SeasonPage'
import SportIndex from './pages/SportIndex'
import DailyPage from './pages/DailyPage'
import RedraftNotFound from './components/RedraftNotFound'
import DraftPicker from './pages/DraftPicker'
import DraftView from './pages/DraftView'
import CompletedDraftBoard from './pages/CompletedDraftBoard'
import LiveDraftView from './pages/LiveDraftView'
import MockSetup from './pages/MockSetup'
import MockDraftView from './pages/MockDraftView'
import ManagerTendencies from './pages/ManagerTendencies'
import HistoricalDraft from './pages/HistoricalDraft'
import HistoricalRoom from './pages/HistoricalRoom'
import LeagueHome from './pages/LeagueHome'
import LeagueHistory from './pages/LeagueHistory'
import PowerRankings from './pages/PowerRankings'
import LeagueAnalysis from './pages/LeagueAnalysis'
import RosterManagement from './pages/RosterManagement'
import ExpectedWins from './pages/ExpectedWins'
import ScheduleGrid from './pages/ScheduleGrid'
import Rivalries from './pages/Rivalries'
import WeeklyPot from './pages/WeeklyPot'
import AdpCompare from './pages/AdpCompare'
import StatCalculator from './pages/StatCalculator'
import StatLeaderboard from './pages/StatLeaderboard'
import PlayerTrends from './pages/PlayerTrends'
import PlayerPage from './pages/PlayerPage'
import PlayerCompare from './pages/PlayerCompare'
import SeasonForecast from './pages/SeasonForecast'
import WeeklyReport from './pages/WeeklyReport'
import Superlatives from './pages/Superlatives'
import ManagerHistory from './pages/ManagerHistory'
import ManagerComparison from './pages/ManagerComparison'
import AdminDigest from './pages/AdminDigest'
import AdminUsage from './pages/AdminUsage'
import AppShell from './components/AppShell'
import TimeMachineShell from './components/TimeMachineShell'
import CrossSiteRedirect from './components/CrossSiteRedirect'
import InAppRedirect from './components/InAppRedirect'
import NotFound from './components/NotFound'
import ErrorBoundary from './components/ErrorBoundary'
import PlatformGate from './components/PlatformGate'
import { useUser } from './user'
import { useUnlocked } from './leaguesLock'
import { useIdleLock } from './useIdleLock'
import LeaguesLock from './components/LeaguesLock'
import { title as landingTitle } from './landingCopy'
import { tmGame, tmHome } from './site'
import { currentSite } from './siteEnv'

// Forces a full remount of DraftView on every draft change. Without this,
// React Router does not remount on a :draftId param change alone -- result/
// userPicks/resimming/requestSeqRef would all persist across a draft switch,
// so a resim left in flight for the old draft could land later and paint its
// board onto the new draft's now-open screen. See
// claude/reactive-resimulation.md §3, "Cross-draft leak".
function KeyedDraftView() {
  const { draftId } = useParams<{ draftId: string }>()
  return <PlatformGate page="draftRoom" draftId={draftId}><DraftView key={draftId} /></PlatformGate>
}

// Same remount wrapper, same reason: a param-only change would keep the old
// draft's fetched board/seats alive across the switch.
function KeyedCompletedDraftBoard() {
  const { draftId } = useParams<{ draftId: string }>()
  return <PlatformGate page="draftRoom" draftId={draftId}><CompletedDraftBoard key={draftId} /></PlatformGate>
}

// Same remount wrapper, same reason: LiveDraftView holds a simulation in
// flight (requestSeqRef/result) and an EventSource keyed on draftId, and a
// param-only change would keep every one of them alive across the switch --
// an in-flight resim for the old draft could land and paint onto the new one.
function KeyedLiveDraftView() {
  const { draftId } = useParams<{ draftId: string }>()
  return <PlatformGate page="draftRoom" draftId={draftId}><LiveDraftView key={draftId} /></PlatformGate>
}

// Same remount wrapper, same reason: a param-only change would keep the old
// session's fetched state and in-flight submitPick call alive across the switch.
function KeyedMockDraftView() {
  const { sessionId } = useParams<{ sessionId: string }>()
  return <MockDraftView key={sessionId} />
}

// Spec 074: a season page is keyed by its slug, so a client-side move from one season to the next starts clean
// (the embedded copy of the first one must not be shown for the second).
function KeyedSeasonPage({ sport }: { sport: 'NBA' | 'NFL' }) {
  const { season = '' } = useParams<{ season: string }>()
  return <SeasonPage key={`${sport}/${season}`} sport={sport} slug={season} />
}

// Spec 053: same reason for the historical room -- a param-only change must not keep the old game's state alive.
function KeyedHistoricalRoom() {
  const { gameId } = useParams<{ gameId: string }>()
  return <HistoricalRoom key={gameId} />
}

// One generic remount wrapper for every /leagues/:sleeperLeagueId/* page. The
// rail's year links (spec 011) switch seasons on the SAME route --
// /leagues/<2026>/expected-wins -> /leagues/<2025>/expected-wins -- and an
// unkeyed route element is reused across a param-only change: the old season's
// data stays up while the new one loads, an error banner can sit over the other
// season's numbers, and an effect still in flight for the old id (LeagueAnalysis
// has a few) can land late and overwrite the new season. Keying on the id gives
// the same clean slate the draft routes above get.
//
// `gate` (spec 035 T042) is an availability page key: the page renders only if the server's
// LeagueAvailability says this league can show it, otherwise the platform message does. The keys are the
// server's (LeagueAvailability.ROUTES); this table only says which page uses which.
function KeyedByLeague({ page: Page, gate }: { page: ComponentType; gate?: string }) {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const page = <Page key={sleeperLeagueId} />
  if (!gate) return page
  return <PlatformGate page={gate} leagueKey={sleeperLeagueId}>{page}</PlatformGate>
}

// The player page is addressed by a league AND a player, so it is keyed on both (N4).
function KeyedByLeagueAndPlayer({ page: Page, gate }: { page: ComponentType; gate?: string }) {
  const { sleeperLeagueId, sleeperPlayerId } = useParams<{ sleeperLeagueId: string; sleeperPlayerId: string }>()
  const page = <Page key={`${sleeperLeagueId}:${sleeperPlayerId}`} />
  if (!gate) return page
  return <PlatformGate page={gate} leagueKey={sleeperLeagueId}>{page}</PlatformGate>
}

// Spec 073: three route trees, one per build mode (VITE_SITE). Each is a <Routes>; analytics.test.ts reads
// the regions between the @routes markers and checks each tree's <Route path> values against that site's
// route table (analytics.ts). Every path is a string literal on its own <Route> line, because that test
// parses this file's text. Redirect routes sit OUTSIDE each tree's shell and sign-in gate: a signed-out
// visitor holding an old link is not asked to sign in just to be sent away, and a redirecting path sends
// no page view (the shells' effects are inside).

// Every league page, written once and shared by the combined tree and the league tree.
// @routes:league-pages begin
const leaguePages = (
  <>
    <Route path="/" element={<DraftPicker />} />
    <Route path="/drafts/:draftId" element={<KeyedDraftView />} />
    <Route path="/drafts/:draftId/board" element={<KeyedCompletedDraftBoard />} />
    <Route path="/drafts/:draftId/live" element={<KeyedLiveDraftView />} />
    <Route path="/mock/new" element={<MockSetup />} />
    <Route path="/mock/:sessionId" element={<KeyedMockDraftView />} />
    <Route path="/managers" element={<ManagerTendencies />} />
    <Route path="/managers/:managerId/history" element={<ManagerHistory />} />
    {/* specs/006-deeper-history-both-sports US4: manager-scoped, not
        league-scoped -- addressed by two manager ids, not a league,
        so it lives beside /managers/:managerId/history rather than in
        destinations.ts. That registry's own LeagueDestination.href
        takes a LeagueContext (a lineage + a viewed season); a
        comparison between two people has neither, and
        destinations.test.ts's "does not claim routes that belong to
        no league" already asserts /managers/... routes match no row
        there (contracts/head-to-head-api.md's own wording talks
        about destinations.ts, but the registry it names is strictly
        for pages reached from inside one league -- see that file's
        own header comment). */}
    <Route path="/managers/:aId/versus/:bId" element={<ManagerComparison />} />
    <Route path="/leagues/:sleeperLeagueId" element={<KeyedByLeague page={LeagueHome} />} />
    <Route path="/leagues/:sleeperLeagueId/history" element={<KeyedByLeague page={LeagueHistory} />} />
    <Route path="/leagues/:sleeperLeagueId/power" element={<KeyedByLeague page={PowerRankings} />} />
    <Route path="/leagues/:sleeperLeagueId/analysis" element={<KeyedByLeague page={LeagueAnalysis} gate="analysis" />} />
    <Route path="/leagues/:sleeperLeagueId/schedule" element={<KeyedByLeague page={ScheduleGrid} gate="schedule" />} />
    <Route path="/leagues/:sleeperLeagueId/rivalries" element={<KeyedByLeague page={Rivalries} />} />
    {/* Spec 046: no gate on purpose -- the ADP sources are sport-wide, so an ESPN NBA league gets the compare
        half too (the auction half needs a Sleeper draft and says so itself). */}
    <Route path="/leagues/:sleeperLeagueId/adp" element={<KeyedByLeague page={AdpCompare} />} />
    <Route path="/leagues/:sleeperLeagueId/trends" element={<KeyedByLeague page={PlayerTrends} gate="stats" />} />
    <Route path="/leagues/:sleeperLeagueId/stats" element={<KeyedByLeague page={StatLeaderboard} gate="stats" />} />
    {/* specs/032: the stat calculator, a sibling view of Player stats (same rail row). */}
    <Route path="/leagues/:sleeperLeagueId/stats/calculator" element={<KeyedByLeague page={StatCalculator} gate="stats" />} />
    {/* specs/022: addressed by a league AND a player, so it is keyed on both (N4):
        moving from one player to another is a fresh page, not a re-render that
        leaves the last player's numbers up while the next one loads. */}
    <Route
      path="/leagues/:sleeperLeagueId/players/:sleeperPlayerId"
      element={<KeyedByLeagueAndPlayer page={PlayerPage} gate="stats" />}
    />
    {/* specs/049: two players side by side. Addressed by a league; the players ride in ?a=&b=. */}
    <Route path="/leagues/:sleeperLeagueId/compare" element={<KeyedByLeague page={PlayerCompare} gate="stats" />} />
    <Route
      path="/leagues/:sleeperLeagueId/roster-management"
      element={<KeyedByLeague page={RosterManagement} />}
    />
    <Route
      path="/leagues/:sleeperLeagueId/expected-wins"
      element={<KeyedByLeague page={ExpectedWins} gate="expectedWins" />}
    />
    <Route
      path="/leagues/:sleeperLeagueId/forecast"
      element={<KeyedByLeague page={SeasonForecast} gate="forecast" />}
    />
    <Route
      path="/leagues/:sleeperLeagueId/weekly-report"
      element={<KeyedByLeague page={WeeklyReport} gate="weeklyReport" />}
    />
    <Route
      path="/leagues/:sleeperLeagueId/superlatives"
      element={<KeyedByLeague page={Superlatives} gate="superlatives" />}
    />
    <Route
      path="/leagues/:sleeperLeagueId/weekly-pot"
      element={<KeyedByLeague page={WeeklyPot} gate="weeklyPot" />}
    />
    {/* Spec 043: unlisted on purpose (no nav link anywhere); the backend's admin token is the gate. */}
    <Route path="/admin/digest" element={<AdminDigest />} />
    {/* Spec 076: the owner's usage counts, unlisted the same way. */}
    <Route path="/admin/usage" element={<AdminUsage />} />
  </>
)
// @routes:league-pages end

/**
 * The league tree's chrome: the shell, the signed-in gate and the error boundary.
 */
function LeagueGate({ children }: { children: ReactNode }) {
  const user = useUser()
  // One shell for the whole site. The horizontal `.top` header this replaced
  // was suppressed on "/" only (`0c6f0ab`), which left the app with two
  // different chromes split one route wide -- see
  // claude/site-wide-shell-propagation.md §A. AppShell renders nothing but
  // its children while signed out, so SignIn is still the whole screen.
  //
  // AppShell wraps `<Routes>` rather than sitting inside a route element: the
  // Keyed* wrappers above exist to force remounts, and the shell must
  // not be caught up in them.
  return (
    <div className="app">
      <AppShell>
        {user ? (
          // Inside AppShell, outside <Routes>: a page that throws while
          // rendering keeps the rail and the league switcher on screen, so
          // there is still a way out. Before this, one TypeError blanked the
          // entire document.
          <ErrorBoundary>{children}</ErrorBoundary>
        ) : (
          <SignIn />
        )}
      </AppShell>
    </div>
  )
}

// The single site as it was before the split, plus one in-app redirect: `/game/:gameId` -> `/history/:gameId`,
// so a Time Machine link forwarded during the rollout window (or after a rollback) still lands (PR-06).
// @routes:combined begin
function CombinedTree() {
  return (
    <Routes>
      <Route path="/game/:gameId" element={<InAppRedirect to={(p) => tmGame(currentSite(), p.gameId ?? '')} />} />
      <Route
        path="*"
        element={
          <LeagueGate>
            <Routes>
              {leaguePages}
              <Route path="/history" element={<HistoricalDraft />} />
              <Route path="/history/:gameId" element={<KeyedHistoricalRoom />} />
              {/* React Router's own fallback renders a bare "Not Found" with no
                  way back -- on a mistyped draft id that was the whole screen. */}
              <Route path="*" element={<NotFound what="page" />} />
            </Routes>
          </LeagueGate>
        }
      />
    </Routes>
  )
}
// @routes:combined end

// The league site: the single site without the Time Machine. Its old Time Machine paths go to the
// Time Machine site's /history, which both the combined and the Time Machine site understand (PR-06).
// @routes:leagues begin
function LeaguesTree() {
  // The lock (leaguesLock.ts): until a league session is held, LeaguesLock takes the
  // place of everything inside the gate -- landing page, sign-in, shell, every league route -- at whatever
  // URL was asked for, and the URL is left alone so that page renders after unlocking. The two redirect
  // routes stay outside it: they show nothing, and an old Time Machine link still has to land.
  const unlocked = useUnlocked()
  // While unlocked: renew the session as long as the user is active, lock after the idle window.
  useIdleLock(unlocked)
  return (
    <Routes>
      <Route path="/history" element={<CrossSiteRedirect target="tm" />} />
      <Route path="/history/:gameId" element={<CrossSiteRedirect target="tm" />} />
      <Route
        path="*"
        element={
          unlocked ? (
            <LeagueGate>
              <Routes>
                {leaguePages}
                <Route path="*" element={<NotFound what="page" />} />
              </Routes>
            </LeagueGate>
          ) : (
            <LeaguesLock />
          )
        }
      />
    </Routes>
  )
}
// @routes:leagues end

// The Time Machine site: its own shell and front door. Old Time Machine paths redirect in-app, old league
// paths are forwarded to the league site with path, query and hash.
// @routes:timemachine begin
function TimeMachineTree() {
  return (
    <Routes>
      <Route path="/history" element={<InAppRedirect to={() => tmHome(currentSite())} />} />
      <Route path="/history/:gameId" element={<InAppRedirect to={(p) => tmGame(currentSite(), p.gameId ?? '')} />} />
      <Route path="/leagues/*" element={<CrossSiteRedirect target="leagues" />} />
      <Route path="/drafts/*" element={<CrossSiteRedirect target="leagues" />} />
      <Route path="/mock/*" element={<CrossSiteRedirect target="leagues" />} />
      <Route path="/managers/*" element={<CrossSiteRedirect target="leagues" />} />
      <Route path="/admin/*" element={<CrossSiteRedirect target="leagues" />} />
      <Route
        path="*"
        element={
          <div className="app">
            <TimeMachineShell>
              {/* Spec 074: every page renders for a guest and a signed-in visitor alike (the front door at every URL is gone).
                  Each indexable page sets its own document.title from redraftCopy, the words its static file's <title> uses. */}
              <Routes>
                <Route path="/" element={<HistoricalDraft />} />
                <Route path="/daily" element={<DailyPage />} />
                <Route path="/nba" element={<SportIndex sport="NBA" />} />
                <Route path="/nfl" element={<SportIndex sport="NFL" />} />
                <Route path="/nba/:season" element={<KeyedSeasonPage sport="NBA" />} />
                <Route path="/nfl/:season" element={<KeyedSeasonPage sport="NFL" />} />
                <Route path="/game/:gameId" element={<KeyedHistoricalRoom />} />
                <Route path="*" element={<RedraftNotFound />} />
              </Routes>
            </TimeMachineShell>
          </div>
        }
      />
    </Routes>
  )
}
// @routes:timemachine end

export default function App() {
  const user = useUser()
  const unlocked = useUnlocked()
  const { site } = currentSite()

  // Spec 050: index.html carries the long SEO title for crawlers and the signed-out landing
  // page. Signed-in tabs, bookmarks and history entries keep the short name they always had.
  // Spec 074: the Redraft site has no title here: each of its pages sets its own (pageTitle.ts), so this effect, which
  // runs after the children's, cannot overwrite them.
  useEffect(() => {
    if (site === 'timemachine') return
    // The locked league site carries no marketing title (its delivered HTML says just "Ball Knowers").
    if (site === 'leagues' && !unlocked) document.title = 'Ball Knowers'
    else document.title = user ? 'Ball Knowers' : landingTitle
  }, [user, site, unlocked])

  if (site === 'timemachine') return <TimeMachineTree />
  if (site === 'leagues') return <LeaguesTree />
  return <CombinedTree />
}

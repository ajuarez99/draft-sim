import { useCallback, useEffect, useId, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import HowThisWorks from '../components/HowThisWorks'
import AwardWinners from '../components/AwardWinners'
import SuperlativeStandings from '../components/SuperlativeStandings'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import {
  fetchSuperlatives,
  type SuperlativesResponse,
  type Superlative,
  type Sport,
} from '../api'
import { useFailure } from '../useFailure'
import NotFound from '../components/NotFound'
import CommissionerListPanel, { CommissionerListButton } from '../components/CommissionerListPanel'
import { hueForIndex } from '../hue'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { TITLES } from '../awardTitles'

/**
 * specs/008-season-superlatives US1: the season's extremes and close games.
 *
 * Every kind from the payload gets a card, in payload order (contract order),
 * whether or not it has been built yet -- an absent card would be
 * indistinguishable from a broken fetch, and a card with nothing to say still
 * says why (FR-002/FR-008).
 */

export default function Superlatives() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<SuperlativesResponse | null>(null)
  const { error, notFound, setError, fail } = useFailure()
  const [loading, setLoading] = useState(false)
  // Bumped by the rail when this league's background refresh finishes (specs/009-auto-data-refresh).
  const dataVersion = useLeagueDataVersion(sleeperLeagueId)

  // Shared by the mount effect below AND by CommissionerListPanel after a
  // successful save/remove (live-check finding, 2026-09-23): a commissioner
  // edit changes the UNETHICAL card's holders/detail too, and refetching
  // only the conduct list left that card showing "the list is empty" until
  // a full page reload.
  const refetchSuperlatives = useCallback(async () => {
    if (!sleeperLeagueId) return
    try {
      const d = await fetchSuperlatives(sleeperLeagueId)
      setData(d)
      setError(null)
    } catch (e) {
      fail(e)
    }
  }, [sleeperLeagueId])

  useEffect(() => {
    if (!sleeperLeagueId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchSuperlatives(sleeperLeagueId)
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch((e) => {
        if (!cancelled) fail(e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId, dataVersion])

  if (notFound) return <NotFound what="league" />

  return (
    <div className="content">
      <PageHeader
        eyebrow={data ? `League · ${data.season}` : 'League'}
        title="Awards"
        sub="Season-long awards for the highs and lows, the closest games, luck, the bench and the waiver wire."
      />

      {error && (
        <div className="error">
          <span>{error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="section">
          <h3 className="section-title">No scored weeks yet</h3>
          <p className="muted small">{data.reason ?? 'No week of this season has been scored yet.'}</p>
          {/* Spec 040 (plan-review F4a): before the first scored week there are no cards, but the
              commissioner still needs the list -- a suspension is announced before week 1. */}
          {sleeperLeagueId && (
            <ConductListSlot
              urlLeagueId={sleeperLeagueId}
              cardsSeason={data.season}
              sport={data.sport}
              onChanged={refetchSuperlatives}
            />
          )}
        </section>
      )}

      {data && data.available && (
        <>
          <section className="section">
            <h3 className="section-title">Season so far · through week {data.throughWeek}</h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
            <p className="muted small">
              {data.regularSeasonEnd == null
                ? 'This league has no playoff start set, so every scored week counts.'
                : `Regular season — weeks 1 through ${data.regularSeasonEnd}.`}
            </p>
          </section>

          <div className="sl-list">
            {data.superlatives.map((s, i) => (
              <SuperlativeCard
                key={s.kind}
                s={s}
                hue={hueForIndex(i, data.superlatives.length)}
                sport={data.sport}
                leagueId={data.leagueSleeperId ?? sleeperLeagueId ?? ''}
                conductLeagueId={sleeperLeagueId ?? ''}
                cardsSeason={data.season}
                onConductChanged={refetchSuperlatives}
                closeGameMargin={data.closeGameMargin}
                throughWeek={data.throughWeek}
                suspensionWeeksObserved={data.suspensionWeeksObserved}
              />
            ))}
          </div>

          <HowThisWorks>
            <p>One row each. If an award can&apos;t be worked out yet, its row says why.</p>
            {data.superlatives.some((s) => s.kind === 'TRENDING_TONY') && (
              <p>
                The Trending Tony counts pickups that were on Sleeper&apos;s trending list when they were made. That list
                is platform-wide (the players added most across all Sleeper leagues in the last 24 hours), not this
                league&apos;s, and a pickup counts when the player was in its top 15. Each pickup is judged by the
                first list recorded within a day after it (the one whose 24-hour window contains the pickup), or failing that
                the last list recorded within a day before it. &quot;Not judged&quot;
                means no usable list was recorded, which is not the same as &quot;not trending&quot;. Lists have only been
                recorded since the day recording began, so earlier pickups can never be judged. Waiver claims are timed
                when they process, not when they were submitted. Trending data from Sleeper.
              </p>
            )}
          </HowThisWorks>
        </>
      )}
    </div>
  )
}

/** Kinds whose detail names one event per holder -- the week (and, for a game, the opponent) belongs on the card itself, not only behind "Games" (FR-002). */
const SINGLE_EVENT_KINDS = new Set(['HIGHEST_WEEK', 'LOWEST_WEEK', 'BIGGEST_BLOWOUT', 'CLOSEST_GAME'])
const CLOSE_GAME_KINDS = new Set(['CLOSE_WINS', 'CLOSE_LOSSES'])
/** US2 (T035) + US3 (T040) + US4 (T049) + US6 (T060) + US7 (T079): the reading (luck), the bench span (bench), the pickup total (waiver), the cost (Embiid), the conduct row (Unethical) or each player's own "N adds by M teams" (Jabari Smith Jr.) is itself the per-holder figure, so the generic value line beneath the holder list would only repeat it. */
const READING_KINDS = new Set([
  'LUCKIEST', 'UNLUCKIEST', 'MOST_BENCH_POINTS', 'WAIVER_WIRE_WARRIOR', 'JABARI_SMITH_JR', 'JOEL_EMBIID', 'UNETHICAL',
])

function SuperlativeCard({
  s,
  hue,
  sport,
  leagueId,
  conductLeagueId,
  cardsSeason,
  onConductChanged,
  closeGameMargin,
  throughWeek,
  suspensionWeeksObserved,
}: {
  s: Superlative
  hue: number
  sport: Sport
  /** The league season the cards resolved to; where a player's name links. */
  leagueId: string
  /** The URL's own league id: what the commissioner's list edits (spec 040, see CommissionerListPanel). */
  conductLeagueId: string
  cardsSeason: number
  onConductChanged: () => void | Promise<void>
  closeGameMargin: number
  throughWeek: number | null
  suspensionWeeksObserved: number[]
}) {
  const meta = TITLES[s.kind] ?? { title: s.kind }
  const isCloseGameKind = CLOSE_GAME_KINDS.has(s.kind)
  const isSingleEventKind = SINGLE_EVENT_KINDS.has(s.kind)
  const isReadingKind = READING_KINDS.has(s.kind)
  // US7 (T079): JABARI_SMITH_JR is headed by players, not teams -- `holders`
  // is always `[]` for it, and `playerHolders` carries the winners instead
  // (contracts/superlatives-api.md's restated invariant). Both empty is what
  // actually means "nothing to show" now, not `holders` alone -- without this
  // fix the award rendered its empty state while it had a real winner.
  const isPlayerHeadedKind = s.kind === 'JABARI_SMITH_JR'
  const isEmpty = s.holders.length === 0 && s.playerHolders.length === 0
  // Spec 010 (amendments 10, 12): the full standings open from a real "See all"
  // button (the keyboard/screen-reader path); the card body also opens it, but
  // for the mouse only -- no role or tabIndex on the <article>. State lives here
  // so `standingFigure` closes over this card's own kind and unit.
  const openable = s.available && !isEmpty && (s.standings.length > 0 || s.playerStandings.length > 0)
  const [open, setOpen] = useState(false)
  // Spec 040: the commissioner's list opens in place from the UNETHICAL card, whatever the award's own
  // state -- available, empty or not openable (plan-review F3).
  const hasConductList = s.kind === 'UNETHICAL'
  const [conductOpen, setConductOpen] = useState(false)
  const [conductCount, setConductCount] = useState<number | null>(null)
  const conductPanelId = useId()

  // The headline figure, in big tabular numbers. Player-headed awards lead with
  // the winner's add count; every other kind reuses `standingFigure`, the same
  // formatter the full standings use, so the row and its standings can't
  // disagree on a number (amendment 10). Absent when the payload has no value.
  const showSharedValue = !isSingleEventKind && !isReadingKind
  let stat: string | null = null
  if (s.available && !isEmpty) {
    if (isPlayerHeadedKind) {
      const ph = s.playerHolders[0]
      stat = ph ? `${ph.adds} ${ph.adds === 1 ? 'add' : 'adds'}` : null
    } else if (!showSharedValue && s.value != null) {
      stat = standingFigure(s.kind, s.unit, s.value)
    }
  }

  return (
    <article
      className={`sl-card sl-row${openable ? ' sl-card-openable' : ''}`}
      style={{ ['--sl-hue' as string]: hue }}
      onClick={
        openable
          ? () => {
              // B3: a drag-select across row text ends in a click on the article; don't open over it.
              if (window.getSelection()?.toString()) return
              setOpen(true)
            }
          : undefined
      }
    >
      <header className="sl-card-head">
        <TrophyIcon />
        <h4>{meta.title}</h4>
        {meta.subtitle && <span className="sl-subtitle muted small">{meta.subtitle}</span>}
        {s.early && <span className="sl-early small">early — this is mostly noise</span>}
      </header>

      <div className="sl-row-body">
      {/* Unconditional lines, independent of available/holders state -- EXCEPT
          the empty-tracking fallback below, which is deliberately suppressed
          in one case (see its own comment). */}
      {meta.note && <p className="muted small sl-note">{meta.note}</p>}
      {s.kind === 'UNETHICAL' &&
        (suspensionWeeksObserved.length > 0 ? (
          // Adds information (the actual first tracked week) beyond whatever
          // the empty/holder state below says, so this always renders.
          <p className="muted small sl-note">{`tracking began week ${suspensionWeeksObserved[0]}`}</p>
        ) : (
          // The backend now lists only SCORED weeks in suspensionWeeksObserved,
          // so an empty list is the ordinary early-season case, not an error --
          // "hasn't covered a scored week yet" says that rather than implying
          // tracking itself is broken (2026-09-23 fix). But when nobody
          // qualifies, the backend's own emptyReason already says this same
          // thing ("...and the commissioner's list is empty") -- printing the
          // fallback here too repeated the sentence verbatim on the card
          // (live-check finding, 2026-09-23). Suppressed in exactly that one
          // case; every other state (unavailable, or holders present with
          // nothing observed) still gets it.
          !(s.available && s.holders.length === 0 && s.emptyReason) && (
            <p className="muted small sl-note">
              suspension tracking hasn&apos;t covered a scored week yet
            </p>
          )
        ))}

      <AwardWinners s={s} hue={hue} sport={sport} leagueId={leagueId} throughWeek={throughWeek} />
      </div>

      {/* The headline figure sits in its own column. Single-event and reading
          kinds put their full figure on each holder's own line as well (ties
          can point at different weeks/opponents), so this is the headline,
          not a replacement for those lines. */}
      <div className="sl-row-stat">
        {s.available && !isEmpty && !isPlayerHeadedKind && showSharedValue && s.value != null && (
          <p className="sl-value">
            {isCloseGameKind ? formatCloseGameCount(s.kind, s.value) : formatValue(s.value, s.unit)}
            {isCloseGameKind && (
              <span className="muted small"> by under {closeGameMargin} points</span>
            )}
          </p>
        )}
        {stat && <p className="sl-stat">{stat}</p>}
      </div>

      <div className="sl-row-action">
        {openable && (
          <button
            type="button"
            className="sl-see-all"
            aria-expanded={open}
            onClick={(e) => {
              e.stopPropagation()
              setOpen((o) => !o)
            }}
          >
            {open ? 'Hide' : 'See all'}
          </button>
        )}
        {hasConductList && (
          <CommissionerListButton
            count={conductCount}
            open={conductOpen}
            controlsId={conductPanelId}
            onToggle={() => setConductOpen((o) => !o)}
          />
        )}
      </div>

      {open && (
        <SuperlativeStandings
          s={s}
          title={meta.title}
          hue={hue}
          leagueId={leagueId}
          sport={sport}
          figure={(v) => standingFigure(s.kind, s.unit, v)}
          // Spec 051 F6: a player row's figure is the pickup's trending RANK, not a count, so it can't share `figure`.
          contributionFigure={s.kind === 'TRENDING_TONY' ? (v) => `#${v} trending` : undefined}
        />
      )}

      {hasConductList && (
        <CommissionerListPanel
          leagueSleeperId={conductLeagueId}
          cardsSeason={cardsSeason}
          sport={sport}
          open={conductOpen}
          panelId={conductPanelId}
          onCount={setConductCount}
          onChanged={onConductChanged}
        />
      )}
    </article>
  )
}

/** The trophy beside each award's name; --fitted ("earned"), a leaf mark with no text of its own. */
function TrophyIcon() {
  return (
    <svg className="sl-trophy" viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M7 3h10v2h3a1 1 0 0 1 1 1v1.5A4.5 4.5 0 0 1 16.6 12 5 5 0 0 1 13 14.9V17h3a1 1 0 0 1 1 1v3H7v-3a1 1 0 0 1 1-1h3v-2.1A5 5 0 0 1 7.4 12 4.5 4.5 0 0 1 3 7.5V6a1 1 0 0 1 1-1h3V3Zm0 4H5v.5A2.5 2.5 0 0 0 7.3 10 5 5 0 0 1 7 8.2V7Zm10 0v1.2a5 5 0 0 1-.3 1.8A2.5 2.5 0 0 0 19 7.5V7h-2Z"
      />
    </svg>
  )
}

/**
 * One figure per standings row (amendment 10): the card has no single value
 * formatter -- some kinds print a shared value line, others fold the figure into
 * each holder line -- so the modal gets its own, defined per kind. Luck is signed
 * because the sign is the whole point of UNLUCKIEST.
 */
export function standingFigure(kind: Superlative['kind'], unit: Superlative['unit'], value: number): string {
  if (CLOSE_GAME_KINDS.has(kind)) return formatCloseGameCount(kind, value)
  if (kind === 'LUCKIEST' || kind === 'UNLUCKIEST') {
    return `${value >= 0 ? '+' : '−'}${Math.abs(value).toFixed(2)} wins vs expected`
  }
  // B1: UNETHICAL's total counts player-weeks (a player flagged for a week), not games.
  if (kind === 'UNETHICAL') return `${value} ${value === 1 ? 'player-week' : 'player-weeks'}`
  // B4: every Embiid figure is an estimate, same wording as the card.
  if (kind === 'JOEL_EMBIID') return `${value.toFixed(2)} estimated points lost`
  return formatValue(value, unit)
}

function formatValue(value: number, unit: Superlative['unit']): string {
  if (unit === 'POINTS') return `${value.toFixed(2)} points`
  if (unit === 'WINS') return `${value} ${value === 1 ? 'win' : 'wins'}`
  if (unit === 'GAMES') return `${value} ${value === 1 ? 'game' : 'games'}`
  if (unit === 'ADDS') return `${value} ${value === 1 ? 'add' : 'adds'}`
  // Spec 051: TRENDING_TONY's count of pickups that were on the trending list.
  if (unit === 'PICKUPS') return `${value} trending ${value === 1 ? 'pickup' : 'pickups'}`
  return String(value)
}

/** CLOSE_WINS/CLOSE_LOSSES read "win(s)"/"loss(es)" on the card -- the payload unit stays GAMES for CLOSE_LOSSES; this is display text only. */
function formatCloseGameCount(kind: Superlative['kind'], value: number): string {
  if (kind === 'CLOSE_WINS') return `${value} ${value === 1 ? 'win' : 'wins'}`
  if (kind === 'CLOSE_LOSSES') return `${value} ${value === 1 ? 'loss' : 'losses'}`
  return String(value)
}

/**
 * The commissioner's list on a page with no award cards (nothing scored yet), addressed by the URL's id like the
 * panel on the UNETHICAL card. Same button, same panel.
 */
function ConductListSlot({
  urlLeagueId,
  cardsSeason,
  sport,
  onChanged,
}: {
  urlLeagueId: string
  cardsSeason: number
  sport: Sport
  onChanged: () => void | Promise<void>
}) {
  const [open, setOpen] = useState(false)
  const [count, setCount] = useState<number | null>(null)
  const panelId = useId()
  return (
    <div className="sl-cl-slot">
      <CommissionerListButton count={count} open={open} controlsId={panelId} onToggle={() => setOpen((o) => !o)} />
      <CommissionerListPanel
        leagueSleeperId={urlLeagueId}
        cardsSeason={cardsSeason}
        sport={sport}
        open={open}
        panelId={panelId}
        onCount={setCount}
        onChanged={onChanged}
      />
    </div>
  )
}

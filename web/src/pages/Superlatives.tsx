import { useCallback, useEffect, useId, useState } from 'react'
import { useParams } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import Avatar from '../components/Avatar'
import SeasonFallbackNote from '../components/SeasonFallbackNote'
import {
  fetchSuperlatives,
  fetchConductList,
  saveConductEntry,
  deleteConductEntry,
  type SuperlativesResponse,
  type Superlative,
  type SuperlativeDetail,
  type ConductList,
} from '../api'
import { hueForIndex } from '../hue'

/**
 * specs/008-season-superlatives US1: the season's extremes and close games.
 *
 * Every kind from the payload gets a card, in payload order (contract order),
 * whether or not it has been built yet -- an absent card would be
 * indistinguishable from a broken fetch, and a card with nothing to say still
 * says why (FR-002/FR-008).
 */
const TITLES: Record<string, { title: string; subtitle?: string; note?: string }> = {
  HIGHEST_WEEK: { title: 'Highest week' },
  LOWEST_WEEK: { title: 'Lowest week' },
  BIGGEST_BLOWOUT: { title: 'Biggest blowout' },
  CLOSEST_GAME: { title: 'Closest game' },
  CLOSE_WINS: { title: 'Closest wins', subtitle: 'Escape artist' },
  CLOSE_LOSSES: { title: 'Closest losses', subtitle: 'Heartbreak kid' },
  LUCKIEST: { title: 'Luckiest' },
  UNLUCKIEST: { title: 'Unluckiest' },
  MOST_BENCH_POINTS: { title: 'Most bench points' },
  WAIVER_WIRE_WARRIOR: { title: 'Waiver Wire Warrior' },
  // FR-013: this line is not a caveat that shows up only when relevant -- the
  // award's whole premise is that the source is never the injury tag, so it
  // is printed unconditionally under the title (T049), not folded into a
  // holder line the way other kinds' figures are.
  JOEL_EMBIID: { title: 'The Joel Embiid Award', note: 'Games missed, cause unknown' },
  UNETHICAL: { title: 'The Unethical Award' },
}

export default function Superlatives() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<SuperlativesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // Shared by the mount effect below AND by ConductListSection after a
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
      setError(e instanceof Error ? e.message : String(e))
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
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sleeperLeagueId])

  return (
    <div className="content">
      <PageHeader
        eyebrow="League"
        title="Superlatives"
        sub="Season-long facts, one card per award. A card that isn't built yet says so, rather than going missing."
      />

      {error && (
        <div className="error">
          <span>{error.includes('404') ? "This league hasn't been loaded yet." : error}</span>
        </div>
      )}

      {loading && !data && <p className="muted small">Loading…</p>}

      {data && !data.available && (
        <section className="panel">
          <h3 className="cond">No scored weeks yet</h3>
          <p className="muted small">{data.reason ?? 'No week of this season has been scored yet.'}</p>
        </section>
      )}

      {data && data.available && (
        <>
          <section className="panel">
            <h3 className="cond">Season so far · through week {data.throughWeek}</h3>
            <SeasonFallbackNote season={data.season} requestedSeason={data.requestedSeason} />
            <p className="muted small">
              {data.regularSeasonEnd == null
                ? 'This league has no playoff start set, so every scored week counts.'
                : `Regular season — weeks 1 through ${data.regularSeasonEnd}.`}
            </p>
          </section>

          <div className="sl-cards">
            {data.superlatives.map((s, i) => (
              <SuperlativeCard
                key={s.kind}
                s={s}
                hue={hueForIndex(i, data.superlatives.length)}
                closeGameMargin={data.closeGameMargin}
                throughWeek={data.throughWeek}
                suspensionWeeksObserved={data.suspensionWeeksObserved}
              />
            ))}
          </div>

          {data.leagueSleeperId && (
            <ConductListSection
              leagueSleeperId={data.leagueSleeperId}
              commissionerListAvailable={data.commissionerListAvailable}
              season={data.season}
              requestedSeason={data.requestedSeason ?? null}
              onChanged={refetchSuperlatives}
            />
          )}
        </>
      )}
    </div>
  )
}

/** Kinds whose detail names one event per holder -- the week (and, for a game, the opponent) belongs on the card itself, not only behind "Games" (FR-002). */
const SINGLE_EVENT_KINDS = new Set(['HIGHEST_WEEK', 'LOWEST_WEEK', 'BIGGEST_BLOWOUT', 'CLOSEST_GAME'])
const CLOSE_GAME_KINDS = new Set(['CLOSE_WINS', 'CLOSE_LOSSES'])
/** US2 (T035) + US3 (T040) + US4 (T049) + US6 (T060): the reading (luck), the bench span (bench), the pickup total (waiver), the cost (Embiid) or the conduct row (Unethical) is itself the per-holder figure, so the generic value line beneath the holder list would only repeat it. */
const READING_KINDS = new Set([
  'LUCKIEST', 'UNLUCKIEST', 'MOST_BENCH_POINTS', 'WAIVER_WIRE_WARRIOR', 'JOEL_EMBIID', 'UNETHICAL',
])

function SuperlativeCard({
  s,
  hue,
  closeGameMargin,
  throughWeek,
  suspensionWeeksObserved,
}: {
  s: Superlative
  hue: number
  closeGameMargin: number
  throughWeek: number | null
  suspensionWeeksObserved: number[]
}) {
  const meta = TITLES[s.kind] ?? { title: s.kind }
  const isCloseGameKind = CLOSE_GAME_KINDS.has(s.kind)
  const isSingleEventKind = SINGLE_EVENT_KINDS.has(s.kind)
  const isReadingKind = READING_KINDS.has(s.kind)

  return (
    <article className="sl-card" style={{ ['--sl-hue' as string]: hue }}>
      <header className="sl-card-head">
        <h4>{meta.title}</h4>
        {meta.subtitle && <span className="sl-subtitle muted small">{meta.subtitle}</span>}
        {s.early && <span className="sl-early small">early — this is mostly noise</span>}
      </header>

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

      {!s.available ? (
        <p className="muted small">{s.reason}</p>
      ) : s.holders.length === 0 ? (
        <p className="muted small">{s.emptyReason}</p>
      ) : (
        <>
          <div className="sl-holders">
            {s.holders.map((h) => {
              const holderLines = holderDetailLines(s, h.rosterId, throughWeek)
              return (
                <div className="sl-holder" key={h.rosterId}>
                  <span className="sl-holder-top">
                    <Avatar
                      avatarId={h.avatarId}
                      seed={String(h.managerId ?? h.rosterId)}
                      label={h.teamName}
                      hue={hue}
                      className="sl-avatar"
                    />
                    <span className="sl-holder-name">{h.teamName}</span>
                  </span>
                  {/* Each mapped to its own line, not joined -- WAIVER_WIRE_WARRIOR
                      returns a total line plus up to three pickup lines (T040);
                      every other kind still returns exactly one, so this renders
                      identically for them. */}
                  {holderLines.map((line, li) => (
                    <span className="sl-holder-detail muted small" key={li}>
                      {line}
                    </span>
                  ))}
                </div>
              )
            })}
          </div>

          {/* Single-event and reading kinds put their figure on each holder's
              own line above (ties can point at different weeks/opponents, or
              already carry the full reading), so the shared line here would
              just repeat the first holder's figure. */}
          {!isSingleEventKind && !isReadingKind && (
            <p className="sl-value">
              {isCloseGameKind
                ? s.value != null && formatCloseGameCount(s.kind, s.value)
                : s.value != null && formatValue(s.value, s.unit)}
              {isCloseGameKind && (
                <span className="muted small"> by under {closeGameMargin} points</span>
              )}
            </p>
          )}

          {s.coverage && (
            <p className="muted small sl-coverage">
              {s.coverage.weeksCovered} of {s.coverage.weeksCovered + s.coverage.weeksExcluded} weeks
              {s.coverage.reasons.length > 0 ? ` — ${s.coverage.reasons.join('; ')}` : ''}
            </p>
          )}

          {expandableRows(s).length > 0 && <DetailList s={s} />}
        </>
      )}
    </article>
  )
}

/**
 * The figure this holder's own row(s) contributed -- the week, and for a
 * game the opponent, printed inline so the reader never has to expand
 * "Games" just to see FR-002's week (live-check finding, 2026-09-23).
 * Ties (e.g. three CLOSEST_GAME holders) each get their own line rather than
 * one shared figure, since a tie can span different weeks and opponents.
 */
function holderDetailLines(s: Superlative, rosterId: number, throughWeek: number | null): string[] {
  const rows = s.detail.filter((d): d is Extract<SuperlativeDetail, { rosterId: number }> =>
    'rosterId' in d && d.rosterId === rosterId,
  )
  if (s.kind === 'HIGHEST_WEEK' || s.kind === 'LOWEST_WEEK') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'WEEK_SCORE' }> => d.type === 'WEEK_SCORE')
      .map((d) => `${d.points.toFixed(2)} points · week ${d.week}`)
  }
  if (s.kind === 'BIGGEST_BLOWOUT' || s.kind === 'CLOSEST_GAME') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'GAME' }> => d.type === 'GAME')
      .map((d) => `${d.margin.toFixed(2)} points over ${d.opponentTeamName} · week ${d.week}`)
  }
  if (s.kind === 'CLOSE_WINS' || s.kind === 'CLOSE_LOSSES') {
    const weeks = rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'GAME' }> => d.type === 'GAME')
      .map((d) => d.week)
    return weeks.length > 0 ? [`weeks ${weeks.join(', ')}`] : []
  }
  // US2 (T035): the reading itself IS the figure -- "2.40 more wins than
  // their scores earned" -- so it belongs inline, with the span it covers.
  if (s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'LUCK' }> => d.type === 'LUCK')
      .map((d) => `${d.reading} · weeks ${d.fromWeek}–${d.throughWeek}`)
  }
  if (s.kind === 'MOST_BENCH_POINTS') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'BENCH_TOTAL' }> => d.type === 'BENCH_TOTAL')
      .map((d) => {
        const worst = d.biggestWeek
          ? ` · worst: week ${d.biggestWeek.week} (${d.biggestWeek.pointsLeft.toFixed(2)})`
          : ''
        return `${d.pointsLeft.toFixed(2)} points left on the bench · weeks ${d.fromWeek}–${d.throughWeek}${worst}`
      })
  }
  // US3 (T040): a total line, then up to three pickup lines (the backend
  // already limits detail to top 3 per holder) -- not folded into one line,
  // since a reader scanning pickups wants each player on its own row.
  if (s.kind === 'WAIVER_WIRE_WARRIOR') {
    const pickups = rows.filter(
      (d): d is Extract<SuperlativeDetail, { type: 'PICKUP' }> => d.type === 'PICKUP',
    )
    if (pickups.length === 0) return []
    const total: string[] =
      s.value != null
        ? [`${s.value.toFixed(2)} points from waiver pickups · weeks 1–${throughWeek ?? '?'}`]
        : []
    const pickupLines = pickups.map((d) => {
      const source = d.addType === 'WAIVER' ? 'waivers' : 'free agency'
      const weeksWord = d.startedWeeks.length === 1 ? 'week' : 'weeks'
      return (
        `${d.playerName} (${d.position}) — ${d.points.toFixed(2)} pts, added week ${d.addedWeek} ` +
        `off ${source}, started ${weeksWord} ${d.startedWeeks.join(', ')}`
      )
    })
    return [...total, ...pickupLines]
  }
  // US4 (T049): the total, then each top absence -- "estimated" said on both
  // the total and every player line (FR-015), never left to be assumed once.
  if (s.kind === 'JOEL_EMBIID') {
    const absences = rows.filter(
      (d): d is Extract<SuperlativeDetail, { type: 'ABSENCE' }> => d.type === 'ABSENCE',
    )
    if (absences.length === 0) return []
    const total: string[] =
      s.value != null ? [`${s.value.toFixed(2)} estimated points lost`] : []
    const absenceLines = absences.map(
      (d) =>
        `${d.playerName} (${d.position}) — ${d.gamesMissed} games missed (${d.weeksAffected} weeks), ` +
        `~${d.pointsPerGame.toFixed(2)} per game (estimated)`,
    )
    return [...total, ...absenceLines]
  }
  // US6 (T060): one line per player named -- a team can be on the list for
  // more than one player, each with its own source and reason. Plain text
  // only, never dangerouslySetInnerHTML (FR-016's reason is unmoderated
  // commissioner-entered text). The weeks it counted for THIS team (FR-002)
  // print on both sources, not just SUSPENDED -- a commissioner entry is
  // just as much a "which weeks" fact (live-check finding, 2026-09-23).
  if (s.kind === 'UNETHICAL') {
    return rows
      .filter((d): d is Extract<SuperlativeDetail, { type: 'CONDUCT' }> => d.type === 'CONDUCT')
      .map((d) => {
        const weeksPart = d.weeks.length > 0 ? ` · ${weeksLabel(d.weeks)}` : ''
        const label =
          d.source === 'SUSPENDED'
            ? `Suspended${weeksPart}`
            : `Commissioner's call: ${d.reason ?? ''}${weeksPart}`
        return `${d.playerName} — ${label}`
      })
  }
  return []
}

/**
 * "week 2" for one week, "weeks 3–5" for a consecutive run, "weeks 3, 7, 9"
 * for scattered ones, and "weeks 3–5, 9" when both shapes appear -- a raw
 * comma-joined list of every captured week reads as noise once a suspension
 * spans more than two or three weeks.
 */
function weeksLabel(weeks: number[]): string {
  const sorted = [...weeks].sort((a, b) => a - b)
  const ranges: string[] = []
  let start = sorted[0]
  let prev = sorted[0]
  for (let i = 1; i <= sorted.length; i++) {
    const cur = sorted[i]
    if (cur === prev + 1) {
      prev = cur
      continue
    }
    ranges.push(start === prev ? `${start}` : `${start}–${prev}`)
    start = cur
    prev = cur
  }
  return `${sorted.length === 1 ? 'week' : 'weeks'} ${ranges.join(', ')}`
}

function formatValue(value: number, unit: Superlative['unit']): string {
  if (unit === 'POINTS') return `${value.toFixed(2)} points`
  if (unit === 'WINS') return `${value} ${value === 1 ? 'win' : 'wins'}`
  if (unit === 'GAMES') return `${value} ${value === 1 ? 'game' : 'games'}`
  return String(value)
}

/** CLOSE_WINS/CLOSE_LOSSES read "win(s)"/"loss(es)" on the card -- the payload unit stays GAMES for CLOSE_LOSSES; this is display text only. */
function formatCloseGameCount(kind: Superlative['kind'], value: number): string {
  if (kind === 'CLOSE_WINS') return `${value} ${value === 1 ? 'win' : 'wins'}`
  if (kind === 'CLOSE_LOSSES') return `${value} ${value === 1 ? 'loss' : 'losses'}`
  return String(value)
}

function DetailList({ s }: { s: Superlative }) {
  const rows = expandableRows(s)
  const summary = s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST' ? 'Swing weeks' : 'Games'
  return (
    <details className="sl-detail">
      <summary className="muted small">{summary}</summary>
      <ul className="sl-detail-list">
        {rows.map((r, i) => (
          <li key={i}>{r}</li>
        ))}
      </ul>
    </details>
  )
}

/**
 * The expandable list's rows. Single-event and reading kinds already print
 * their week/reading on the holder line itself (holderDetailLines above), so
 * this only ever adds something NEW: WEEK_SCORE/GAME rows for the kinds that
 * don't yet fold their figure into the holder line, and swing weeks for luck
 * (which nest inside the LUCK row rather than being their own detail rows).
 * MOST_BENCH_POINTS has nothing beyond its inline summary, so it contributes
 * no rows -- the card never opens an empty "Games" list (never blank).
 */
function expandableRows(s: Superlative): string[] {
  if (s.kind === 'LUCKIEST' || s.kind === 'UNLUCKIEST') {
    return s.detail
      .filter((d): d is Extract<SuperlativeDetail, { type: 'LUCK' }> => d.type === 'LUCK')
      .flatMap((d) =>
        d.swingWeeks.map(
          (w) =>
            `Week ${w.week}: ${w.result === 'WON' ? 'won' : 'lost'} with ${w.points.toFixed(2)} ` +
            `(${ordinal(w.weeklyRank)} that week) vs ${w.opponent}`,
        ),
      )
  }
  if (s.kind === 'MOST_BENCH_POINTS' || s.kind === 'WAIVER_WIRE_WARRIOR'
      || s.kind === 'JOEL_EMBIID' || s.kind === 'UNETHICAL') {
    // Already fully represented on the holder line(s) above -- nothing new
    // to say behind an expandable panel, so the card never opens an empty one.
    return []
  }
  return s.detail.map(detailLine).filter((line) => line.length > 0)
}

function detailLine(d: SuperlativeDetail): string {
  switch (d.type) {
    case 'WEEK_SCORE':
      return `Week ${d.week}: ${d.points.toFixed(2)}`
    case 'GAME':
      return `Week ${d.week} vs ${d.opponentTeamName}: ${d.points.toFixed(2)}–${d.opponentPoints.toFixed(2)} (margin ${d.margin.toFixed(2)})`
    default:
      return ''
  }
}

/** "1st"/"2nd"/"3rd"/"4th…", matching ExpectedWins.tsx's own helper. */
function ordinal(n: number): string {
  const s = ['th', 'st', 'nd', 'rd']
  const v = n % 100
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0])
}

/**
 * The commissioner's conduct list (T060), a separate season-scoped list
 * below the award cards -- readable by every member (FR-017), editable only
 * by the commissioner (`canEdit`, the same `LeagueMembership.canCommission`
 * gate the write endpoints enforce with).
 *
 * <p><b>Addressed by `leagueSleeperId`, never the URL's own id.</b> The cards
 * above resolve through `LeagueSeasonResolver`, which can walk back to an
 * older PLAYED season; the conduct list is addressed by exact league row
 * (contracts/superlatives-api.md), so before a new season's first scored
 * week the URL's id and the season actually shown disagree -- saving
 * against the URL id silently edited a season nothing on screen was
 * displaying (code-review fix, 2026-09-23). `data.leagueSleeperId` is
 * always the season the cards resolved to, so that is what this fetches,
 * saves and deletes against.
 *
 * <p>Player picker: no existing lightweight player-search/typeahead
 * component was found in `web/src/components` -- `PlayerPicker.tsx` is a
 * full draft-pick overlay wired to a draft's availability rows and team
 * needs, not a standalone player lookup, and pulling it in here would need
 * a second data source this page has no other reason to fetch. Falls back
 * to a plain Sleeper player-id input per the task's own escape hatch; the
 * player's name comes back from the server on save/refresh rather than
 * being resolved client-side.
 */
function ConductListSection({
  leagueSleeperId,
  commissionerListAvailable,
  season,
  requestedSeason,
  onChanged,
}: {
  leagueSleeperId: string
  commissionerListAvailable: boolean
  season: number
  /** Non-null when LeagueSeasonResolver walked back from the URL's own
   *  season to an older played one -- the same signal SeasonFallbackNote
   *  reads for the cards above. */
  requestedSeason: number | null
  /** Called after a successful save or remove, so the UNETHICAL card above
   *  (a different fetch, from the parent) doesn't keep showing stale
   *  holders/detail until a full reload (live-check finding, 2026-09-23). */
  onChanged: () => void | Promise<void>
}) {
  const [list, setList] = useState<ConductList | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [playerId, setPlayerId] = useState('')
  const [reason, setReason] = useState('')
  const [appliesFromWeek, setAppliesFromWeek] = useState(1)
  const [saving, setSaving] = useState(false)

  // Accessible names for the form's inputs: an explicit htmlFor/id pair
  // rather than relying on the wrapping <label> alone, since a live check
  // found the browser's accessibility tree listing these as unnamed
  // textboxes (2026-09-23 fix).
  const playerIdInputId = useId()
  const reasonInputId = useId()
  const weekInputId = useId()

  useEffect(() => {
    let cancelled = false
    fetchConductList(leagueSleeperId)
      .then((d) => {
        if (!cancelled) setList(d)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [leagueSleeperId])

  async function refresh() {
    const d = await fetchConductList(leagueSleeperId)
    setList(d)
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await saveConductEntry(leagueSleeperId, { playerId, reason, appliesFromWeek })
      await refresh()
      await onChanged()
      setPlayerId('')
      setReason('')
      setAppliesFromWeek(1)
      setFormOpen(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function remove(entryId: number) {
    setError(null)
    try {
      await deleteConductEntry(leagueSleeperId, entryId)
      await refresh()
      await onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const canEdit = commissionerListAvailable && Boolean(list?.canEdit)

  return (
    <section className="panel sl-conduct">
      <h3 className="cond">Commissioner&apos;s list</h3>
      <p className="muted small">
        This list is for this season only -- a new season starts with an empty one.
      </p>

      {requestedSeason != null && (
        <p className="muted small">
          Showing the {season} season — the list for the new season opens once it has a scored week.
        </p>
      )}

      {error && <p className="error small">{error}</p>}

      {!commissionerListAvailable && (
        <p className="muted small">
          No commissioner is known for this league, so the list can&apos;t be edited.
        </p>
      )}

      {list && list.entries.length === 0 ? (
        <p className="muted small">Nobody&apos;s on the list.</p>
      ) : (
        <ul className="sl-conduct-list">
          {list?.entries.map((e) => (
            <li key={e.id} className="sl-conduct-row">
              <span className="sl-conduct-player">{e.playerName}</span>
              <span className="muted small">{e.reason}</span>
              <span className="muted small">applies from week {e.appliesFromWeek}</span>
              <span className="muted small">added by {e.addedBy ?? 'the commissioner'}</span>
              {canEdit && (
                <button
                  type="button"
                  className="action-button sl-conduct-remove"
                  onClick={() => remove(e.id)}
                >
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canEdit && (
        <div className="sl-conduct-form">
          {formOpen ? (
            <>
              <div className="sl-conduct-field">
                <label htmlFor={playerIdInputId}>Sleeper player id</label>
                <input
                  id={playerIdInputId}
                  value={playerId}
                  onChange={(e) => setPlayerId(e.target.value)}
                />
              </div>
              <div className="sl-conduct-field">
                <label htmlFor={reasonInputId}>Reason</label>
                <input
                  id={reasonInputId}
                  value={reason}
                  maxLength={140}
                  onChange={(e) => setReason(e.target.value)}
                />
              </div>
              <div className="sl-conduct-field">
                <label htmlFor={weekInputId}>Applies from week</label>
                <input
                  id={weekInputId}
                  type="number"
                  min={1}
                  value={appliesFromWeek}
                  onChange={(e) => setAppliesFromWeek(Math.max(1, Number(e.target.value) || 1))}
                />
              </div>
              <div className="controls-inline">
                <button type="button" onClick={save} disabled={saving || !playerId || !reason}>
                  Save
                </button>
                <button type="button" className="action-button" onClick={() => setFormOpen(false)}>
                  Cancel
                </button>
              </div>
            </>
          ) : (
            <button type="button" onClick={() => setFormOpen(true)}>
              Add to the list
            </button>
          )}
        </div>
      )}
    </section>
  )
}

import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import Avatar from '../components/Avatar'
import HowThisWorks from '../components/HowThisWorks'
import NotFound from '../components/NotFound'
import PageHeader from '../components/PageHeader'
import PlayerLink from '../components/PlayerLink'
import {
  getAdpCompare,
  type AdpCompareResponse,
  type AdpDraft,
  type AdpPlayer,
  type AdpSaleRow,
  type AdpSource,
  type AdpTeamState,
} from '../api'
import {
  BLEND_SUBLINE_MIN_WEIGHT,
  FULL_TRUST_GAP_DEFAULT,
  GAP_THRESHOLD_DEFAULT,
  TRUST_DEFAULT,
  YAHOO_MIN_PCT_DEFAULT,
  blendOrder,
  gapText,
  rescale,
  sortByBlendRank,
  sortRows,
  tintOf,
  yahooReliable,
  type Blended,
  type SortCol,
  type SortDir,
} from '../adpBlend'
import { DASH } from '../statCells'
import { useLeagueDataVersion } from '../leagueDataVersion'
import { useBlock } from '../useBlock'
import { useLiveDraft } from '../useLiveDraft'

/**
 * Spec 046 US1: Sleeper dynasty ADP, Sleeper redraft ADP and Yahoo ADP/price side by side, with where each
 * number came from and where they disagree.
 *
 * The table scrolls sideways INSIDE its own container with the player column pinned (the stats leaderboard's
 * `.sl-wrap`), so the page never scrolls sideways at 375px. A gap is always spelled out in words: the tint is
 * a second encoding of it, never the only one. Every number the page cannot show says why (a dash with a
 * reason, or a greyed value with the share of Yahoo leagues behind it) -- never a blank, never a 0.
 *
 * US2 adds the trust-Yahoo blend and Blend $ (a derived guide; every knob is hand-set and says so). US3 adds the
 * auction half -- budget cards, a latest-sales feed and sold prices -- when the league's draft is an auction. The
 * budget cards are cards (repeated, each a person); the player table stays a dense table.
 */

const POSITIONS = ['PG', 'SG', 'SF', 'PF', 'C'] as const

const fmtAdp = (v: number | null) => (v == null ? DASH : v.toFixed(1))
const fmtCost = (v: number | null) => (v == null ? DASH : `$${v.toFixed(1)}`)
const pct = (v: number) => `${Math.round(v * 100)}%`
const fmtDollars = (v: number | null) => (v == null ? DASH : `$${v}`)

/** The two blend columns sort by blended rank on top of the plain columns. */
type PageSort = SortCol | 'blend' | 'blendCost'

/** The reason the Blend $ column is not shown (FR-013): always says why, never just hides. */
function blendCostReason(d: AdpCompareResponse, prices: number[] | null): string | null {
  if (prices) return null
  if (!d.draft) return 'this league has no Sleeper draft to take teams, rounds and a budget from'
  // Spec 046 C1: an auction row ingested before the budget was recorded has a null budget too; saying it
  // "isn't an auction" there would sit directly above that auction's own Budgets section.
  if (d.draft.budget == null) {
    return d.draft.type === 'auction'
      ? 'Sleeper hasn’t reported this auction’s budget yet (re-ingest the league to load it)'
      : 'this league’s draft has no budget (it isn’t an auction)'
  }
  if (d.costCurve.length === 0) return 'Yahoo has no price capture yet'
  return 'Yahoo’s prices have no spread above $1 to scale'
}

/**
 * Spec 046 US3: every slot's money, then the latest sales. The budget cards come from the server's per-slot
 * arithmetic (FR-011), never recomputed here, and the sales feed says "price missing" rather than showing $0.
 */
function AuctionBoard({
  draft, d, blendOf, prices,
}: { draft: AdpDraft; d: AdpCompareResponse; blendOf: Map<AdpPlayer, Blended<AdpPlayer>>; prices: number[] | null }) {
  const teams = draft.teamsState ?? []
  const sales = draft.sales ?? []
  const byManager = new Map<number, AdpTeamState>()
  teams.forEach((t) => { if (t.managerId != null) byManager.set(t.managerId, t) })
  const byPlayerId = new Map<number, AdpPlayer>()
  d.players.forEach((p) => { if (p.playerId != null) byPlayerId.set(p.playerId, p) })
  const latest = sales.slice(0, 12)
  // Spec 046 C2: a finished auction ingested before prices were recorded has every amount null. One line says
  // so, with the count, instead of a feed of identical "price missing" rows (the per-team counts stay).
  const unpriced = sales.length > 0 && sales.every((r) => r.amount == null)
    && (draft.budget == null || draft.status === 'complete')
  return (
    <>
      <section className="pp-card" aria-label="Team budgets">
        <h2 className="section-title">Budgets</h2>
        <p className="muted small pp-line">
          {`Max bid is what a team can open at and still afford $1 for each of its other empty spots. ${draft.rounds} spots each${draft.budget != null ? `, $${draft.budget} to start` : ''}.`}
        </p>
        <ul className="adp-budgets">
          {teams.map((t) => (
            <li key={t.slot} className={`adp-budget${t.mine ? ' mine' : ''}`}>
              <div className="adp-budget-who">
                <Avatar avatarId={t.avatar} seed={String(t.managerId ?? `slot-${t.slot}`)} label={t.name ?? `Slot ${t.slot}`} isMe={t.mine} />
                <span className="adp-budget-name">{t.name ?? `Slot ${t.slot}`}</span>
                {t.mine && <span className="adp-you">You</span>}
              </div>
              <div className="adp-budget-left">
                {fmtDollars(t.left)}
                <small>left</small>
              </div>
              <div className="adp-budget-facts">
                <span>{`${t.spotsLeft} ${t.spotsLeft === 1 ? 'spot' : 'spots'} left`}</span>
                <span>{`max bid ${fmtDollars(t.maxBid)}`}</span>
                {t.priceMissing > 0 && (
                  <span className="adp-miss">{`${t.priceMissing} ${t.priceMissing === 1 ? 'price' : 'prices'} missing`}</span>
                )}
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section className="pp-card" aria-label="Latest sales">
        <h2 className="section-title">Latest sales</h2>
        {latest.length === 0 ? (
          <p className="muted small">Nobody has been sold yet.</p>
        ) : unpriced ? (
          <p className="muted small" data-testid="prices-not-ingested">
            {`${sales.length} ${sales.length === 1 ? 'player is' : 'players are'} recorded as sold, but none has a price yet: prices haven’t been ingested for this auction. Re-ingest the league to load them.`}
          </p>
        ) : (
          <ul className="adp-feed">
            {latest.map((r) => (
              <SaleLine key={r.pickNo} r={r} buyer={r.managerId != null ? byManager.get(r.managerId)?.name ?? null : null}
                guide={saleGuide(r, byPlayerId, blendOf, prices)} />
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

/** What Blend $ said about this player, or null when there is nothing honest to compare against. */
function saleGuide(
  r: AdpSaleRow, byPlayerId: Map<number, AdpPlayer>, blendOf: Map<AdpPlayer, Blended<AdpPlayer>>, prices: number[] | null,
): number | null {
  if (!prices || r.playerId == null) return null
  const p = byPlayerId.get(r.playerId)
  const b = p ? blendOf.get(p) : undefined
  return b ? prices[b.rank - 1] ?? null : null
}

function SaleLine({ r, buyer, guide }: { r: AdpSaleRow; buyer: string | null; guide: number | null }) {
  return (
    <li>
      <span className="adp-feed-name">{r.name}</span>
      <span className="muted small">{buyer ? `to ${buyer}` : 'to a team not matched to a manager'}</span>
      <span className="adp-feed-price">
        {r.priceMissing || r.amount == null ? <span className="adp-miss">price missing</span> : <strong>{`$${r.amount}`}</strong>}
      </span>
      {!r.priceMissing && r.amount != null && guide != null && (
        <span className={`adp-feed-vs ${r.amount > guide ? 'over' : r.amount < guide ? 'under' : ''}`}>
          {`Blend $ ${guide}: ${r.amount === guide ? 'right on it' : r.amount > guide ? `paid $${r.amount - guide} over` : `paid $${guide - r.amount} under`}`}
        </span>
      )}
    </li>
  )
}

function fetchedOn(iso: string): string {
  // The date is a calendar day (UTC capture date), so format it without a time zone shifting it.
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d) return iso
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
}

function SourceStrip({ sources }: { sources: AdpSource[] }) {
  return (
    <ul className="adp-sources" aria-label="Where each ADP comes from">
      {sources.map((s) => (
        <li key={s.key} className={`adp-source${s.capturedOn ? '' : ' off'}`}>
          <span className="adp-source-name">{s.label}</span>
          {s.capturedOn ? (
            <>
              <span className="muted small">{`fetched on ${fetchedOn(s.capturedOn)}`}</span>
              <span className="muted small">{`${s.ranked} players ranked`}</span>
              {s.key === 'yahoo' && s.unmatched != null && s.unmatched > 0 && (
                <span className="small adp-source-warn">{`${s.unmatched} not matched to a player`}</span>
              )}
            </>
          ) : (
            <span className="small adp-source-warn">unavailable</span>
          )}
        </li>
      ))}
    </ul>
  )
}

function Header({
  col, label, title, sort, onSort, pin,
}: { col: PageSort; label: string; title: string; sort: { col: PageSort; dir: SortDir }; onSort: (c: PageSort) => void; pin?: boolean }) {
  const on = sort.col === col
  return (
    <th scope="col" aria-sort={on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'} className={pin ? 'sl-pin' : undefined}>
      <button type="button" className={`sl-sort${on ? ' on' : ''}`} title={title} onClick={() => onSort(col)}>
        {label}
        <span aria-hidden="true" className="sl-arrow">{on ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span>
      </button>
    </th>
  )
}

function Row({
  p, d, leagueId, threshold, minPct, blend, price, showPrice, buyer,
}: {
  p: AdpPlayer; d: AdpCompareResponse; leagueId: string; threshold: number; minPct: number
  blend: Blended<AdpPlayer> | undefined; price: number | null; showPrice: boolean; buyer: string | null
}) {
  const tint = tintOf(p, threshold, minPct)
  const unreliable = p.yahooAdp != null && !yahooReliable(p, minPct)
  const share = p.yahooPctDrafted != null ? `drafted in ${pct(p.yahooPctDrafted)} of Yahoo leagues` : 'drafted in an unknown share of Yahoo leagues'
  const pos = p.positions.join('/')
  const sold = p.sale != null
  return (
    <tr className={[p.playerId == null ? 'adp-unmatched' : '', sold ? 'adp-sold-row' : ''].filter(Boolean).join(' ') || undefined}>
      <th scope="row" className="sl-pin">
        {p.sleeperId ? (
          <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={p.sleeperId} sport={d.sport}>{p.name}</PlayerLink>
        ) : (
          p.name
        )}
        <span className="sl-sub muted small">{[pos, p.team].filter(Boolean).join(' · ') || DASH}</span>
        {p.playerId == null && <span className="sl-sub sl-unranked small">not matched to a player</span>}
        {p.sale && (
          <span className="sl-sub small adp-sold-tag">
            {p.sale.amount == null ? 'Sold · price missing' : `Sold $${p.sale.amount}`}
            {buyer ? ` · ${buyer}` : ''}
          </span>
        )}
      </th>
      <td>{p.yearsExp == null ? DASH : p.yearsExp === 0 ? 'Rookie' : `${p.yearsExp} yr`}</td>
      <td>{fmtAdp(p.dynasty)}</td>
      <td>{fmtAdp(p.redraft)}</td>
      <td className={unreliable ? 'adp-grey' : undefined}>
        {fmtAdp(p.yahooAdp)}
        {unreliable && <span className="sl-sub small">{share}</span>}
      </td>
      <td className={unreliable ? 'adp-grey' : undefined}>{fmtCost(p.yahooCost)}</td>
      <td className="adp-gap-cell">
        <span className={`adp-gap${tint ? ` ${tint}` : ''}`}>{gapText(p, minPct)}</span>
      </td>
      <td className="adp-blend-cell">
        {blend ? fmtAdp(blend.blend) : DASH}
        {blend && blend.weight >= BLEND_SUBLINE_MIN_WEIGHT && (
          <span className="sl-sub muted small">{`${pct(blend.weight)} of the way to Yahoo`}</span>
        )}
      </td>
      {showPrice && <td className="adp-price-cell">{fmtDollars(price)}</td>}
    </tr>
  )
}

export default function AdpCompare() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const leagueId = sleeperLeagueId ?? ''
  const version = useLeagueDataVersion(sleeperLeagueId)

  const [query, setQuery] = useState('')
  const [position, setPosition] = useState<string | null>(null)
  const [rookiesOnly, setRookiesOnly] = useState(false)
  const [threshold, setThreshold] = useState(GAP_THRESHOLD_DEFAULT)
  const [sort, setSort] = useState<{ col: PageSort; dir: SortDir }>({ col: 'dynasty', dir: 'asc' })
  // Hand-set and ARBITRARY (FR-013); held as whole percents so the slider has no float steps.
  const [trustPct, setTrustPct] = useState(Math.round(TRUST_DEFAULT * 100))
  const [fullGap, setFullGap] = useState(FULL_TRUST_GAP_DEFAULT)
  const [hideSold, setHideSold] = useState(true)

  const block = useBlock(leagueId ? () => getAdpCompare(leagueId) : null, [leagueId, version])
  // A live refetch (below) replaces the first load in place, without the loading flash a dependency change on
  // useBlock would cause, which would unmount the table mid-draft.
  const [fresh, setFresh] = useState<AdpCompareResponse | null>(null)
  useEffect(() => { setFresh(null) }, [leagueId, version])
  const d = fresh ?? (block.status === 'ok' ? block.data : null)

  const auction = d?.draft?.type === 'auction'
  // Spec 046 C6: 'paused' too -- Sleeper's auction autopause is a setting, and a page opened while paused must
  // pick the draft up on resume instead of staying frozen.
  const following = auction
    && (d?.draft?.status === 'pre_draft' || d?.draft?.status === 'drafting' || d?.draft?.status === 'paused')
  // The existing stream, not a second poll: '' keeps the hook from connecting when there is nothing to follow.
  const { live } = useLiveDraft(following && d?.draft ? d.draft.sleeperDraftId : '')
  const picksMade = live?.picksMade ?? null
  // Spec 046 C6: a pick count counts as fetched only once the fetch SUCCEEDED, so a failed refetch is retried on
  // the next stream event (`live` is a new object per frame) instead of leaving the sales one pick behind for good.
  // `inflight` stops the next frame starting a second fetch for the same count while one is still running.
  const fetchedFor = useRef<number | null>(null)
  const inflight = useRef<number | null>(null)
  const leagueRef = useRef(leagueId)
  leagueRef.current = leagueId
  const salesSeen = d?.draft?.sales?.length ?? null
  useEffect(() => {
    if (picksMade == null || !leagueId) return
    // The page already holds this many sales; only a different count means a pick landed since.
    if (picksMade === salesSeen || fetchedFor.current === picksMade || inflight.current === picksMade) return
    inflight.current = picksMade
    const forLeague = leagueId
    getAdpCompare(forLeague)
      .then((r) => {
        if (leagueRef.current !== forLeague) return
        fetchedFor.current = picksMade
        setFresh(r)
      })
      .catch(() => {})
      .finally(() => { inflight.current = null })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, picksMade, leagueId])

  const yahoo = d?.sources.find((s) => s.key === 'yahoo')
  const minPct = yahoo?.minPercentDrafted ?? YAHOO_MIN_PCT_DEFAULT

  const trust = trustPct / 100
  // Ranked over the WHOLE list, so filtering and sorting never change a player's blended rank or Blend $.
  const blended = useMemo(() => (d ? blendOrder(d.players, trust, fullGap, minPct) : []), [d, trust, fullGap, minPct])
  const blendOf = useMemo(() => new Map(blended.map((b) => [b.row, b])), [blended])
  const prices = useMemo(
    () => (d ? rescale(d.costCurve, d.draft?.teams ?? null, d.draft?.rounds ?? null, d.draft?.budget ?? null) : null),
    [d],
  )
  const priceOf = (p: AdpPlayer): number | null => {
    const b = blendOf.get(p)
    return b && prices ? (prices[b.rank - 1] ?? null) : null
  }
  const buyerName = useMemo(() => {
    const m = new Map<number, string>()
    d?.draft?.teamsState?.forEach((t) => { if (t.managerId != null && t.name) m.set(t.managerId, t.name) })
    return m
  }, [d])

  const shown = useMemo(() => {
    if (!d) return []
    const q = query.trim().toLowerCase()
    const filtered = d.players.filter((p) => {
      if (q && !p.name.toLowerCase().includes(q) && !(p.team ?? '').toLowerCase().includes(q)) return false
      if (position && !p.positions.includes(position)) return false
      if (rookiesOnly && p.yearsExp !== 0) return false
      if (auction && hideSold && p.sale) return false
      return true
    })
    if (sort.col === 'blend' || sort.col === 'blendCost') return sortByBlendRank(filtered, blendOf, sort.dir)
    return sortRows(filtered, sort.col, sort.dir, minPct)
  }, [d, query, position, rookiesOnly, sort, minPct, auction, hideSold, blendOf])

  if (block.status === 'error' && block.notFound) return <NotFound what="league" />

  // First click sorts a column ascending (best, i.e. earliest, ADP first); a second click reverses it.
  const onSort = (col: PageSort) =>
    setSort((s) => (s.col === col ? { col, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { col, dir: 'asc' }))

  return (
    <div className="content">
      <PageHeader
        eyebrow="League · ADP"
        title="ADP & prices"
        sub="Where Sleeper’s dynasty drafters and Yahoo’s redraft leagues disagree about the same players."
      />

      {(block.status === 'loading' || block.status === 'idle') && <p className="muted small" role="status">Loading…</p>}
      {block.status === 'error' && <p className="muted small">Couldn’t load the ADP comparison.</p>}

      {d && (
        <>
          <SourceStrip sources={d.sources} />

          {auction && d.draft && <AuctionBoard draft={d.draft} d={d} blendOf={blendOf} prices={prices} />}

          <section className="pp-card adp-blend" aria-label="Blend settings">
            <h2 className="section-title">Trust Yahoo</h2>
            <div className="adp-blend-sliders">
              <label>
                <span className="small">
                  {`Trust in Yahoo: ${trustPct}%`}
                  <span className="adp-hand-set">hand-set</span>
                </span>
                <input
                  type="range" min={0} max={100} step={5} value={trustPct}
                  aria-label="Trust in Yahoo, percent"
                  onChange={(e) => setTrustPct(Number(e.target.value))}
                />
              </label>
              <label>
                <span className="small">
                  {`Full-trust gap: ${fullGap} picks`}
                  <span className="adp-hand-set">hand-set</span>
                </span>
                <input
                  type="range" min={10} max={60} step={5} value={fullGap}
                  aria-label="Gap at which Yahoo gets its full say, in picks"
                  onChange={(e) => setFullGap(Number(e.target.value))}
                />
              </label>
            </div>
            <p className="muted small pp-line">
              {`The blended ADP moves each player from his dynasty ADP toward Yahoo’s, by the trust above times how big the gap is next to ${fullGap} picks. At 0% it is the dynasty order. A Yahoo value drafted in under ${pct(minPct)} of Yahoo leagues is not counted.`}
            </p>
          </section>

          <div className="sl-filters adp-filters">
            <label className="sl-field adp-search">
              <span className="muted small">Search</span>
              <input
                type="search"
                value={query}
                placeholder="Player or team"
                onChange={(e) => setQuery(e.target.value)}
              />
            </label>
            <div className="sl-field">
              <span className="muted small" id="adp-pos-label">Position</span>
              <div className="adp-chips" role="group" aria-labelledby="adp-pos-label">
                <button type="button" className={`chip${position == null ? ' on' : ''}`} aria-pressed={position == null} onClick={() => setPosition(null)}>All</button>
                {POSITIONS.map((x) => (
                  <button
                    key={x}
                    type="button"
                    className={`chip pos-chip ${x}${position === x ? ' on' : ''}`}
                    aria-pressed={position === x}
                    onClick={() => setPosition(position === x ? null : x)}
                  >
                    {x}
                  </button>
                ))}
              </div>
            </div>
            <label className="sl-check adp-rookies">
              <input type="checkbox" checked={rookiesOnly} onChange={(e) => setRookiesOnly(e.target.checked)} />
              <span>Rookies only</span>
            </label>
            {auction && (
              <label className="sl-check adp-rookies">
                <input type="checkbox" checked={hideSold} onChange={(e) => setHideSold(e.target.checked)} />
                <span>Hide sold</span>
              </label>
            )}
            <label className="sl-field adp-threshold">
              <span className="muted small">{`Highlight gaps of ${threshold} picks or more`}</span>
              <input
                type="range"
                min={5}
                max={60}
                step={5}
                value={threshold}
                aria-label="Gap threshold in picks"
                onChange={(e) => setThreshold(Number(e.target.value))}
              />
            </label>
          </div>

          {/* Spec 046 live verification: each swatch is grouped with its own sentence, so a wrap can't leave
              the red swatch dangling at the end of the green line (seen at desktop width). */}
          <p className="muted small pp-line adp-legend">
            <span className="adp-legend-item">
              <span className="adp-key yahoo" aria-hidden="true" />
              {`Green: Yahoo takes him ${threshold}+ picks earlier than dynasty drafters do.`}
            </span>
            <span className="adp-legend-item">
              <span className="adp-key sleeper" aria-hidden="true" />
              {`Red: dynasty takes him ${threshold}+ picks earlier than Yahoo does. Every gap is also written out in words.`}
            </span>
          </p>

          <section className="pp-card" aria-label="ADP comparison">
            <p className="muted small pp-line">
              {`${shown.length} of ${d.players.length} players. ADP is the average pick number, so smaller is earlier. Click a header to sort; click again to reverse. Players with no value sort last.`}
            </p>
            {d.players.length === 0 ? (
              <p className="muted small">No ADP has been captured yet, so there is nothing to compare.</p>
            ) : (
              <div className="sl-wrap">
                <table className="sl-table adp-table">
                  <thead>
                    <tr>
                      <Header col="name" label="Player" title="Sort by name" sort={sort} onSort={onSort} pin />
                      <Header col="years" label="Years" title="Years in the league; a rookie has 0" sort={sort} onSort={onSort} />
                      <Header col="dynasty" label="Dynasty" title="Sleeper dynasty ADP: drafters playing for years" sort={sort} onSort={onSort} />
                      <Header col="redraft" label="Redraft" title="Sleeper redraft ADP: drafters playing for this season" sort={sort} onSort={onSort} />
                      <Header col="yahooAdp" label="Yahoo ADP" title="Yahoo’s average pick across its public redraft leagues" sort={sort} onSort={onSort} />
                      <Header col="yahooCost" label="Yahoo $" title="Yahoo’s average auction price in its public leagues, on a $200 budget" sort={sort} onSort={onSort} />
                      <Header col="gap" label="Gap" title="Yahoo ADP minus dynasty ADP; negative means Yahoo takes him earlier" sort={sort} onSort={onSort} />
                      <Header col="blend" label="Blended ADP" title="Dynasty ADP moved toward Yahoo’s by the trust sliders (hand-set)" sort={sort} onSort={onSort} />
                      {prices && (
                        <Header col="blendCost" label="Blend $" title="Yahoo’s price curve scaled to this league’s total budget, by blended rank. A derived guide" sort={sort} onSort={onSort} />
                      )}
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((p, i) => (
                      <Row
                        // Spec 046 C13: an unmatched Yahoo row has no id; name+team+ADP, and the position in the list if even those collide.
                        key={p.playerId ?? `y:${p.name}:${p.team ?? ''}:${p.yahooAdp ?? ''}:${i}`} p={p} d={d} leagueId={leagueId} threshold={threshold} minPct={minPct}
                        blend={blendOf.get(p)} price={priceOf(p)} showPrice={prices != null}
                        buyer={p.sale?.managerId != null ? buyerName.get(p.sale.managerId) ?? null : null}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {d.players.length > 0 && shown.length === 0 && <p className="muted small">No players match these filters.</p>}
          </section>

          <ul className="muted small pp-notes adp-notes" aria-label="Notes on these numbers">
            <li>
              {'Yahoo’s numbers are redraft: its drafters play for this season, so young players and rookies sit later there and veterans earlier. A gap is a difference in what the two groups value, not an error on either side.'}
            </li>
            <li>
              {`Most Yahoo public leagues are assumed to be category leagues. That is an assumption, not something this page has checked, and it can move a player relative to points leagues.`}
            </li>
            <li>
              {`A Yahoo value drafted in under ${pct(minPct)} of Yahoo’s leagues is greyed, left out of the gap and left out of tinting. The ${pct(minPct)} line is hand-set and arbitrary.`}
            </li>
            <li>
              {'The blend settings (trust and the full-trust gap) are hand-set and arbitrary. Blended ADP and Blend $ are derived guides, not measurements.'}
            </li>
            <li data-testid="blend-cost-note">
              {'Blend $ is Yahoo’s price curve scaled to add up to this league’s total budget. It does not model how fewer teams and deeper rosters push money to the top; in the one auction measured, the top sold 25–50% above it.'}
              {(() => {
                const why = blendCostReason(d, prices)
                return why ? ` Blend $ is unavailable here: ${why}.` : ''
              })()}
            </li>
          </ul>
          <HowThisWorks>
            <p className="muted small">
              {'Each source is captured once a day and shown as of the date it was fetched; “fetched on” is when the app got it, not how fresh the source’s own data is. The gap is Yahoo ADP minus dynasty ADP, in picks. The highlight threshold (20 picks to start) is hand-set and arbitrary: move the slider to see which rows depend on it.'}
            </p>
            {yahoo && yahoo.deepestAdp != null && (
              <p className="muted small">
                {`Yahoo ranks players down to about pick ${Math.round(yahoo.deepestAdp)}; below that there is no Yahoo value, not a bad one.`}
              </p>
            )}
          </HowThisWorks>
        </>
      )}
    </div>
  )
}

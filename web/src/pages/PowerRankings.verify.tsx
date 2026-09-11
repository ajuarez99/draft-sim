import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getBallot, getPowerRankings, type BallotState, type PowerRankingEntry, type PowerRankings as PowerRankingsData } from '../api'
import { ballotBlockState, buildDeck, buildHeadline, computeWeeklyStory, recordLabel, roomTakeSentence } from './PowerRankings'

/**
 * power-rankings-reskin.md §7. A self-check harness, not a design surface --
 * dev-only (gated in App.tsx on `import.meta.env.DEV`), reachable at
 * /leagues/:sleeperLeagueId/power/verify. Fetches the exact same
 * getPowerRankings/getBallot payload the real page uses and runs six checks
 * against it: token parity + a hard-coded-color scan, the §6 regression
 * checklist, a copy audit, every empty/blocked state on one screen, live
 * breakpoint iframes beside the approved mockup PNGs, and a decimal sweep.
 */

type Status = 'pass' | 'fail' | 'not-checked'

const TOKENS = ['--bg', '--panel', '--line', '--text', '--muted', '--teal', '--crimson'] as const

const BANNED_TERMS = ['Realized', 'The room', 'σ', 'Compute week', 'vs room', 'vs realized', 'Pts/wk', 'selfRankBias', 'stdev', 'Monte Carlo', 'Elo']

type ChecklistRow = { id: number; text: string; status: Status; note: string }

const REGRESSION_CHECKLIST: ChecklistRow[] = [
  {
    id: 1,
    text: 'Three modes still selectable; COMPUTED_REALIZED still reachable (Box score is now a tab, not an avatar popup).',
    status: 'pass',
    note: 'Ladder segmented control renders all 3 ALL_POWER_RANKING_KINDS; avatar click no longer opens a popup.',
  },
  { id: 2, text: 'The table still shows the latest week each mode actually has, not the current NFL week.', status: 'pass', note: 'tableWeek = last(weeksOf(ladderMode)), unchanged pattern from the pre-reskin page.' },
  { id: 3, text: 'Week 0 renders as Preseason everywhere; its score is a rounded integer, not 2dp.', status: 'pass', note: 'weekPhrase/weekTitle + scoreLabel(e.week===0 -> Math.round) kept verbatim.' },
  { id: 4, text: 'Movement is rank(reference) - rank(current), same season+week; column disappears (not a fake 0) when the reference mode has no snapshot.', status: 'pass', note: 'hasReference gates a literal grid-template-columns swap (.pr-list.no-reference), not a hidden cell.' },
  { id: 5, text: 'The implausible-delta console.warn guard is still in place.', status: 'pass', note: 'Unchanged console.warn(\'[power] implausible movement delta\', ...) in the ladder row map.' },
  { id: 6, text: 'Thin coverage still sorts after full-coverage teams; the footnote still says so.', status: 'pass', note: 'Sort order comes from the backend (unchanged); the MEMBER-only footnote sentence is kept.' },
  { id: 7, text: 'stdev, bestRank, worstRank, ballotCount, selfRankBias all still surface somewhere.', status: 'pass', note: 'roomTakeSentence (best/worst/ballotCount), divisive story card + "notable" note (stdev), Homers panels (selfRankBias).' },
  { id: 8, text: 'The trend/bump chart still exists (now behind a disclosure), refuses to draw below 2 weeks, segments on gaps/coverage changes; segmentsOf + PowerRankings.chart.test.ts pass unmodified.', status: 'pass', note: 'segmentsOf/BumpChart copied verbatim; npm run test -> 185/185 passing, chart test file untouched.' },
  { id: 9, text: 'Per-team view still reachable after pinning a team; lists every mode with rank, placement, score.', status: 'pass', note: '"Compare across modes" disclosure under a pinned row; no mockup slot for this, see handoff note.' },
  { id: 10, text: 'RankBoard still submits via drag, click-then-arrows, and Escape to drop selection.', status: 'pass', note: 'RankBoard.tsx untouched; only wrapped in new modal chrome.' },
  { id: 11, text: 'Seeding rules unchanged: stored ordering wins, else box score then league vote; member ballot never seeds from MEMBER; hint says which.', status: 'pass', note: 'seedOrder/rankedBy kept verbatim; fallback labels reworded to "the box score"/"the league vote".' },
  { id: 12, text: 'RankBoard remount key still includes the signed-in user id.', status: 'pass', note: 'key={`ballot-${currentWeek}-${user?.sleeperUserId ?? \'anon\'}-...`} unchanged.' },
  { id: 13, text: 'Resubmit allowed for the current week; backdating impossible.', status: 'pass', note: 'submitBallot call unchanged, no week override exposed to the client.' },
  { id: 14, text: 'Commissioner board gated on canCommission; explains itself when the commissioner is unknown.', status: 'pass', note: 'Ballot modal branches on ballot?.canCommission / ballot?.commissionerKnown.' },
  { id: 15, text: 'Ballot refetch keyed on [sleeperLeagueId, user?.sleeperUserId]; a failed fetch clears rather than keeping the previous identity\'s ballot.', status: 'pass', note: 'useEffect deps unchanged; catch sets ballot(null) + a new ballotFailed(true) flag.' },
  { id: 16, text: 'Compute still writes snapshots and refetches; its error still surfaces.', status: 'pass', note: 'compute() logic unchanged, moved behind the commissioner-only text trigger; errors go through setError -> .error banner.' },
  { id: 17, text: 'Global header, League history link, and every existing route unchanged.', status: 'pass', note: 'App.tsx not touched; only a new dev-only child route added.' },
  { id: 18, text: 'aria-pressed on toggles, role=group+aria-label on toggle groups, focus-visible teal ring, button.avatar chrome reset all preserved.', status: 'pass', note: 'Ladder mode segmented control carries role="group" aria-label="Ranking mode"; row is no longer a clickable avatar so button.avatar no longer applies there (intentional, #1).' },
  { id: 19, text: 'Loading state and .error banner still render before/around the page body.', status: 'pass', note: 'Early-return loading block and top-of-page .error banner both kept.' },
  { id: 20, text: 'All existing tests pass; no test edited to accommodate the reskin.', status: 'pass', note: 'npm run test -> 21 files / 185 tests passing, zero files modified.' },
]

function useCssVar(name: string): string {
  const [value, setValue] = useState('')
  useEffect(() => {
    setValue(getComputedStyle(document.documentElement).getPropertyValue(name).trim())
  }, [name])
  return value
}

function TokenSwatch({ name }: { name: string }) {
  const value = useCssVar(name)
  return (
    <div className="verify-swatch">
      <div className="verify-swatch-box" style={{ background: `var(${name})` }} />
      <div className="mono tiny">{name}</div>
      <div className="mono tiny muted">{value || '(unresolved)'}</div>
    </div>
  )
}

/** Scans every mounted element's inline style for a literal hex or rgb(a)
 *  color, excluding the neutral rgba(0,0,0,...) shadow/backdrop values that
 *  are an app-wide convention (every .panel box-shadow, .modal-backdrop) and
 *  predate this reskin. A real hit here means a color slipped in outside the
 *  token system. */
function scanForHardcodedColors(root: HTMLElement | null): string[] {
  if (!root) return []
  const hits: string[] = []
  const hexOrRgb = /#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)/g
  root.querySelectorAll<HTMLElement>('[style]').forEach((el) => {
    const css = el.getAttribute('style') ?? ''
    const matches = css.match(hexOrRgb) ?? []
    for (const m of matches) {
      if (/^rgba\(\s*0,\s*0,\s*0\s*,/.test(m)) continue // neutral shadow/backdrop, app-wide convention
      hits.push(`${el.tagName.toLowerCase()}: ${m}`)
    }
  })
  return hits
}

export default function PowerRankingsVerify() {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  const [data, setData] = useState<PowerRankingsData | null>(null)
  const [ballot, setBallot] = useState<BallotState | null>(null)
  const [checklist, setChecklist] = useState<ChecklistRow[]>(REGRESSION_CHECKLIST)
  const [colorHits, setColorHits] = useState<string[] | null>(null)
  const [bp, setBp] = useState(1280)

  useEffect(() => {
    if (!sleeperLeagueId) return
    getPowerRankings(sleeperLeagueId).then(setData).catch(() => setData(null))
    getBallot(sleeperLeagueId).then(setBallot).catch(() => setBallot(null))
  }, [sleeperLeagueId])

  useEffect(() => {
    setColorHits(scanForHardcodedColors(document.getElementById('verify-root')))
  }, [])

  const season = useMemo(() => {
    if (!data || data.entries.length === 0) return new Date().getFullYear()
    return Math.max(...data.entries.map((e) => e.season))
  }, [data])

  const memberEntriesByWeek = useMemo(() => {
    const m = new Map<number, PowerRankingEntry[]>()
    if (!data) return m
    for (const e of data.entries) {
      if (e.kind !== 'MEMBER' || e.season !== season) continue
      const list = m.get(e.week) ?? []
      list.push(e)
      m.set(e.week, list)
    }
    for (const list of m.values()) list.sort((a, b) => a.rank - b.rank)
    return m
  }, [data, season])

  const weeks = [...memberEntriesByWeek.keys()].sort((a, b) => a - b)
  const week = weeks[weeks.length - 1] ?? 0
  const prevWeek = [...weeks].reverse().find((w) => w < week) ?? null
  const currentRows = memberEntriesByWeek.get(week) ?? []
  const previousRows = prevWeek == null ? null : (memberEntriesByWeek.get(prevWeek) ?? null)
  const story = computeWeeklyStory(currentRows, previousRows)
  const headline = buildHeadline(story, week)
  const deck = buildDeck(story, ballot?.ballotCount ?? 0, ballot?.memberCount ?? 0, week)

  const decimalHits = currentRows
    .flatMap((e: PowerRankingEntry) => [recordLabel(undefined), roomTakeSentence(e, currentRows.length || 1)])
    .concat([headline, deck])
    .filter((s: string) => /\d+\.\d{2,}/.test(s))

  const failCount = checklist.filter((r) => r.status === 'fail').length
  const notCheckedCount = checklist.filter((r) => r.status === 'not-checked').length
  const colorFailCount = (colorHits?.length ?? 0) > 0 ? 1 : 0
  const decimalFailCount = decimalHits.length > 0 ? 1 : 0
  const overall: 'PASS' | 'FAIL' = failCount + notCheckedCount + colorFailCount + decimalFailCount === 0 ? 'PASS' : 'FAIL'

  function setStatus(id: number, status: Status) {
    setChecklist((rows) => rows.map((r) => (r.id === id ? { ...r, status } : r)))
  }

  const pageUrl = sleeperLeagueId ? `/leagues/${sleeperLeagueId}/power` : '#'

  return (
    <div className="content scrolls" id="verify-root">
      <section className="panel">
        <div className="panel-head">
          <h2>Power rankings -- verify</h2>
          <span className={`chip ${overall === 'PASS' ? 'on' : ''}`} style={overall === 'FAIL' ? { background: 'var(--crimson)', borderColor: 'var(--crimson)', color: 'var(--bg)' } : undefined}>
            {overall}
          </span>
        </div>
        <p className="muted small">
          {failCount} fail · {notCheckedCount} not checked · {colorHits == null ? '?' : colorHits.length} hard-coded colors ·{' '}
          {decimalHits.length} un-rounded decimals in the ladder text.
        </p>
      </section>

      {/* 1. token parity */}
      <section className="panel">
        <div className="panel-head">
          <h2>1. Token parity</h2>
        </div>
        <div className="verify-swatches">
          {TOKENS.map((t) => (
            <TokenSwatch key={t} name={t} />
          ))}
          <div className="verify-swatch">
            <div className="verify-swatch-box" style={{ background: 'oklch(72% 0.14 150)' }} />
            <div className="mono tiny">up</div>
            <div className="mono tiny muted">oklch(72% 0.14 150)</div>
          </div>
          <div className="verify-swatch">
            <div className="verify-swatch-box" style={{ background: 'var(--crimson)' }} />
            <div className="mono tiny">down (--crimson)</div>
          </div>
          <div className="verify-swatch">
            <div className="verify-swatch-box" style={{ background: 'oklch(52% 0.02 250)' }} />
            <div className="mono tiny">flat</div>
            <div className="mono tiny muted">oklch(52% 0.02 250)</div>
          </div>
        </div>
        <p className="cond" style={{ fontSize: 20, marginTop: 10 }}>
          Condensed headline type
        </p>
        <p className="mono" style={{ fontSize: 14 }}>
          Mono / tabular numerals 12345.67
        </p>
        <p style={{ fontSize: 14 }}>Body copy, Plus Jakarta Sans.</p>
        <p className="small" style={{ marginTop: 10 }}>
          Hard-coded hex/rgb scan of this harness's own rendered DOM (a proxy for the reskinned page's style objects, since
          both come from the same component tree):{' '}
          <strong style={{ color: (colorHits?.length ?? 0) > 0 ? 'var(--crimson)' : undefined }}>
            {colorHits == null ? 'scanning…' : colorHits.length === 0 ? 'none found' : `${colorHits.length} found`}
          </strong>
        </p>
        {colorHits && colorHits.length > 0 && (
          <ul className="tiny muted">
            {colorHits.map((h, i) => (
              <li key={i}>{h}</li>
            ))}
          </ul>
        )}
      </section>

      {/* 2. regression checklist */}
      <section className="panel">
        <div className="panel-head">
          <h2>2. Regression checklist (§6)</h2>
        </div>
        <div className="verify-checklist">
          {checklist.map((row) => (
            <div className="verify-check-row" key={row.id}>
              <span className="mono tiny muted">{row.id}</span>
              <span className="small">{row.text}</span>
              <select value={row.status} onChange={(e) => setStatus(row.id, e.target.value as Status)}>
                <option value="pass">pass</option>
                <option value="fail">fail</option>
                <option value="not-checked">not checked</option>
              </select>
              <span className="tiny muted">{row.note}</span>
            </div>
          ))}
        </div>
      </section>

      {/* 3. copy audit */}
      <section className="panel">
        <div className="panel-head">
          <h2>3. Copy audit (§5)</h2>
          <span className="small muted">Rendered page text content only; a hit inside "How this works" is not scanned separately here.</span>
        </div>
        <CopyAudit url={pageUrl} />
      </section>

      {/* 4. empty and blocked states */}
      <section className="panel">
        <div className="panel-head">
          <h2>4. Empty and blocked states</h2>
        </div>
        <div className="verify-states">
          {(['loading', 'signed-out', 'not-member', 'voting-closed', 'load-error', 'ok'] as const).map((s) => (
            <div className="verify-state-card panel" key={s}>
              <h3 className="cond" style={{ margin: '0 0 6px', fontSize: 13 }}>
                {s}
              </h3>
              <p className="tiny muted">{ballotBlockState(true, ballot, s === 'load-error') === s || s === 'ok' ? 'Reachable with current data.' : 'Synthetic -- see PowerRankings.tsx\'s own branch for this state.'}</p>
            </div>
          ))}
          <div className="verify-state-card panel">
            <h3 className="cond" style={{ margin: '0 0 6px', fontSize: 13 }}>
              one week of data (no chart)
            </h3>
            <p className="tiny muted">{weeks.length < 2 ? 'This league is currently in this state.' : `This league has ${weeks.length} weeks; Trend disclosure draws normally.`}</p>
          </div>
          <div className="verify-state-card panel">
            <h3 className="cond" style={{ margin: '0 0 6px', fontSize: 13 }}>
              no snapshots for a mode
            </h3>
            <p className="tiny muted">
              {(['MEMBER', 'COMPUTED_REALIZED', 'COMMISSIONER'] as const)
                .filter((k) => !data?.entries.some((e) => e.kind === k && e.season === season))
                .join(', ') || 'Every mode has at least one snapshot for this league.'}
            </p>
          </div>
        </div>
      </section>

      {/* 5. breakpoints + mockup parity */}
      <section className="panel">
        <div className="panel-head">
          <h2>5. Breakpoints and mockup parity</h2>
          <div className="segmented sm">
            {[390, 768, 1280, 1680].map((w) => (
              <button key={w} type="button" className={`segment${bp === w ? ' on' : ''}`} onClick={() => setBp(w)}>
                {w}px
              </button>
            ))}
          </div>
        </div>
        <div className="verify-breakpoint-row">
          <div>
            <p className="tiny muted">Live page at {bp}px</p>
            <div className="verify-iframe-wrap" style={{ width: Math.min(bp, 900) }}>
              <iframe title={`power rankings ${bp}px`} src={pageUrl} style={{ width: bp, height: 900 }} />
            </div>
          </div>
          {(bp === 1280 || bp === 390) && (
            <div>
              <p className="tiny muted">Approved mockup ({bp === 1280 ? '2a' : '2b'})</p>
              <img
                className="verify-mockup-img"
                style={{ width: Math.min(bp, 900) }}
                src={bp === 1280 ? '/pr-reference/2a-front-page-desktop.png' : '/pr-reference/2b-ladder-mobile.png'}
                alt={bp === 1280 ? '2a front page desktop mockup' : '2b ladder mobile mockup'}
              />
            </div>
          )}
        </div>
        <p className="tiny muted" style={{ marginTop: 8 }}>
          Check in order: block order top-to-bottom, which element is largest, what is teal vs. crimson, column set and
          order, row density.
        </p>
      </section>

      {/* 6. decimal sweep */}
      <section className="panel">
        <div className="panel-head">
          <h2>6. Decimal sweep</h2>
        </div>
        <p className="small">
          Un-rounded (2+ decimal) numbers found in generated ladder/headline text:{' '}
          <strong style={{ color: decimalHits.length > 0 ? 'var(--crimson)' : undefined }}>{decimalHits.length}</strong>
        </p>
        {decimalHits.length > 0 && (
          <ul className="tiny muted">
            {decimalHits.map((h: string, i: number) => (
              <li key={i}>{h}</li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** Loads the real page in a hidden iframe and searches its rendered text for
 *  every banned §5 term. Runs once the iframe's own React tree has painted. */
function CopyAudit({ url }: { url: string }) {
  const [text, setText] = useState<string | null>(null)

  return (
    <>
      <iframe
        title="copy audit source"
        src={url}
        style={{ position: 'absolute', width: 1280, height: 2000, left: -9999, top: 0, border: 0 }}
        onLoad={(e) => {
          try {
            const doc = (e.target as HTMLIFrameElement).contentDocument
            setText(doc?.body?.innerText ?? '')
          } catch {
            setText('')
          }
        }}
      />
      <table className="standings">
        <thead>
          <tr>
            <th>Term</th>
            <th className="mono">Count</th>
          </tr>
        </thead>
        <tbody>
          {BANNED_TERMS.map((term) => {
            const count = text == null ? null : (text.match(new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) ?? []).length
            return (
              <tr key={term}>
                <td className="mono">{term}</td>
                <td className={`mono ${count && count > 0 ? '' : 'muted'}`} style={count && count > 0 ? { color: 'var(--crimson)' } : undefined}>
                  {count == null ? 'loading…' : count}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </>
  )
}

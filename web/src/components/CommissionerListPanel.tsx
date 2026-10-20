import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import Avatar from './Avatar'
import CommissionerHonourNote from './CommissionerHonourNote'
import PlayerFace from './PlayerFace'
import PlayerLink from './PlayerLink'
import {
  deleteConductEntry,
  fetchConductList,
  saveConductEntry,
  updateConductEntry,
  type ConductCandidate,
  type ConductEntry,
  type ConductList,
  type Sport,
} from '../api'
import { useFailure } from '../useFailure'

/**
 * specs/040-superlatives-commissioner-ux US2: the commissioner's list, opened in place from the
 * UNETHICAL card (it used to be a section at the bottom of the page).
 *
 * <p><b>Addressed by the URL's own league id (`leagueSleeperId`), and says which season that is.</b>
 * This deliberately REVERSES a 2026-09-23 code-review fix that addressed the list by the id the award
 * cards resolved to ("never the URL's own id"). That fix existed because, before a new season's first
 * scored week, the cards walk back to last season while the list is per league row, so a save from the
 * URL id edited a season nothing on screen showed. The fix removed the symptom by locking the
 * commissioner out of the new season, which is the season they need to prepare: a suspension announced
 * before week 1 could not be entered until week 1 had been scored (2026-10-20, Allan: the NBA season had
 * just started and the list was unusable). The real defect was that the panel never said which season it
 * edited, so it now does: the header names `list.season`, and when that differs from `cardsSeason` a note
 * says the awards above still show the older season and that this list starts counting once the new
 * season has a scored week. `canEdit` and `commissionerKnown` come from the conduct-list response alone
 * (spec 040 plan-review F5); the award payload's `commissionerListAvailable` is not consulted.
 *
 * <p>The card around this panel is a clickable `<article>`, so the root stops BOTH click and keydown from
 * bubbling to it (plan-review F3).
 *
 * <p>The panel fetches on mount even while closed, so the card's "Commissioner's list · N" button can show
 * the count; it renders nothing until `open`.
 */

/** Case- and diacritic-insensitive: "Jokic" finds "Nikola Jokić". */
export function normalizeName(s: string): string {
  return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()
}

/** "wk 2" / "wk 2–3" / "from wk 2" -- the span an entry applies to. */
export function weekRange(from: number, through: number | null): string {
  if (through == null) return `from wk ${from}`
  if (through === from) return `wk ${from}`
  return `wk ${from}–${through}`
}

/** [2,3,4,7] -> "2–4, 7". */
export function formatWeeks(weeks: number[]): string {
  const out: string[] = []
  for (let i = 0; i < weeks.length; ) {
    let j = i
    while (j + 1 < weeks.length && weeks[j + 1] === weeks[j] + 1) j++
    out.push(j > i ? `${weeks[i]}–${weeks[j]}` : String(weeks[i]))
    i = j + 1
  }
  return out.join(', ')
}

const MAX_OPTIONS = 30

type Props = {
  /** The URL's league id, never the resolved award season's (see the class comment). */
  leagueSleeperId: string
  /** The season the award cards show; compared with the list's own season. */
  cardsSeason: number
  sport: Sport
  open: boolean
  /** The id the card's button points aria-controls at. */
  panelId: string
  /** Called with the entry count once known, so the card's button can show it. */
  onCount?: (n: number) => void
  /** After a successful write, only when this list is the season the cards show (otherwise the cards can't have changed). */
  onChanged: () => void | Promise<void>
}

export default function CommissionerListPanel({
  leagueSleeperId,
  cardsSeason,
  sport,
  open,
  panelId,
  onCount,
  onChanged,
}: Props) {
  const [list, setList] = useState<ConductList | null>(null)
  const { error, setError, fail } = useFailure()
  const [adding, setAdding] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const headingId = useId()
  const onCountRef = useRef(onCount)
  onCountRef.current = onCount

  function accept(d: ConductList) {
    setList(d)
    onCountRef.current?.(d.entries.length)
  }

  // The league this panel is currently showing; async completions compare against it so a response (or a
  // write) that resolves after navigation can't land on the next league's state (code-review L1).
  const leagueRef = useRef(leagueSleeperId)
  leagueRef.current = leagueSleeperId
  // True when a write succeeded but re-reading the list failed (code-review L2): the write is NOT an error.
  const [refreshFailed, setRefreshFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    // A different league: drop everything that belonged to the previous one before the new list arrives.
    setList(null)
    setAdding(false)
    setEditingId(null)
    setConfirmingId(null)
    setSaving(false)
    setRefreshFailed(false)
    setError(null)
    fetchConductList(leagueSleeperId)
      .then((d) => {
        if (!cancelled) accept(d)
      })
      .catch((e) => {
        if (!cancelled) fail(e)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [leagueSleeperId])

  async function run(write: () => Promise<unknown>, writtenSeason: number | undefined) {
    const id = leagueSleeperId
    const stale = () => leagueRef.current !== id
    setSaving(true)
    setError(null)
    setRefreshFailed(false)
    try {
      await write()
    } catch (e) {
      if (!stale()) {
        fail(e)
        setSaving(false)
      }
      return
    }
    if (stale()) return
    // The write succeeded: close the form whatever happens to the refresh below.
    setAdding(false)
    setEditingId(null)
    setConfirmingId(null)
    try {
      const d = await fetchConductList(id)
      if (!stale()) accept(d)
    } catch {
      if (!stale()) setRefreshFailed(true)
    }
    if (stale()) return
    try {
      if (writtenSeason === cardsSeason) await onChanged()
    } catch (e) {
      if (!stale()) fail(e)
    }
    if (!stale()) setSaving(false)
  }

  if (!open) return null

  const season = list?.season
  const canEdit = Boolean(list?.canEdit)
  const listed = new Map<string, ConductEntry>((list?.entries ?? []).map((e) => [e.playerId, e]))

  return (
    <section
      id={panelId}
      className="sl-cl"
      aria-labelledby={headingId}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <h5 id={headingId} className="sl-cl-title">
        Commissioner&apos;s list{season != null ? ` · ${season}` : ''}
      </h5>
      <p className="muted small sl-cl-sub">
        This list is for this season only — a new season starts with an empty one.
      </p>

      {season != null && season !== cardsSeason && (
        <p className="sl-cl-seasonnote small">
          The awards above show {cardsSeason}. This {season} list starts counting once {season} has a scored week.
        </p>
      )}

      {error && <p className="error small">{error}</p>}
      {refreshFailed && (
        <p className="error small" role="alert">
          Saved, but the list couldn&apos;t refresh — reload the page.
        </p>
      )}

      {list && !list.commissionerKnown && (
        <p className="muted small">No commissioner is known for this league, so the list can&apos;t be edited.</p>
      )}
      {canEdit && <CommissionerHonourNote />}

      {list && list.entries.length === 0 ? (
        <p className="muted small">Nobody&apos;s on the list.</p>
      ) : (
        <ul className="sl-cl-list">
          {list?.entries.map((e) => (
            <li key={e.id} className="sl-cl-entry">
              {editingId === e.id ? (
                <EntryForm
                  mode="edit"
                  entry={e}
                  saving={saving}
                  onCancel={() => setEditingId(null)}
                  onSubmit={(v) =>
                    run(
                      () =>
                        updateConductEntry(leagueSleeperId, e.id, {
                          reason: v.reason,
                          appliesFromWeek: v.from,
                          appliesThroughWeek: v.through,
                        }),
                      list.season,
                    )
                  }
                />
              ) : (
                <EntryRow
                  entry={e}
                  sport={sport}
                  leagueSleeperId={leagueSleeperId}
                  canEdit={canEdit}
                  confirming={confirmingId === e.id}
                  onEdit={() => {
                    setAdding(false)
                    setConfirmingId(null)
                    setEditingId(e.id)
                  }}
                  onAskRemove={() => setConfirmingId(e.id)}
                  onCancelRemove={() => setConfirmingId(null)}
                  onRemove={() => run(() => deleteConductEntry(leagueSleeperId, e.id), list.season)}
                />
              )}
            </li>
          ))}
        </ul>
      )}

      {canEdit && list && (
        <div className="sl-cl-add">
          {adding ? (
            <EntryForm
              mode="add"
              candidates={list.candidates}
              sport={sport}
              listed={listed}
              saving={saving}
              onCancel={() => setAdding(false)}
              onJumpToEntry={(id) => {
                setAdding(false)
                setEditingId(id)
              }}
              onSubmit={(v) =>
                run(
                  () =>
                    saveConductEntry(leagueSleeperId, {
                      playerId: v.playerId as string,
                      reason: v.reason,
                      appliesFromWeek: v.from,
                      appliesThroughWeek: v.through,
                    }),
                  list.season,
                )
              }
            />
          ) : (
            <button
              type="button"
              className="sl-cl-btn sl-cl-btn-primary"
              onClick={() => {
                setEditingId(null)
                setAdding(true)
              }}
            >
              Add to the list
            </button>
          )}
        </div>
      )}
    </section>
  )
}

/** The card's trigger. Rendered regardless of whether the award has holders, is available, or is openable. */
export function CommissionerListButton({
  count,
  open,
  controlsId,
  onToggle,
}: {
  count: number | null
  open: boolean
  controlsId: string
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      className="sl-cl-trigger"
      aria-expanded={open}
      aria-controls={open ? controlsId : undefined}
      onClick={(e) => {
        e.stopPropagation()
        onToggle()
      }}
      onKeyDown={(e) => e.stopPropagation()}
    >
      {count == null ? "Commissioner's list" : `Commissioner's list · ${count}`}
    </button>
  )
}

function EntryRow({
  entry: e,
  sport,
  leagueSleeperId,
  canEdit,
  confirming,
  onEdit,
  onAskRemove,
  onCancelRemove,
  onRemove,
}: {
  entry: ConductEntry
  sport: Sport
  leagueSleeperId: string
  canEdit: boolean
  confirming: boolean
  onEdit: () => void
  onAskRemove: () => void
  onCancelRemove: () => void
  onRemove: () => void
}) {
  const charges = e.charges ?? []
  const playerWeeks = e.playerWeeks ?? 0
  const flagged = e.alsoFlaggedBySleeperWeeks ?? []
  return (
    <div className="sl-cl-row">
      <div className="sl-cl-who">
        <PlayerFace
          sport={sport}
          sleeperId={e.playerId}
          team={e.proTeam}
          position={e.position ?? ''}
          name={e.playerName}
          size={32}
        />
        <div className="sl-cl-name">
          <PlayerLink sleeperLeagueId={leagueSleeperId} sleeperPlayerId={e.playerId} sport={sport}>
            {e.playerName}
          </PlayerLink>
          {(e.position || e.proTeam) && (
            <span className="muted small">{[e.position, e.proTeam].filter(Boolean).join(' · ')}</span>
          )}
        </div>
        <span className="sl-cl-pills">
          <span className="sl-cl-pill">{weekRange(e.appliesFromWeek, e.appliesThroughWeek)}</span>
          <span className="sl-cl-pill sl-cl-pill-count">
            {playerWeeks} {playerWeeks === 1 ? 'player-week' : 'player-weeks'}
          </span>
        </span>
      </div>

      <p className="sl-cl-reason">{e.reason}</p>

      <div className="sl-cl-charges">
        {charges.length > 0 ? (
          <>
            <span className="muted small">charged to</span>
            {charges.map((c) => (
              <span className="sl-cl-chip" key={c.team.rosterId}>
                <Avatar
                  avatarId={c.team.avatarId}
                  seed={String(c.team.managerId ?? c.team.rosterId)}
                  label={c.team.teamName}
                  className="sl-avatar"
                />
                <span className="sl-cl-chip-team">{c.team.teamName}</span>
                <span className="muted small">wk {formatWeeks(c.weeks)}</span>
              </span>
            ))}
          </>
        ) : (
          <span className="muted small">not rostered in these weeks yet</span>
        )}
      </div>
      {flagged.length > 0 && (
        <p className="muted small sl-cl-flagged">
          also flagged by Sleeper in weeks {formatWeeks(flagged)} — those weeks count once
        </p>
      )}

      <div className="sl-cl-foot">
        <span className="muted small">added by {e.addedBy ?? 'the commissioner'}</span>
        {canEdit &&
          (confirming ? (
            <span className="sl-cl-confirm" role="group" aria-label={`Remove ${e.playerName}?`}>
              <span className="small">Remove?</span>
              <button type="button" className="sl-cl-btn sl-cl-btn-danger" onClick={onRemove}>
                Yes
              </button>
              <button type="button" className="sl-cl-btn" onClick={onCancelRemove}>
                No
              </button>
            </span>
          ) : (
            <span className="sl-cl-actions">
              <button type="button" className="sl-cl-btn" aria-label={`Edit ${e.playerName}`} onClick={onEdit}>
                Edit
              </button>
              <button
                type="button"
                className="sl-cl-btn"
                aria-label={`Remove ${e.playerName}`}
                onClick={onAskRemove}
              >
                Remove
              </button>
            </span>
          ))}
      </div>
    </div>
  )
}

type FormValues = { playerId: string | null; reason: string; from: number; through: number | null }

function EntryForm(
  props:
    | {
        mode: 'edit'
        entry: ConductEntry
        saving: boolean
        onCancel: () => void
        onSubmit: (v: FormValues) => void
      }
    | {
        mode: 'add'
        candidates: ConductCandidate[]
        sport: Sport
        listed: Map<string, ConductEntry>
        saving: boolean
        onCancel: () => void
        onJumpToEntry: (id: number) => void
        onSubmit: (v: FormValues) => void
      },
) {
  const editing = props.mode === 'edit' ? props.entry : null
  const [picked, setPicked] = useState<ConductCandidate | null>(null)
  const [reason, setReason] = useState(editing?.reason ?? '')
  // Held as text so clearing the box and typing a new number doesn't snap back to 1 mid-edit.
  const [fromText, setFromText] = useState(String(editing?.appliesFromWeek ?? 1))
  const [through, setThrough] = useState(editing?.appliesThroughWeek != null ? String(editing.appliesThroughWeek) : '')
  const reasonId = useId()
  const fromId = useId()
  const throughId = useId()

  const from = Number(fromText)
  const fromBad = fromText.trim() === '' || !Number.isInteger(from) || from < 1
  const throughNum = through.trim() === '' ? null : Number(through)
  const throughBad = !fromBad && throughNum != null && (!Number.isInteger(throughNum) || throughNum < from)
  const hasPlayer = props.mode === 'edit' || picked != null
  const canSave = hasPlayer && reason.trim() !== '' && !fromBad && !throughBad && !props.saving

  return (
    <form
      className="sl-cl-form"
      onSubmit={(ev) => {
        ev.preventDefault()
        if (!canSave) return
        props.onSubmit({
          playerId: picked?.playerId ?? null,
          reason: reason.trim(),
          from,
          through: throughNum,
        })
      }}
    >
      {props.mode === 'add' ? (
        picked ? (
          <div className="sl-cl-picked">
            <PlayerFace
              sport={props.sport}
              sleeperId={picked.playerId}
              team={picked.proTeam}
              position={picked.position ?? ''}
              name={picked.playerName}
              size={28}
            />
            <span>
              <strong>{picked.playerName}</strong>{' '}
              <span className="muted small">{[picked.position, picked.proTeam].filter(Boolean).join(' · ')}</span>
            </span>
            <button type="button" className="sl-cl-btn" onClick={() => setPicked(null)}>
              Change player
            </button>
          </div>
        ) : (
          <PlayerSearch
            candidates={props.candidates}
            sport={props.sport}
            listed={props.listed}
            onPick={setPicked}
            onJumpToEntry={props.onJumpToEntry}
          />
        )
      ) : (
        <p className="sl-cl-editing">
          Editing <strong>{props.entry.playerName}</strong>
        </p>
      )}

      <div className="sl-cl-field">
        <label htmlFor={reasonId}>Reason</label>
        <input id={reasonId} value={reason} maxLength={140} onChange={(e) => setReason(e.target.value)} />
      </div>
      <div className="sl-cl-weeks">
        <div className="sl-cl-field">
          <label htmlFor={fromId}>Applies from week</label>
          <input
            id={fromId}
            type="number"
            min={1}
            value={fromText}
            aria-invalid={fromBad}
            onChange={(e) => setFromText(e.target.value)}
          />
        </div>
        <div className="sl-cl-field">
          <label htmlFor={throughId}>Through week (optional)</label>
          <input
            id={throughId}
            type="number"
            min={fromBad ? 1 : from}
            placeholder="rest of season"
            value={through}
            aria-invalid={throughBad}
            onChange={(e) => setThrough(e.target.value)}
          />
        </div>
      </div>
      {throughBad && <p className="error small">The through week can&apos;t be before the from week.</p>}

      <div className="sl-cl-formbtns">
        <button type="submit" className="sl-cl-btn sl-cl-btn-primary" disabled={!canSave}>
          Save
        </button>
        <button type="button" className="sl-cl-btn" onClick={props.onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

/**
 * Name search over the league's known players -- a combobox with a listbox, operable from the keyboard
 * (Arrow keys move, Enter picks, Escape closes). No raw player-id input: with no candidates the only honest
 * thing to say is that rosters haven't loaded (plan-review F4b).
 */
function PlayerSearch({
  candidates,
  sport,
  listed,
  onPick,
  onJumpToEntry,
}: {
  candidates: ConductCandidate[]
  sport: Sport
  listed: Map<string, ConductEntry>
  onPick: (c: ConductCandidate) => void
  onJumpToEntry: (entryId: number) => void
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const inputId = useId()
  const listId = useId()

  const indexed = useMemo(() => candidates.map((c) => ({ c, key: normalizeName(c.playerName) })), [candidates])
  const q = normalizeName(query)
  const matched = useMemo(() => indexed.filter((x) => q === '' || x.key.includes(q)).map((x) => x.c), [indexed, q])
  const shown = matched.slice(0, MAX_OPTIONS)

  if (candidates.length === 0) {
    return (
      <p className="muted small sl-cl-empty">Rosters haven&apos;t loaded yet — refresh the league.</p>
    )
  }

  function choose(c: ConductCandidate) {
    const existing = listed.get(c.playerId)
    setOpen(false)
    setQuery('')
    if (existing) onJumpToEntry(existing.id)
    else onPick(c)
  }

  function onKeyDown(ev: KeyboardEvent<HTMLInputElement>) {
    if (ev.key === 'ArrowDown') {
      ev.preventDefault()
      setOpen(true)
      setActive((a) => (shown.length === 0 ? 0 : (a + 1) % shown.length))
    } else if (ev.key === 'ArrowUp') {
      ev.preventDefault()
      setOpen(true)
      setActive((a) => (shown.length === 0 ? 0 : (a - 1 + shown.length) % shown.length))
    } else if (ev.key === 'Enter') {
      if (open && shown[active]) {
        ev.preventDefault() // don't submit the form on the pick
        choose(shown[active])
      }
    } else if (ev.key === 'Escape') {
      if (open) {
        ev.preventDefault()
        setOpen(false)
      }
    }
  }

  const optionId = (i: number) => `${listId}-o${i}`

  return (
    <div className="sl-cl-field sl-cl-search">
      <label htmlFor={inputId}>Player</label>
      <input
        id={inputId}
        role="combobox"
        autoComplete="off"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && shown[active] ? optionId(active) : undefined}
        placeholder="Search by name"
        value={query}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onChange={(e) => {
          setQuery(e.target.value)
          setActive(0)
          setOpen(true)
        }}
        onKeyDown={onKeyDown}
      />
      {open && (
        <ul id={listId} role="listbox" aria-label="Matching players" className="sl-cl-options">
          {shown.length === 0 && <li className="muted small sl-cl-noopt">No player matches “{query}”.</li>}
          {shown.map((c, i) => {
            const on = listed.get(c.playerId)
            const where = c.fantasyTeam ? c.fantasyTeam.teamName : 'not on a roster'
            const label = [
              c.playerName,
              c.position,
              c.proTeam,
              where,
              on ? 'on the list — edit' : null,
            ]
              .filter(Boolean)
              .join(', ')
            return (
              <li
                key={c.playerId}
                id={optionId(i)}
                role="option"
                aria-selected={i === active}
                aria-label={label}
                className={`sl-cl-option${i === active ? ' sl-cl-option-active' : ''}`}
                // mousedown, not click: the input's blur would close the list before a click lands.
                onMouseDown={(ev) => {
                  ev.preventDefault()
                  choose(c)
                }}
                onMouseEnter={() => setActive(i)}
              >
                <PlayerFace
                  sport={sport}
                  sleeperId={c.playerId}
                  team={c.proTeam}
                  position={c.position ?? ''}
                  name={c.playerName}
                  size={24}
                />
                <span className="sl-cl-opt-name">{c.playerName}</span>
                <span className="muted small">{[c.position, c.proTeam].filter(Boolean).join(' · ')}</span>
                <span className="sl-cl-opt-team">
                  {c.fantasyTeam ? (
                    <>
                      <Avatar
                        avatarId={c.fantasyTeam.avatarId}
                        seed={String(c.fantasyTeam.managerId ?? c.fantasyTeam.rosterId)}
                        label={c.fantasyTeam.teamName}
                        className="sl-avatar"
                      />
                      <span className="small">{c.fantasyTeam.teamName}</span>
                    </>
                  ) : (
                    <span className="muted small">not on a roster</span>
                  )}
                </span>
                {on && <span className="sl-cl-pill sl-cl-pill-listed">on the list — edit</span>}
              </li>
            )
          })}
          {matched.length > shown.length && (
            <li className="muted small sl-cl-noopt">{matched.length - shown.length} more — keep typing to narrow.</li>
          )}
        </ul>
      )}
    </div>
  )
}

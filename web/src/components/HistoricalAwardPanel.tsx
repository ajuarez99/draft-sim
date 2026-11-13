import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  getHistoricalGameAward,
  getHistoricalProfile,
  markHistoricalSeen,
  type HistoricalBadge,
  type HistoricalGameAward,
  type HistoricalProfile,
} from '../api'
import AchievementToasts, { type AchievementToast } from './AchievementToasts'

const SAVED_NOTE =
  "Counted from this game's saved result. Pick-by-pick badges need a game finished after achievements were added."

/** What a badge's `detail` says, read defensively: the backend owns its shape. */
export function detailText(detail: Record<string, unknown> | null): string | null {
  if (!detail) return null
  if (typeof detail.text === 'string' && detail.text) return detail.text
  const name = typeof detail.playerName === 'string' ? detail.playerName : null
  const pick = typeof detail.pickNo === 'number' ? detail.pickNo : null
  if (name && pick != null) return `${name}, pick ${pick}`
  return name
}

function toastsFor(award: HistoricalGameAward, profile: HistoricalProfile | null): AchievementToast[] {
  if (!award.settled) return []
  const out: AchievementToast[] = []
  // A first-time pick-level badge and its moment are one event with one sentence: the badge toast wins.
  const badged = new Set<string>()
  for (const b of award.badges) {
    if (b.unlocked && !b.seen) {
      const pickNo = b.detail?.pickNo
      if (typeof pickNo === 'number') badged.add(`${b.code}:${pickNo}`)
      out.push({ key: `badge:${b.code}`, kind: 'badge', title: b.name, text: detailText(b.detail) ?? b.rule, xp: b.xp })
    }
  }
  if (profile && profile.level > profile.seenLevel) {
    out.push({ key: `level:${profile.level}`, kind: 'level', title: `Level ${profile.level}: ${profile.levelName}` })
  }
  if (!award.momentsSeen) {
    award.moments.forEach((m, i) => {
      if (badged.has(`${m.code}:${m.pickNo}`)) return
      out.push({ key: `moment:${m.code}:${m.pickNo}:${i}`, kind: 'moment', title: m.name, text: m.text })
    })
  }
  return out
}

/**
 * Spec 064: "What this game earned". Mounted only in the results branch of the room, so nothing here is requested
 * while the paced reveal is still running. A failed request leaves one muted line and nothing else changes.
 */
export default function HistoricalAwardPanel({ gameId }: { gameId: number }) {
  const [award, setAward] = useState<HistoricalGameAward | null>(null)
  const [profile, setProfile] = useState<HistoricalProfile | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let current = true
    getHistoricalGameAward(gameId)
      .then((a) => current && setAward(a))
      .catch(() => current && setFailed(true))
    // The profile only feeds the level-up toast; losing it loses that one toast.
    getHistoricalProfile()
      .then((p) => current && setProfile(p))
      .catch(() => {})
    return () => {
      current = false
    }
  }, [gameId])

  const toasts = useMemo(() => (award ? toastsFor(award, profile) : []), [award, profile])

  // Seen is posted as each toast is shown. Moments are marked per game, once.
  const momentsPosted = useRef(false)
  const onShown = useMemo(() => {
    return (t: AchievementToast) => {
      let body: { codes: string[]; level: number | null; momentsGameId: number | null } | null = null
      if (t.kind === 'badge') body = { codes: [t.key.slice('badge:'.length)], level: null, momentsGameId: null }
      else if (t.kind === 'level') body = { codes: [], level: Number(t.key.slice('level:'.length)), momentsGameId: null }
      else if (!momentsPosted.current) {
        momentsPosted.current = true
        body = { codes: [], level: null, momentsGameId: gameId }
      }
      if (body) void markHistoricalSeen(body).catch(() => {})
    }
  }, [gameId])

  // When every moment was folded into a badge toast no moment toast will ever post the seen mark: post it here.
  useEffect(() => {
    if (!award || !award.settled || award.momentsSeen || award.moments.length === 0) return
    if (toasts.some((t) => t.kind === 'moment') || momentsPosted.current) return
    momentsPosted.current = true
    void markHistoricalSeen({ codes: [], level: null, momentsGameId: gameId }).catch(() => {})
  }, [award, toasts, gameId])

  // The live region is in the tree, empty, from the first render in every branch, so a screen reader is already
  // watching it when the first toast arrives (a region inserted together with its content is not announced).
  const region = <AchievementToasts toasts={toasts} onShown={onShown} />

  if (failed) {
    return (
      <>
        {region}
        <section className="panel hist-award" aria-label="What this game earned">
          <p className="muted small">This game&apos;s awards could not be loaded.</p>
        </section>
      </>
    )
  }
  if (!award) return region

  if (!award.settled) {
    return (
      <>
        {region}
        <section className="panel hist-award" aria-label="What this game earned">
          <h2>What this game earned</h2>
          <p className="muted small">This game&apos;s awards could not be worked out.</p>
        </section>
      </>
    )
  }

  return (
    <>
      {region}
      <section className="panel hist-award" aria-label="What this game earned">
        <h2>What this game earned</h2>
        <ul className="hist-award-lines row-list">
          {award.lines.map((l, i) => (
            <li key={i}>
              <span>{l.label}</span>
              <span className="mono">+{l.xp} XP</span>
            </li>
          ))}
          {award.badges.map((b) => (
            <li key={b.code}>
              <span>Badge: {b.name}</span>
              <span className="mono">+{b.xp} XP</span>
            </li>
          ))}
          <li className="hist-award-total">
            <strong>Total</strong>
            <strong className="mono">+{award.total} XP</strong>
          </li>
        </ul>

        {award.badges.length > 0 && (
          <>
            <h3 className="hist-award-sub">Badges unlocked</h3>
            <ul className="ach-grid">
              {award.badges.map((b) => (
                <BadgeTile key={b.code} badge={b} />
              ))}
            </ul>
          </>
        )}

        {award.moments.length > 0 && (
          <>
            <h3 className="hist-award-sub">Moments</h3>
            <ul className="hist-award-moments">
              {award.moments.map((m, i) => (
                <li key={`${m.code}-${m.pickNo}-${i}`}>
                  <span className="ach-pill ach-pill-moment">{m.name}</span> <span>{m.text}</span>
                </li>
              ))}
            </ul>
          </>
        )}

        {award.fromSavedResult && <p className="muted small">{SAVED_NOTE}</p>}
      </section>
    </>
  )
}

export function BadgeTile({ badge, footer }: { badge: HistoricalBadge; footer?: ReactNode }) {
  const detail = detailText(badge.detail)
  return (
    <li className={`ach-badge ${badge.unlocked ? 'earned' : 'locked'}`}>
      <div className="ach-badge-head">
        <strong>{badge.name}</strong>
        <span className={`ach-pill ${badge.unlocked ? 'ach-pill-earned' : 'ach-pill-locked'}`}>
          {badge.unlocked ? 'Earned' : 'Locked'}
        </span>
      </div>
      <p className="muted small">{badge.unlocked ? (detail ?? badge.rule) : badge.rule}</p>
      {!badge.unlocked && badge.source === 'NEW_GAMES' && (
        <p className="muted small">Only from games finished after achievements were added.</p>
      )}
      <p className="small ach-badge-foot">
        <span className="mono">+{badge.xp} XP</span>
        {footer}
      </p>
    </li>
  )
}

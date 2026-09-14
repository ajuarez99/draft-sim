import { useState } from 'react'
import { hueFor } from '../hue'

/**
 * Sleeper's own avatar id (e.g. "e1d4ebf9ea0760f248119d4ec2ac5a63") resolves
 * to an image on their CDN -- the same id SleeperUserController and every
 * manager-carrying API response now hand back. Thumb size everywhere: this is
 * always rendered at .avatar's 24px-ish footprint, never full-size.
 */
const sleeperAvatarSrc = (avatarId: string) => `https://sleepercdn.com/avatars/thumbs/${avatarId}`

type Props = {
  /** Sleeper's avatar id, or null/undefined when Sleeper has none on file. */
  avatarId?: string | null
  /** hueFor() input -- the manager id (or other stable identity) that makes the
   *  same person the same color everywhere on the page, matching the seed the
   *  many call sites this replaces already agreed on. */
  seed: string
  /** Display name; its first character is the fallback initial. Defaults to `seed`. */
  label?: string | null
  /** This avatar's person is the viewer. Crimson fill with no photo; a crimson
   *  ring with one -- the same "crimson-when-it's-you" rule the board's column
   *  headers and on-clock strip already followed before there was a photo to
   *  show. */
  isMe?: boolean
  /** Extra classes for size variants (on-clock-avatar, placement-avatar, ...). */
  className?: string
}

/** Same color-initials fallback every seat/board/history page used to draw by
 * hand, now with Sleeper's real photo shown in front of it when there is one
 * -- see claude/user-identity-and-onboarding.md's identity-chip comment in
 * App.tsx, superseded by the "everywhere, replacing initials" call. A broken
 * image (avatar id stale, CDN hiccup) falls back to the initial rather than a
 * broken-image icon. */
export default function Avatar({ avatarId, seed, label, isMe, className }: Props) {
  const [broken, setBroken] = useState(false)
  const showPhoto = !!avatarId && !broken
  const hue = hueFor(seed)
  const style = showPhoto
    ? undefined
    : isMe
      ? { background: 'var(--crimson)', color: 'var(--bg)' }
      : { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
  const initial = (label ?? seed ?? '?').trim().charAt(0).toUpperCase() || '?'

  return (
    <span
      className={`avatar${isMe ? ' avatar-me' : ''}${className ? ` ${className}` : ''}`}
      style={style}
      aria-hidden="true"
    >
      {showPhoto ? <img src={sleeperAvatarSrc(avatarId!)} alt="" onError={() => setBroken(true)} /> : initial}
    </span>
  )
}

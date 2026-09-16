import { useState } from 'react'
import { hueForName } from '../hue'

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
  /** Stable identity for the fallback color -- the manager id, matching the
   *  seed the many call sites this replaces already agreed on. */
  seed: string
  /** An already-spaced hue from the caller, which knows the whole league and so
   *  can space them evenly; see hueForIndex. Falls back to hashing the seed when
   *  absent, which is a cosmetic fallback VALUE rather than a rule -- the photo
   *  is what identifies most managers, and this only paints the initial behind
   *  it. */
  hue?: number
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
export default function Avatar({ avatarId, seed, label, isMe, className, hue: given }: Props) {
  const [broken, setBroken] = useState(false)
  const showPhoto = !!avatarId && !broken
  const hue = given ?? hueForName(seed)
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

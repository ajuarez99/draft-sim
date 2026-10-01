import { useState } from 'react'
import type { Sport } from '../api'
import { hueForName } from '../hue'

type Props = {
  /** Which league's CDN folder to read. Required, never defaulted: a rule, not a value. */
  sport: Sport
  /** Sleeper's player id (for DEF, the team abbreviation). */
  sleeperId: string
  /** Pro team abbreviation, or null for a free agent. Drives the logo step. */
  team: string | null
  /** Only used to start DEF at the logo: a defense has no headshot. */
  position: string
  /** Full name: the alt text, and the source of the initials. */
  name: string
  /** Square edge in px. The box is this size in every state, so nothing shifts. */
  size: number
}

type Stage = 'photo' | 'logo' | 'initials'

const CDN = 'https://sleepercdn.com'

export const playerPhotoSrc = (sport: Sport, sleeperId: string) =>
  `${CDN}/content/${sport}/players/thumb/${sleeperId}.jpg`
export const teamLogoSrc = (sport: Sport, team: string) =>
  `${CDN}/images/team_logos/${sport}/${team.toLowerCase()}.png`

/**
 * A player's face: photo, else team logo, else initials. States only move
 * forward, and only when the image actually errors, so a broken-image icon
 * never shows. The box has a fixed size in all three states (no layout shift),
 * and images load lazily.
 */
export default function PlayerFace(props: Props) {
  // Re-key on identity so a recycled row never inherits the previous player's stage.
  return <Face key={`${props.sport}:${props.sleeperId}:${props.team ?? ''}`} {...props} />
}

function Face({ sport, sleeperId, team, position, name, size }: Props) {
  const [stage, setStage] = useState<Stage>(position === 'DEF' ? 'logo' : 'photo')

  // Skip a logo step there is nothing to fetch for.
  const effective: Stage = stage === 'logo' && !team ? 'initials' : stage
  const hue = hueForName(name)
  const box = { width: size, height: size, flexBasis: size }

  if (effective === 'initials') {
    const initial = name.trim().charAt(0).toUpperCase() || '?'
    return (
      <span
        className="pface pface-initials"
        style={{
          ...box,
          fontSize: Math.max(8, Math.round(size * 0.5)),
          background: `oklch(28% 0.03 ${hue})`,
          color: `oklch(82% 0.1 ${hue})`,
        }}
        role="img"
        aria-label={name}
      >
        {initial}
      </span>
    )
  }

  const src = effective === 'photo' ? playerPhotoSrc(sport, sleeperId) : teamLogoSrc(sport, team as string)
  return (
    <span className={`pface pface-${effective}`} style={box}>
      <img
        key={effective}
        src={src}
        alt={name}
        width={size}
        height={size}
        loading="lazy"
        onError={() => setStage(effective === 'photo' ? 'logo' : 'initials')}
      />
    </span>
  )
}

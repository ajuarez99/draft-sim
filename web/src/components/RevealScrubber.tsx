import { roundPickLabel } from '../roundPickLabel'

type Props = {
  value: number
  max: number
  teams: number
  myPicks: number[]
  onChange: (pickNo: number) => void
  onSkip: () => void
  disabled?: boolean
}

export default function RevealScrubber({ value, max, teams, myPicks, onChange, onSkip, disabled }: Props) {
  if (max <= 0) return null
  const shown = Math.max(1, value)
  return (
    <div className="reveal-controls">
      <div className="reveal-status">
        <span className="muted small">
          Pick {value} of {max} <span className="mono">{roundPickLabel(shown, teams)}</span>
        </span>
        {value < max && (
          <button className="chip" onClick={onSkip} disabled={disabled}>
            skip
          </button>
        )}
      </div>
      <input
        type="range"
        className="reveal-slider"
        min={1}
        max={max}
        value={shown}
        list="reveal-ticks"
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <datalist id="reveal-ticks">
        {myPicks.map((p) => (
          <option key={p} value={p} />
        ))}
      </datalist>
    </div>
  )
}

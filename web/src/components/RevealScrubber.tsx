type Props = {
  value: number
  max: number
  myPicks: number[]
  isRevealing: boolean
  onChange: (pickNo: number) => void
  onSkip: () => void
}

export default function RevealScrubber({ value, max, myPicks, isRevealing, onChange, onSkip }: Props) {
  if (max <= 0) return null
  return (
    <div className="reveal-controls">
      <div className="reveal-status">
        {isRevealing ? (
          <>
            <span className="muted small">Revealing predicted board…</span>
            <button className="chip" onClick={onSkip}>skip</button>
          </>
        ) : (
          <span className="muted small">Pick {value} of {max}</span>
        )}
      </div>
      <input
        type="range"
        className="reveal-slider"
        min={1}
        max={max}
        value={Math.max(1, value)}
        list="reveal-ticks"
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

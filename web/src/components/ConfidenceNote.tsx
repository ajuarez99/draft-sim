import type { Confidence } from '../api'

/**
 * Deliberately not tucked into a collapsed "details" section. The model is thin
 * and the numbers look more precise than they are; that belongs on screen next
 * to the numbers, not behind a click.
 */
export default function ConfidenceNote({ c }: { c: Confidence }) {
  return (
    <section className="panel warn">
      <h2>How much to trust this</h2>
      <p className="small">
        {c.managersWithHistory} of {c.totalSeats} seats have any draft history ·{' '}
        {c.scoreablePicks} picks scoreable against a contemporaneous board · board source:{' '}
        <code>{c.boardSource}</code>
      </p>
      <ul className="small">
        {c.caveats.map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
    </section>
  )
}

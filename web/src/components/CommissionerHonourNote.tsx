/**
 * Commissioner controls run on the honour system, like ballots (claude/audit-2026-09-28/04,
 * amended 2026-10-05): the server checks that the request names the league's commissioner,
 * and that Sleeper id is public. Said beside the controls (power rankings, the conduct list,
 * Recompute) rather than left for someone to discover.
 */
export default function CommissionerHonourNote() {
  return (
    <span className="small muted commissioner-key-note">
      Commissioner controls aren't verified: anyone who knows the commissioner's Sleeper name could use them.
    </span>
  )
}

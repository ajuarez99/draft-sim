import { Fragment } from 'react'
import type { PlayerRef, PredictedPick, Seat } from '../api'
import { hueFor } from '../hue'
import { posRank } from '../posRank'
import { PROVENANCE_LABEL } from '../provenance'

type Props = {
  board: PredictedPick[]
  teams: number
  rounds: number
  myPicks: number[]
  userPicks: Record<number, PlayerRef>
  revealedThrough?: number
  seats?: Seat[]
  // Undefined while the slot isn't known yet -- see DraftView's `slotKnown`.
  // Distinct from "no seats at all" (seats itself being undefined).
  mySlot?: number
  onCellClick?: (pick: PredictedPick) => void
  onSeatClick?: (slot: number) => void
  // The mock draft room (claude/next-features-roadmap.md §4, Phase 3) has no
  // fitted-history provenance to show -- every seat is either the viewing
  // user or an unmodelled bot -- so a NEUTRAL dot on every single header
  // would be exactly the "eleven of fourteen headers carry an identical mark"
  // noise PROVENANCE_LABEL's own comment says this feature exists to avoid.
  // Same additive-prop pattern D used for live mode's `landed` field.
  hideProvenanceDots?: boolean
}

/**
 * Rounds x slots grid, snake-aware. Each cell shows the modal pick, colored by
 * the player's position and badged with his positional rank ("RB4").
 *
 * The per-cell probability and its bar are deliberately gone: by the time a
 * cell is on screen the pick has been made, and "62% of runs" was answering a
 * question nobody is asking at that moment. The number is still real model
 * output and still reachable -- the cell's own `title`, and PlayerCard when you
 * click it. What survives in the cell itself is the qualitative half:
 * `.uncertain` still fades the name when the most likely player here went
 * earlier. See claude/pill-board-and-player-list-on-top.md section B.
 */
export default function DraftBoard({
  board,
  teams,
  rounds,
  myPicks,
  userPicks,
  revealedThrough,
  seats,
  mySlot,
  onCellClick,
  onSeatClick,
  hideProvenanceDots,
}: Props) {
  const byPick = new Map(board.map((p) => [p.pickNo, p]))
  const mine = new Set(myPicks)
  const seatBySlot = new Map((seats ?? []).map((s) => [s.slot, s]))

  return (
    <div className="board-scroll panel-body">
      <div className="board" style={{ gridTemplateColumns: `44px repeat(${teams}, minmax(96px, 1fr))` }}>
        <div className="corner" />
        {Array.from({ length: teams }, (_, i) => {
          const slot = i + 1
          const seat = seatBySlot.get(slot)
          const isMe = mySlot === slot
          const hue = seat ? hueFor(String(seat.managerId)) : hueFor(String(slot))
          const label = seat ? PROVENANCE_LABEL[seat.provenance] : null
          // The header carries what SeatList used to show in its own band
          // (avatar, name, provenance, "you") -- see
          // claude/board-first-layout-and-pick-latency.md §C. A dot, not the
          // old text chip: fourteen "league average" chips across the top
          // would be exactly the noise that map's own comment warns about.
          // A real <button> (not a div) so it's reachable/activatable the
          // same way a revealed cell already is, and opens SeatPopover.
          return (
            <button
              key={i}
              type="button"
              className={`col-head${isMe ? ' mine' : ''}`}
              onClick={() => seat && onSeatClick?.(seat.slot)}
              disabled={!seat}
              title={seat ? `${seat.manager} — click for details` : undefined}
            >
              <span
                className="avatar"
                style={
                  isMe
                    ? { background: 'var(--crimson)', color: 'var(--bg)' }
                    : { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
                }
              >
                {seat ? seat.manager.trim().charAt(0).toUpperCase() : slot}
              </span>
              <span className="col-head-name mono">{seat ? seat.manager : slot}</span>
              <span className="col-head-meta">
                {!hideProvenanceDots && label && (
                  <span className={`col-head-dot ${label.className}`} title={label.badge ?? 'drafts like the league average'} />
                )}
                {isMe && <span className="col-head-you mono">you</span>}
              </span>
            </button>
          )
        })}
        {Array.from({ length: rounds }, (_, r) => {
          const round = r + 1
          return (
            <Fragment key={round}>
              <div className="rnd cond">R{round}</div>
              {Array.from({ length: teams }, (_, s) => {
                const slot = s + 1
                // snake: even rounds run right to left
                const indexInRound = round % 2 === 1 ? slot : teams - slot + 1
                const pickNo = (round - 1) * teams + indexInRound
                const pick = byPick.get(pickNo)
                const hidden = revealedThrough !== undefined && pickNo > revealedThrough
                const visible = hidden ? undefined : pick
                // chosen is a player you actually picked at this slot (§3 of the
                // design doc). chosen and uncertain are mutually exclusive:
                // "uncertain" describes the model's own guess-quality, and a
                // cell you actually picked isn't a guess.
                // Gated on `hidden` like `visible` is -- otherwise scrubbing the
                // reveal slider backward past a pick you've made would keep
                // showing him, the only cell that would leak content past the
                // hidden boundary every other not-yet-revealed cell respects.
                const chosen = hidden ? undefined : userPicks[pickNo]
                // The cell's own fill now comes from the position of whoever is
                // in it (section A) -- so "your seat" and "your pick" had to give
                // the fill up and become rings instead (styles.css `.cell.mine`).
                const shown = chosen ?? visible?.player
                const cls =
                  'cell' +
                  (shown ? ` pos-${shown.position}` : '') +
                  (mine.has(pickNo) ? ' mine' : '') +
                  (chosen ? ' chosen' : visible && !visible.isModal ? ' uncertain' : '')
                const titleAttr = chosen
                  ? `Your pick — ${chosen.name}`
                  : visible
                    ? `${visible.manager} — ${Math.round(visible.probability * 100)}% of runs\n` +
                      (visible.isModal
                        ? ''
                        : 'Not the most likely player here; the most likely one went earlier.\n') +
                      visible.alternatives
                        .map((a) => `${a.player.name} ${Math.round(a.probability * 100)}%`)
                        .join('\n')
                    : ''
                // One branch for both: a pick you made and a pick the model
                // guessed now render identically (dropping the probability and
                // then the "yours" badge is what collapsed them), and `shown`
                // already applies the chosen-wins-over-predicted precedence.
                // The difference between the two lives in `cls` and `titleAttr`.
                const inner = shown ? (
                  <>
                    <span className="pickno mono">{pickNo}</span>
                    <span className={`pos ${shown.position}`}>{posRank(shown)}</span>
                    <span className="name">{shown.name}</span>
                    <div className="meta">
                      <span className="team-code mono">{shown.team ?? '—'}</span>
                    </div>
                  </>
                ) : (
                  <>
                    <span className="pickno mono">{pickNo}</span>
                    <span className="empty">—</span>
                  </>
                )
                // A visible cell is a real button (native focus + Enter/Space
                // activation) so it can open the player card; hidden/pick-less
                // cells have nothing to open and stay inert divs.
                return visible ? (
                  <button
                    key={slot}
                    type="button"
                    className={cls}
                    title={titleAttr}
                    onClick={() => onCellClick?.(visible)}
                  >
                    {inner}
                  </button>
                ) : (
                  <div key={slot} className={cls}>
                    {inner}
                  </div>
                )
              })}
            </Fragment>
          )
        })}
      </div>
    </div>
  )
}

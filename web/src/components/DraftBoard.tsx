import { Fragment, type CSSProperties } from 'react'
import type { PickGrade, ProductionBasis, PlayerRef, PredictedPick, Seat, Sport } from '../api'
import Avatar from './Avatar'
import PlayerFace from './PlayerFace'
import { shortName } from '../playerName'
import { posRank } from '../posRank'
import { eligiblePositions, familyCode, multiVars, positionLabel, posPill } from '../positions'
import { PROVENANCE_LABEL } from '../provenance'
import { isForward, pickNoAt } from '../snake'
import { roundPickLabel } from '../roundPickLabel'
import { pickValue, signedPicks, tintPercent } from '../stealsReaches'
import { signedPoints, valueTint } from '../draftGrades'

type Props = {
  board: PredictedPick[]
  teams: number
  rounds: number
  /** Kept for callers; the board no longer marks cells from it (spec 024 A6: the column mark comes from `mySlot` only). */
  myPicks?: number[]
  userPicks: Record<number, PlayerRef>
  revealedThrough?: number
  seats?: Seat[]
  // Undefined while the slot isn't known yet -- see DraftView's `slotKnown`.
  // Distinct from "no seats at all" (seats itself being undefined).
  mySlot?: number
  /**
   * `mySlot` is only a fallback nobody confirmed (spec 024 FR-006): the column is
   * marked dashed and low-alpha and the header says "assumed".
   */
  mySlotAssumed?: boolean
  /**
   * A slot with no Seat object on a real draft renders "Unclaimed · Claim", which
   * calls this. A mock never passes it (its BOT seats are seats, not vacancies).
   */
  onClaim?: (slot: number) => void
  /** The one pick number that is on the clock now; undefined when no draft is in progress (FR-007). */
  onTheClockPickNo?: number
  /**
   * Which room draws this, because the cell kinds differ per room (FR-008):
   * 'live' -- landed vs empty; 'projection' (default) -- your chosen pick vs a
   * projected one; 'real' -- a mock or a finished draft, every filled cell is a real pick.
   */
  room?: 'live' | 'projection' | 'real'
  /** 'compact' draws a filled cell as one line (FR-001b); 'full' is the cell with face and meta line. */
  density: 'compact' | 'full'
  // Required, never defaulted: it picks the player-photo CDN folder as well as
  // playerName.ts's DEF-abbreviation guard, and a silent 'nfl' would show a
  // football face on a basketball player. Callers pass the draft's own sport.
  sport: Sport
  onCellClick?: (pick: PredictedPick) => void
  onSeatClick?: (slot: number) => void
  // The mock draft room (claude/next-features-roadmap.md §4, Phase 3) has no
  // fitted-history provenance to show -- every seat is either the viewing
  // user or an unmodelled bot -- so a NEUTRAL dot on every single header
  // would be exactly the "eleven of fourteen headers carry an identical mark"
  // noise PROVENANCE_LABEL's own comment says this feature exists to avoid.
  // Same additive-prop pattern D used for live mode's `landed` field.
  hideProvenanceDots?: boolean
  /**
   * The round from which snake parity flips; 0 is plain snake,
   * which is every football draft.
   * Comes from SeatsResponse.reversalRound, which is already the effective
   * value -- Sleeper's, or the user's override of it. Required (spec 024 review
   * #16): an optional rule is how a board once drew the wrong snake direction.
   */
  reversalRound: number
  /**
   * Steals-and-reaches view (a finished draft only). When `valueView` is on,
   * each cell is tinted by pickNo minus the player's ADP *at draft time*, from
   * this map (pickNo -> RealPick.adpAtDraft). Never today's ADP: see
   * stealsReaches.ts. A pick with no entry, or a null one, is left untinted.
   */
  valueView?: boolean
  adpAtDraft?: Record<number, number | null | undefined>
  /**
   * "How it played out" view (spec 018): pickNo -> PickGrade. When passed, a
   * cell shows the signed value over slot (points) with its own tint scale, IN
   * PLACE OF the ADP delta -- never beside it. Takes precedence over
   * `valueView`; the page keeps the two views exclusive anyway.
   */
  grades?: Record<number, PickGrade>
  /** With `grades`: basketball has no single position, so its hover says "player" rather than a position. */
  gradesBasis?: ProductionBasis
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
  userPicks,
  revealedThrough,
  seats,
  mySlot,
  mySlotAssumed = false,
  onClaim,
  onTheClockPickNo,
  room = 'projection',
  density,
  sport,
  onCellClick,
  onSeatClick,
  hideProvenanceDots,
  reversalRound,
  valueView = false,
  adpAtDraft,
  grades,
  gradesBasis,
}: Props) {
  const byPick = new Map(board.map((p) => [p.pickNo, p]))
  const seatBySlot = new Map((seats ?? []).map((s) => [s.slot, s]))
  const gradeValues = grades
    ? Object.values(grades).flatMap((g) => (g.valueOverSlot != null ? [g.valueOverSlot] : []))
    : []

  return (
    <div className="board-scroll panel-body">
      <div className={`board${valueView || grades ? ' value-view' : ''}${density === 'compact' ? ' compact' : ''}`} style={{ gridTemplateColumns: `44px repeat(${teams}, minmax(96px, 1fr))` }}>
        <div className="corner" />
        {Array.from({ length: teams }, (_, i) => {
          const slot = i + 1
          const seat = seatBySlot.get(slot)
          const marked = mySlot === slot
          const isMe = marked && !mySlotAssumed
          const label = seat ? PROVENANCE_LABEL[seat.provenance] : null
          // A vacancy on a real draft: no Seat object for this slot. The whole
          // header is the one action, "make it your seat" (FR-004, A5).
          if (!seat && onClaim) {
            return (
              <button
                key={i}
                type="button"
                className={`col-head unclaimed${isMe ? ' mine' : ''}${marked && mySlotAssumed ? ' assumed' : ''}`}
                onClick={() => onClaim(slot)}
                title={
                  isMe
                    ? `You claimed slot ${slot}. Sleeper hasn't mapped a manager to it yet.`
                    : `Nobody is mapped to slot ${slot} yet. Make it your seat.`
                }
              >
                <Avatar seed={String(slot)} label={String(slot)} isMe={isMe} />
                {/* A claimed vacancy is yours, not "assumed" -- the same isMe/assumed
                    split the mapped-seat branch below makes. */}
                <span className="col-head-name mono">{isMe ? 'Your seat' : 'Unclaimed · Claim'}</span>
                <span className="col-head-meta">
                  {marked && mySlotAssumed && <span className="col-head-you mono">assumed</span>}
                </span>
              </button>
            )
          }
          // The header carries what SeatList used to show in its own band
          // (avatar, name, provenance, "you") -- see
          // claude/board-first-layout-and-pick-latency.md section C. A dot, not
          // the old text chip. A real <button> so it opens SeatPopover.
          return (
            <button
              key={i}
              type="button"
              className={`col-head${isMe ? ' mine' : ''}${marked && mySlotAssumed ? ' assumed' : ''}`}
              onClick={() => seat && onSeatClick?.(seat.slot)}
              disabled={!seat}
              title={seat ? `${seat.manager} — click for details${marked && mySlotAssumed ? ' (assumed to be your seat)' : ''}` : undefined}
            >
              <Avatar
                avatarId={seat?.avatarId}
                seed={String(seat ? seat.managerId : slot)}
                label={seat ? seat.manager : String(slot)}
                isMe={isMe}
              />
              <span className="col-head-name mono">{seat ? seat.manager : slot}</span>
              <span className="col-head-meta">
                {!hideProvenanceDots && label && (
                  <span className={`col-head-dot ${label.className}`} title={label.badge ?? 'drafts like the league average'} />
                )}
                {isMe && <span className="col-head-you mono">you</span>}
                {marked && mySlotAssumed && <span className="col-head-you assumed mono">assumed</span>}
              </span>
            </button>
          )
        })}
        {Array.from({ length: rounds }, (_, r) => {
          const round = r + 1
          const forward = isForward(round, reversalRound)
          return (
            <Fragment key={round}>
              <div className="rnd cond" data-forward={forward} title={forward ? 'Picks run left to right' : 'Picks run right to left'}>
                R{round}
                <span className="rnd-arrow" aria-hidden="true">{forward ? '→' : '←'}</span>
              </div>
              {Array.from({ length: teams }, (_, s) => {
                const slot = s + 1
                // snake -- even rounds run right to left, and from
                // `reversalRound` on that parity is flipped (Sleeper's
                // third-round reversal). Shared with the engine's own
                // DraftSlot rather than inlined here: this used to be a bare
                // `round % 2 === 1`, which draws the 2026 NBA draft's rounds
                // 3-14 in the wrong direction while the simulator picks in the
                // right one (multi-sport-and-rebrand.md Phase 6b).
                const pickNo = pickNoAt(round, slot, teams, reversalRound)
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
                // Only a real, revealed pick can be a steal or a reach.
                const value = !grades && valueView && visible ? pickValue(pickNo, adpAtDraft?.[pickNo]) : null
                const grade = grades && visible ? grades[pickNo] : undefined
                const gradeKind =
                  grade?.valueOverSlot != null && Math.abs(grade.valueOverSlot) >= 0.05
                    ? grade.valueOverSlot > 0
                      ? 'steal'
                      : 'reach'
                    : null
                const onClock = onTheClockPickNo === pickNo
                // FR-008: what kind of cell this is, per room. Live: a landed pick or
                // an empty slot (the projection is never drawn past the last real
                // pick). Projection: your own chosen pick or a projected one. Mock /
                // finished: every filled cell is a real pick.
                const kind = !shown
                  ? 'empty'
                  : room === 'live'
                    ? 'landed'
                    : room === 'real'
                      ? 'real'
                      : chosen
                        ? 'chosen'
                        : 'projected'
                const multi = shown ? multiVars(shown, sport) : undefined
                const cls =
                  `cell kind-${kind}` +
                  // A multi-position player never takes his first position's tint (A7): split instead.
                  (shown
                    ? multi
                      ? ' pos-multi'
                      : // An NBA player with no position gets no football tint (review N3).
                        eligiblePositions(shown, sport).length > 0
                        ? ` pos-${shown.position}`
                        : ''
                    : '') +
                  (value && (value.kind === 'steal' || value.kind === 'reach') ? ` value-${value.kind}` : '') +
                  (gradeKind ? ` value-${gradeKind}` : '') +
                  (chosen ? ' chosen' : visible && !visible.isModal && room === 'projection' ? ' uncertain' : '') +
                  (onClock ? ' on-the-clock' : '')
                const valueTitle =
                  value == null
                    ? ''
                    : value.kind === 'unknown'
                      ? 'No ADP at draft time'
                      : value.delta != null && value.kind !== 'even'
                        ? `Taken ${Math.abs(Math.round(value.delta))} picks ${value.kind === 'steal' ? 'after' : 'before'} his ADP at draft time (${value.kind})`
                        : 'Taken right at his ADP at draft time'
                const gradeTitle =
                  grades && visible
                    ? grade?.valueOverSlot != null
                      ? `${signedPoints(grade.valueOverSlot)} points vs. what a ${gradesBasis === 'WEEKLY_AVERAGE_GAME' || !grade.position ? 'player' : grade.position} taken here scored in this draft (fitted)`
                      : 'No comparison available for this pick'
                    : ''
                const titleAttr = chosen
                  ? `Your pick — ${chosen.name}`
                  : visible
                    ? // The cell shows an abbreviation now, so the hover is
                      // the only place the full name appears without a click.
                      (valueTitle ? `${valueTitle}\n` : '') +
                      (gradeTitle ? `${gradeTitle}\n` : '') +
                      `${visible.player.name}\n${visible.manager} — ${Math.round(visible.probability * 100)}% of runs\n` +
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
                // Abbreviated, not truncated: at 14 teams every column sits on
                // the 96px floor and 23 of 28 revealed names were ellipsized
                // (measured 2026-09-07), which costs the surname -- the half
                // that identifies the player. "J. Gibbs" fits the same cell.
                // The full name stays in the cell's own `title` and PlayerCard.
                const label = shown ? shortName(shown, sport) : null
                const pickLabel = roundPickLabel(pickNo, teams)
                const clockTag = onClock ? <span className="otc-tag cond">On the clock</span> : null
                const inner = shown && label && density === 'compact' ? (
                  <>
                    {/* Compact cells show a family code ("G", "F/C") for a multi-position
                        player; the title carries the full list (A9). */}
                    <span {...posPill(shown, sport)} title={multi ? positionLabel(shown, sport) : undefined}>
                      {multi ? familyCode(shown, sport) : posRank(shown, sport)}
                    </span>
                    <span className="name">
                      <span className="name-lead">{label.lead}</span>
                      {label.rest}
                    </span>
                    <span className="pickno mono">{pickLabel}</span>
                    {clockTag}
                  </>
                ) : shown && label ? (
                  <>
                    <span className="pickno mono">{pickLabel}</span>
                    <span {...posPill(shown, sport)}>{posRank(shown, sport)}</span>
                    <span className="name">
                      <span className="name-lead">{label.lead}</span>
                      {label.rest}
                    </span>
                    {clockTag}
                    {/* The face sits on the meta line, not the name line: on the name
                        line it truncated 16 of 180 names at 1440 vs 1 without
                        (measured, spec 013 parent review). */}
                    <div className="meta">
                      <PlayerFace sport={sport} sleeperId={shown.sleeperId} team={shown.team} position={shown.position} name={shown.name} size={16} />
                      <span className="team-code mono">{shown.team ?? '—'}</span>
                      {grades &&
                        (grade?.valueOverSlot != null ? (
                          <span className={`value-delta mono ${gradeKind ?? 'even'}`} title={gradeTitle}>
                            {signedPoints(grade.valueOverSlot)}
                          </span>
                        ) : (
                          <span className="value-delta none" title={gradeTitle}>
                            —
                          </span>
                        ))}
                      {value &&
                        (value.kind === 'unknown' ? (
                          <span className="value-delta none" title="no ADP at draft time">
                            no ADP
                          </span>
                        ) : (
                          value.delta != null && (
                            <span className={`value-delta mono ${value.kind}`} title={valueTitle}>
                              {signedPicks(value.delta)}
                            </span>
                          )
                        ))}
                    </div>
                  </>
                ) : (
                  // No em-dash placeholder. Measured 2026-09-07: 131 of 150
                  // cells in a mid-draft board drew one, so the great majority
                  // of the board at any moment was two glyphs saying "nothing
                  // here" -- which the absence of a player already says. The
                  // pick number moves into flow for this branch rather than
                  // staying absolutely positioned: with no flow content at all
                  // the cell collapses to its own padding and the absolute
                  // number hangs out the bottom of it.
                  <>
                    <span className="pickno-open mono">{pickLabel}</span>
                    {clockTag}
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
                    data-kind={kind}
                    data-pickno={pickNo}
                    title={titleAttr}
                    style={
                      value && value.delta != null && (value.kind === 'steal' || value.kind === 'reach')
                        ? ({ ...multi, '--vt': `${tintPercent(value.delta)}%` } as CSSProperties)
                        : gradeKind && grade?.valueOverSlot != null
                          ? ({ ...multi, '--vt': `${8 + valueTint(grade.valueOverSlot, gradeValues) * 18}%` } as CSSProperties)
                          : multi
                    }
                    onClick={() => onCellClick?.(visible)}
                  >
                    {inner}
                  </button>
                ) : (
                  <div key={slot} className={cls} data-kind={kind} data-pickno={pickNo} style={multi}>
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

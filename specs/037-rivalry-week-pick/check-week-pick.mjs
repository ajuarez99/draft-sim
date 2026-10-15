// Spec 037 T023: payload invariants for the rivalry week pick, recounted ONLY from seasonGrid.cells
// (independent of RivalryWeekPicker). Usage:
//   node check-week-pick.mjs <payload.json> [liveCurrentWeek]
// liveCurrentWeek: Sleeper's live /v1/state/{sport} week (plan F8a), so "never a played or in-progress
// week" is checked against reality, not against the payload's own leg.
import { readFileSync } from 'node:fs'

const [, , file, liveArg] = process.argv
const d = JSON.parse(readFileSync(file, 'utf8'))
const rec = d.recommendation
const out = []
let fail = 0
const bad = (msg) => { fail++; out.push('FAIL ' + msg) }
const key = (a, b) => (a < b ? `${a}-${b}` : `${b}-${a}`)

if (!rec.week) {
  out.push(`week == null (recommendation.status ${rec.status})`)
  console.log(out.join('\n'))
  process.exit(rec.status === 'OK' ? 1 : 0)
}
const w = rec.week
out.push(`week.status ${w.status}, cap ${w.cap}, asOfWeek ${w.asOfWeek}`)

const g = d.seasonGrid
const regular = new Set(g.weeks.filter((c) => !c.isPlayoffWeek).map((c) => c.week))
const playoff = new Set(g.weeks.filter((c) => c.isPlayoffWeek).map((c) => c.week))
// pair -> weeks, from the grid (each OPPONENT cell is mirrored, so dedupe by the lower manager)
const weeksOf = new Map()
const schedByWeek = new Map()
const played = new Set()
for (const c of g.cells) {
  if (c.state !== 'OPPONENT' || c.opponentManagerId == null || !regular.has(c.week)) continue
  if (c.result) played.add(c.week)
  if (c.managerId > c.opponentManagerId) continue
  const k = key(c.managerId, c.opponentManagerId)
  if (!weeksOf.has(k)) weeksOf.set(k, [])
  weeksOf.get(k).push(c.week)
  if (!schedByWeek.has(c.week)) schedByWeek.set(c.week, new Set())
  schedByWeek.get(c.week).add(k)
}
const managers = new Set(d.matrix.rows.map((r) => r.managerId))
const live = liveArg != null ? Number(liveArg) : null

const options = w.status === 'OK' ? [w.recommended, ...w.others] : []
const bestSet = new Set(rec.pairings.map((p) => key(p.aManagerId, p.bManagerId)))
for (const o of options) {
  const W = o.week
  // 2. eligibility
  if (!regular.has(W) || playoff.has(W)) bad(`week ${W} is not a regular-season week`)
  if (played.has(W)) bad(`week ${W} already has a result`)
  if (g.currentWeek != null && W <= g.currentWeek) bad(`week ${W} <= payload currentWeek ${g.currentWeek}`)
  if (live != null && W <= live) bad(`week ${W} <= Sleeper live week ${live}`)
  // 5. shape: everyone once (one sit-out when odd)
  const seen = new Map()
  for (const wp of o.pairings) {
    for (const m of [wp.pairing.aManagerId, wp.pairing.bManagerId]) seen.set(m, (seen.get(m) ?? 0) + 1)
  }
  const twice = [...seen.values()].some((n) => n > 1)
  const missing = [...managers].filter((m) => !seen.has(m))
  if (twice) bad(`week ${W}: a manager appears twice`)
  if (missing.length !== (managers.size % 2) || (o.unpaired ? missing[0] !== o.unpaired.managerId : missing.length > 0))
    bad(`week ${W}: unpaired mismatch (missing ${missing})`)
  // 1. cap: substitute this week's pairings, recount every pair
  const chosen = new Set(o.pairings.map((wp) => key(wp.pairing.aManagerId, wp.pairing.bManagerId)))
  const counts = new Map()
  for (const [k, ws] of weeksOf) counts.set(k, ws.filter((x) => x !== W).length)
  for (const k of chosen) counts.set(k, (counts.get(k) ?? 0) + 1)
  for (const [k, n] of counts) {
    const before = weeksOf.get(k)?.length ?? 0
    if (n > w.cap && n > before) bad(`week ${W}: pair ${k} rises to ${n} (> cap ${w.cap})`)
  }
  // alreadyScheduled flags
  const sched = schedByWeek.get(W) ?? new Set()
  for (const wp of o.pairings) {
    const k = key(wp.pairing.aManagerId, wp.pairing.bManagerId)
    if (wp.alreadyScheduled !== sched.has(k)) bad(`week ${W}: alreadyScheduled wrong for ${k}`)
  }
  // 3. held back: in the best set, not chosen, and genuinely over the cap if paired here
  for (const h of o.heldBack) {
    const k = key(h.aManagerId, h.bManagerId)
    if (!bestSet.has(k)) bad(`week ${W}: heldBack ${k} not in history-best set`)
    if (chosen.has(k)) bad(`week ${W}: heldBack ${k} was chosen`)
    const all = weeksOf.get(k) ?? []
    const would = all.filter((x) => x !== W).length + 1
    if (would <= w.cap) bad(`week ${W}: heldBack ${k} would only meet ${would}`)
    if (h.wouldMeet !== would) bad(`week ${W}: heldBack ${k} wouldMeet ${h.wouldMeet} != ${would}`)
    const exp = all.filter((x) => x !== W).sort((a, b) => a - b)
    if (JSON.stringify(h.meetsInWeeks) !== JSON.stringify(exp)) bad(`week ${W}: heldBack ${k} weeks ${h.meetsInWeeks} != ${exp}`)
  }
  // every best pair not chosen and disallowed must be listed (and nothing else)
  for (const k of bestSet) {
    const all = weeksOf.get(k) ?? []
    const would = all.filter((x) => x !== W).length + 1
    const listed = o.heldBack.some((h) => key(h.aManagerId, h.bManagerId) === k)
    if ((would > w.cap) !== listed) bad(`week ${W}: best pair ${k} heldBack=${listed} but wouldMeet=${would}`)
  }
  // 4. displaced = scheduled this week minus chosen; met/still split excludes W
  const disp = new Set(o.displaced.map((x) => key(x.aManagerId, x.bManagerId)))
  const expDisp = new Set([...sched].filter((k) => !chosen.has(k)))
  if (disp.size !== expDisp.size || [...disp].some((k) => !expDisp.has(k))) bad(`week ${W}: displaced mismatch`)
  for (const x of o.displaced) {
    const k = key(x.aManagerId, x.bManagerId)
    const other = (weeksOf.get(k) ?? []).filter((v) => v !== W).sort((a, b) => a - b)
    const got = [...x.metInWeeks, ...x.stillMeetsInWeeks].sort((a, b) => a - b)
    if (JSON.stringify(got) !== JSON.stringify(other)) bad(`week ${W}: displaced ${k} weeks ${got} != ${other}`)
    if (x.metInWeeks.some((v) => v > w.asOfWeek) || x.stillMeetsInWeeks.some((v) => v <= w.asOfWeek))
      bad(`week ${W}: displaced ${k} met/still split wrong vs asOfWeek ${w.asOfWeek}`)
  }
  // reason numbers appear in text
  for (const [n, v] of Object.entries(o.reason.numbers)) {
    if (['tiedWeeks', 'bestPairings', 'cap', 'heldBack', 'week', 'firstTiedWeek', 'total', 'bestTotal'].includes(n)
      && /earliest|Ties with|of a possible|of the best|fits week|more than|limit/.test(o.reason.text) === false) bad(`week ${W}: reason text empty?`)
    void v
  }
  out.push(`  week ${W}: total ${o.total}/${o.bestTotal}, kept-as-is ${o.pairings.filter((wp) => bestSet.has(key(wp.pairing.aManagerId, wp.pairing.bManagerId))).length}/${bestSet.size}, heldBack ${o.heldBack.length}, displaced ${o.displaced.length}, code ${o.reason.code}, tied ${o.reason.numbers.tiedWeeks}`)
}
if (w.status === 'OK') out.push(`recommended: week ${w.recommended.week} — "${w.recommended.reason.text}"`)
out.push(`unfitWeeks: [${w.unfitWeeks}]`)
// history-best pairs already meeting twice (research R2's open number)
const atCap = [...bestSet].filter((k) => (weeksOf.get(k)?.length ?? 0) >= w.cap).length
out.push(`history-best pairs already at ${w.cap} regular-season meetings: ${atCap} of ${bestSet.size}`)
out.push(fail ? `${fail} FAILURES` : 'ALL CHECKS PASS')
console.log(out.join('\n'))
process.exit(fail ? 1 : 0)

/** Display titles for each superlative kind. Shared by the Superlatives page, Home and the trophy case. */
export const TITLES: Record<string, { title: string; subtitle?: string; note?: string }> = {
  HIGHEST_WEEK: { title: 'Highest week' },
  LOWEST_WEEK: { title: 'Lowest week' },
  BIGGEST_BLOWOUT: { title: 'Biggest blowout' },
  CLOSEST_GAME: { title: 'Closest game' },
  CLOSE_WINS: { title: 'Closest wins', subtitle: 'Escape artist' },
  CLOSE_LOSSES: { title: 'Closest losses', subtitle: 'Heartbreak kid' },
  LUCKIEST: { title: 'Luckiest' },
  UNLUCKIEST: { title: 'Unluckiest' },
  MOST_BENCH_POINTS: { title: 'Most bench points' },
  WAIVER_WIRE_WARRIOR: { title: 'Waiver Wire Warrior' },
  // US7 (added 2026-09-28): player-headed, not team-headed -- see the
  // isPlayerHeadedKind branch in SuperlativeCard.
  JABARI_SMITH_JR: { title: 'The Jabari Smith Jr. Award' },
  // FR-013: this line is not a caveat that shows up only when relevant -- the
  // award's whole premise is that the source is never the injury tag, so it
  // is printed unconditionally under the title (T049), not folded into a
  // holder line the way other kinds' figures are.
  JOEL_EMBIID: { title: 'The Joel Embiid Award', note: "Weeks missed, cause unknown. An estimate: each week he sat out entirely costs one game's average, and a week he played any game costs nothing." },
  UNETHICAL: { title: 'The Unethical Award' },
  // Spec 051: pickups judged against Sleeper's platform-wide trending list at the time they were made.
  TRENDING_TONY: { title: 'The Trending Tony' },
}

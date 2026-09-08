"""How long is the displacement cascade when one player joins an NBA roster?

3c's planned O(1) rosterNeed precomputes, per eligibility mask, the single
augmenting step that adding a player of that mask would allow. That is only
sound if the augmenting path is SHORT and bounded. Football's is at most 2.
This measures basketball's against the real 2025 Ball Knowers draft.

Cascade length = number of already-assigned players who have to move slots
when the new player is added, i.e. (augmenting path edges - 1) / 2.
"""
import io, json, collections

SLOTS = ["PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL2"]
ACCEPTS = {
    "PG": {"PG"}, "SG": {"SG"}, "G": {"PG", "SG"},
    "SF": {"SF"}, "PF": {"PF"}, "F": {"SF", "PF"},
    "C": {"C"}, "UTIL": {"PG", "SG", "SF", "PF", "C"}, "UTIL2": {"PG", "SG", "SF", "PF", "C"},
}
POSSET = {"PG", "SG", "SF", "PF", "C"}

players = json.load(io.open("nba_players.json", encoding="utf-8"))
picks = json.load(io.open("nba_picks.json", encoding="utf-8"))

def elig(pid):
    p = players.get(str(pid)) or {}
    fp = [x for x in (p.get("fantasy_positions") or []) if x in POSSET]
    return frozenset(fp)

rosters = collections.defaultdict(list)
for pk in picks:
    e = elig(pk.get("player_id"))
    if e:
        rosters[pk["roster_id"]].append(e)

def try_augment(player_elig, assign, seen, occupant_elig):
    """Standard Kuhn augmenting step. assign: slot -> player index (into occupant_elig).
    Returns the augmenting path length in players moved, or None."""
    for slot in SLOTS:
        if slot in seen:
            continue
        if not (player_elig & ACCEPTS[slot]):
            continue
        seen.add(slot)
        if slot not in assign:
            assign[slot] = player_elig
            return 0
        displaced = assign[slot]
        assign[slot] = player_elig
        moved = try_augment(displaced, assign, seen, occupant_elig)
        if moved is not None:
            return moved + 1
        assign[slot] = displaced
    return None

hist = collections.Counter()
unplaceable = 0
samples = 0
worst = []

for rid, roster in sorted(rosters.items()):
    # Rebuild the roster pick by pick, measuring each addition against the
    # starting slots as they stood -- which is exactly what rosterNeed faces.
    assign = {}
    for i, e in enumerate(roster):
        seen = set()
        moved = try_augment(e, assign, seen, roster)
        if moved is None:
            unplaceable += 1        # starters full; he is a bench player
            continue
        hist[moved] += 1
        samples += 1
        if moved >= 2:
            worst.append((rid, i + 1, sorted(e), moved))

print("rosters:", len(rosters), " placements measured:", samples,
      " (plus", unplaceable, "who went straight to the bench: starters full)")
print()
print("cascade length (existing players forced to change slot) -> count")
for k in sorted(hist):
    print(f"  {k}: {hist[k]:4d}   {100.0*hist[k]/samples:5.1f}%")
print()
print("max cascade observed:", max(hist) if hist else 0)
if worst:
    print("every placement needing 2+ moves:")
    for w in worst:
        print("   roster %s pick %-2d %-18s -> %d moved" % (w[0], w[1], ",".join(w[2]), w[3]))

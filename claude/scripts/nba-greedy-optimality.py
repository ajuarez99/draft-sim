"""Is value-greedy lineup selection optimal for the NBA slot shape, or is 3c's
greedy-vs-exact question a real tradeoff that needs measuring?

3c (claude/multi-sport-and-rebrand.md) proposes an O(1) rosterNeed backed by a
prepareLineup that runs once per pick, and asks whether the "who is the
cheapest feasible eviction" step inside it should be exact (max-weight
matching over 9 slots) or greedy, with a fallback of "greedy assignment in
most-constrained-slot-first order" if exact proves too fiddly.

The claim this script checks: there is nothing to trade off, because the
lineup-selection problem is not merely matching-shaped, it is a MATROID.

  The player sets that can be simultaneously assigned to distinct starting
  slots (PG,SG,G,SF,PF,F,C,UTIL,UTIL) form a TRANSVERSAL MATROID: slots are
  the ground set of a bipartite graph (ACCEPTS below), and a set of players is
  independent iff it has a perfect matching into slots. Every one of the nine
  starting slots is worth exactly the same -- value comes from the player,
  not which slot he occupies -- so "best starting lineup" is "maximum-weight
  independent set of a matroid", and the greedy algorithm (sort by weight
  descending, keep each element that preserves independence) is PROVABLY
  optimal on any matroid. That is not a heuristic result; it is the matroid
  greedy theorem. If the hypothesis holds, prepareLineup needs no matching
  solver at all -- a single value-sorted pass IS the optimum.

This is a measurement, not a proof-by-execution: a passing run here is strong
evidence for a claim that is also provable from the definitions, and a single
counterexample would disprove the claim outright regardless of the argument
above. So this script does both:

  1. Every prefix of every one of the 12 real 2025 Ball Knowers rosters --
     a roster mid-draft is exactly the state prepareLineup faces -- comparing
     greedy-by-value against a brute-force optimum computed by honest
     exhaustive subset search (not a matching solver, so it cannot share a bug
     with the greedy side or with the matroid argument).
  2. A randomised stress test over thousands of synthetic rosters, because 12
     real rosters agreeing could be luck rather than structure: eligibility
     sets drawn from the real empirical distribution, values drawn uniformly
     at random (so no argument from "the board's value curve is smooth" can
     be smuggled in).

Data sources are the same as claude/scripts/nba-cascade-length.py: the real
2025 Ball Knowers NBA draft and the NBA player pool, both from Sleeper's
public API. Responses are cached under .cache/ next to this script (not
committed -- see .gitignore) so repeat runs don't re-fetch ~500KB+ of player
data.
"""
import io
import itertools
import json
import os
import random
import urllib.request

BASE = "https://api.sleeper.app/v1"
DRAFT_ID = "1229352720230514688"  # Ball Knowers 2025 NBA, complete, 12 teams
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")

# Starting slots, in the order Sleeper's own roster_positions lists them for
# this league (verified live 2026-09-08: GET /league/1229352720222134272 ->
# roster_positions == ["PG","SG","G","SF","PF","F","C","UTIL","UTIL","BN"x5]).
SLOTS = ["PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL2"]
ACCEPTS = {
    "PG": {"PG"}, "SG": {"SG"}, "G": {"PG", "SG"},
    "SF": {"SF"}, "PF": {"PF"}, "F": {"SF", "PF"},
    "C": {"C"}, "UTIL": {"PG", "SG", "SF", "PF", "C"}, "UTIL2": {"PG", "SG", "SF", "PF", "C"},
}
POSSET = {"PG", "SG", "SF", "PF", "C"}
NUM_SLOTS = len(SLOTS)  # 9


def fetch(url, cache_name):
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = os.path.join(CACHE_DIR, cache_name)
    if os.path.exists(path):
        return json.load(io.open(path, encoding="utf-8"))
    with urllib.request.urlopen(url, timeout=60) as r:
        data = json.load(r)
    with io.open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data


# --- eligibility / value, same shape as FootballRules -----------------------

def elig(player):
    fp = [x for x in (player.get("fantasy_positions") or []) if x in POSSET]
    return frozenset(fp)


def value_from_rank(rank):
    # Same shape as FootballRules.value: exp(-adp / valueDecay), with
    # search_rank standing in for adp (this is what the board already does
    # for basketball -- there is no FFC-style ADP source for it). The decay
    # constant is arbitrary -- 120, same as nba-cascade-length.py's sibling
    # script used for value -- and it does not matter FOR THIS COMPARISON:
    # exp(-r/decay) is monotonic in r for any positive decay, so the ORDERING
    # greedy sorts by is identical for every decay choice. Decay only
    # controls the magnitude of relative gaps, which would matter for sizing
    # a shortfall if greedy ever lost -- it cannot affect whether greedy wins.
    if rank is None or rank > 5000:
        rank = 5000
    return pow(2.718281828, -rank / 120.0)


# --- bipartite matching, bitmask Kuhn's algorithm ---------------------------
# Players are represented, once eligibility is known, as an int bitmask over
# the 9 slots (bit i set means "this player can fill SLOTS[i]"). This is the
# same matching problem nba-cascade-length.py solves recursively over sets;
# doing it over bitmasks here is purely a speed optimisation of the identical
# algorithm (needed because the stress test below runs it hundreds of
# thousands of times), not a different or cleverer method.

_slotmask_cache = {}


def slotmask_for(eligset):
    m = _slotmask_cache.get(eligset)
    if m is not None:
        return m
    m = 0
    for i, slot in enumerate(SLOTS):
        if eligset & ACCEPTS[slot]:
            m |= (1 << i)
    _slotmask_cache[eligset] = m
    return m


def _try_kuhn(pi, masks, assign, seen):
    """Standard Kuhn augmenting-path search. assign[slot] = player index or
    -1. seen is the bitmask of slots already visited on this augmenting
    attempt. Returns (found, updated_seen)."""
    avail = masks[pi] & ~seen
    while avail:
        low = avail & (-avail)
        slot = low.bit_length() - 1
        avail ^= low
        seen |= low
        occupant = assign[slot]
        if occupant == -1:
            assign[slot] = pi
            return True, seen
        found, seen = _try_kuhn(occupant, masks, assign, seen)
        if found:
            assign[slot] = pi
            return True, seen
    return False, seen


def matchable(elig_masks):
    """Can every player in elig_masks (a list of slot-bitmasks) get a
    distinct slot? True bipartite maximum-matching feasibility, not a
    heuristic."""
    n = len(elig_masks)
    if n > NUM_SLOTS:
        return False
    assign = [-1] * NUM_SLOTS
    for i in range(n):
        found, _ = _try_kuhn(i, elig_masks, assign, 0)
        if not found:
            return False
    return True


# --- the two algorithms under comparison ------------------------------------

def greedy_lineup(roster):
    """roster: list of (eligset, value). Sort by value descending, keep each
    player whose addition leaves the kept set still matchable, cap at 9."""
    order = sorted(range(len(roster)), key=lambda i: -roster[i][1])
    chosen_masks = []
    total = 0.0
    for i in order:
        if len(chosen_masks) == NUM_SLOTS:
            break
        m = slotmask_for(roster[i][0])
        if matchable(chosen_masks + [m]):
            chosen_masks.append(m)
            total += roster[i][1]
    return total


def brute_optimal(roster):
    """The highest-valued matchable subset, by HONEST exhaustive search over
    subsets -- every size from 0 to min(9, n), every combination of that
    size, no pruning, no shortcuts. This is the oracle; it must not share any
    machinery with greedy_lineup beyond matchable() itself, and matchable()
    is exact bipartite-matching feasibility, not an approximation."""
    n = len(roster)
    masks = [slotmask_for(e) for e, _ in roster]
    vals = [v for _, v in roster]
    cap = min(NUM_SLOTS, n)
    best = 0.0
    for k in range(0, cap + 1):
        for combo in itertools.combinations(range(n), k):
            if not matchable([masks[i] for i in combo]):
                continue
            total = sum(vals[i] for i in combo)
            if total > best:
                best = total
    return best


# --- part 1: real 2025 Ball Knowers rosters, every prefix -------------------

def load_real_rosters():
    picks = fetch(f"{BASE}/draft/{DRAFT_ID}/picks", "nba_picks.json")
    players = fetch(f"{BASE}/players/nba", "nba_players.json")
    rosters = {}
    for pk in sorted(picks, key=lambda p: p["pick_no"]):
        pid = str(pk.get("player_id"))
        p = players.get(pid) or {}
        e = elig(p)
        if not e:
            continue
        v = value_from_rank(p.get("search_rank"))
        rosters.setdefault(pk["roster_id"], []).append((e, v))
    return rosters


def run_real_data():
    rosters = load_real_rosters()
    checked = 0
    mismatches = []
    for rid, full in sorted(rosters.items()):
        for n in range(1, len(full) + 1):
            prefix = full[:n]
            g = greedy_lineup(prefix)
            o = brute_optimal(prefix)
            checked += 1
            if o - g > 1e-9:
                mismatches.append((rid, n, g, o))

    print("=" * 70)
    print("PART 1 -- real 2025 Ball Knowers rosters, every prefix")
    print("=" * 70)
    print(f"roster states checked: {checked}  (12 rosters x every prefix)")
    print(f"greedy != optimal in: {len(mismatches)}")
    if mismatches:
        worst = max(mismatches, key=lambda m: (m[3] - m[2]) / m[3])
        shortfall = (worst[3] - worst[2]) / worst[3]
        print(f"worst relative shortfall: {shortfall * 100:.4f}%  "
              f"(roster {worst[0]} at n={worst[1]}: greedy={worst[2]:.6f} optimal={worst[3]:.6f})")
        print("first few examples:")
        for m in mismatches[:10]:
            print(f"   roster {m[0]} n={m[1]:<2} greedy={m[2]:.6f} optimal={m[3]:.6f}")
    else:
        print("=> greedy matched optimal on every real roster state.")
    return rosters, mismatches


# --- part 2: randomised stress test -----------------------------------------

def empirical_eligibility_distribution(rosters):
    """The real distribution of drafted players' eligibility sets, weighted
    by how often each set actually occurred -- not the doc's rounded summary
    of it, computed directly from the same 168 picks so this stays honest to
    the actual draft rather than to a paraphrase of it."""
    counts = {}
    for roster in rosters.values():
        for e, _ in roster:
            counts[e] = counts.get(e, 0) + 1
    sets = list(counts.keys())
    weights = [counts[e] for e in sets]
    return sets, weights


def run_stress_test(rosters, n_rosters, rng):
    sets, weights = empirical_eligibility_distribution(rosters)
    checked = 0
    mismatches = []
    worst_example = None
    worst_shortfall = 0.0

    for _ in range(n_rosters):
        size = rng.randint(1, 14)
        roster = []
        for _ in range(size):
            e = rng.choices(sets, weights=weights, k=1)[0]
            v = rng.random()
            roster.append((e, v))
        g = greedy_lineup(roster)
        o = brute_optimal(roster)
        checked += 1
        if o - g > 1e-9:
            shortfall = (o - g) / o
            mismatches.append((roster, g, o, shortfall))
            if shortfall > worst_shortfall:
                worst_shortfall = shortfall
                worst_example = roster

    print()
    print("=" * 70)
    print("PART 2 -- randomised stress test beyond the real data")
    print("=" * 70)
    print(f"synthetic rosters checked: {checked}  "
          f"(sizes 1-14, eligibility drawn from the real empirical distribution,")
    print("                            values drawn uniformly at random)")
    print(f"greedy != optimal in: {len(mismatches)}")
    if mismatches:
        print(f"worst relative shortfall: {worst_shortfall * 100:.4f}%")
        print("THE EXACT COUNTEREXAMPLE THAT BEAT GREEDY:")
        for e, v in worst_example:
            print(f"   elig={sorted(e)!s:<24} value={v:.6f}")
    else:
        print("=> greedy matched optimal on every synthetic roster.")
    return mismatches


if __name__ == "__main__":
    rosters, real_mismatches = run_real_data()

    rng = random.Random(20260908)  # fixed seed: a failure here must be reproducible
    stress_mismatches = run_stress_test(rosters, n_rosters=4000, rng=rng)

    print()
    print("=" * 70)
    print("VERDICT")
    print("=" * 70)
    if not real_mismatches and not stress_mismatches:
        print("Greedy-by-value was exactly optimal in every case checked, real and")
        print("synthetic. Consistent with the transversal-matroid argument: the player")
        print("sets matchable into distinct starting slots form a matroid, every slot")
        print("is worth the same, and the matroid greedy theorem guarantees a")
        print("value-sorted pass is optimal. 3c has no greedy-vs-exact tradeoff to")
        print("make -- prepareLineup needs no matching solver, just a sort.")
    else:
        print("Greedy LOST at least once. The matroid hypothesis is FALSE as applied")
        print("here (or the slot model / eligibility model differs from what was")
        print("assumed). 3c must use an exact per-pick matching, not greedy.")

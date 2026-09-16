"""Football-parity check for multi-sport-and-rebrand.md acceptance criterion 1.

Re-runs the two captured baselines against a running backend and prints the
sha256 of each response with `name` and `team` stripped from every object.

Those two fields come out on purpose. During Phase 3a/3b the 14-team baseline
"moved" and the entire diff was one player's `team` going NYG -> null: a player
released between two runs an hour apart, picked up by a routine re-ingest.
Nothing the engine decided had changed. Hashing the raw body makes this check
fail on ordinary NFL roster churn, which trains you to ignore the one check
standing between a seven-phase refactor and a silent regression.

Stripping those two fields is NOT enough on its own, which is what
BASELINE_BOARD below is for. The engine reads the derived board, so rebuilding
the board moves every hash here for reasons that have nothing to do with code.
Measured 2026-09-15: between the board these baselines were taken against
(2026-09-09) and the next rebuild (2026-09-13), 791 of 850 entries changed adp,
18 players left the board entirely, and the largest single move was 418 picks.
Both baselines MOVED, and nothing in the engine had been touched. So the check
now reports which board it ran against and refuses to call a mismatch a
regression when it is standing on different inputs.

    python claude/scripts/football-parity-hash.py
"""

import hashlib
import json
import urllib.request

BASE = "http://localhost:8080"

# Both from the plan doc. mySlot 5, 500 iterations, a PARTIAL startState (an
# empty one is not a cold start -- resolveStartState treats it as "not
# supplied" and replays every recorded pick, so against a `complete` draft the
# run simulates nothing and is byte-identical under any seed), fixed seed.
BASELINES = {
    "1391509064357273600": "22dbc2d5a408bee4947d73d6f8c6b0ab4e073f296056cafbc2c8a10991ceaacb",
    "1346366555776126976": "6e4ce7bfdb45beb098c3bcb2b2b91d4f6e336a00b667e662d314a084c7fec7c0",
}

VOLATILE = ("name", "team")

# The board the BASELINES above were captured against, from GET /api/board's own
# `capturedOn`. A rebuild since then invalidates the hashes as inputs, not as
# code -- see the module docstring. Re-baselining is a deliberate act: the
# expected hashes are the only record of what the engine used to decide, so they
# are not refreshed automatically just because the board moved.
BASELINE_BOARD = "2026-09-09"


def strip(node):
    if isinstance(node, dict):
        return {k: strip(v) for k, v in node.items() if k not in VOLATILE}
    if isinstance(node, list):
        return [strip(v) for v in node]
    return node


def run(draft_id):
    body = json.dumps({
        "draftSleeperId": draft_id,
        "mySlot": 5,
        "iterations": 500,
        "temperature": 1.0,
        "startState": {"1": "9221"},
        "seed": 20260908,
    }).encode()
    req = urllib.request.Request(
        f"{BASE}/api/sims", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        payload = json.load(r)
    canonical = json.dumps(strip(payload), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


def board_captured_on():
    """When the board the engine is about to read was built."""
    with urllib.request.urlopen(f"{BASE}/api/board?sport=nfl", timeout=60) as r:
        return json.load(r).get("capturedOn")


if __name__ == "__main__":
    board = board_captured_on()
    stale = board is not None and board != BASELINE_BOARD
    print(f"board {board}  (baselines taken against {BASELINE_BOARD})")
    if stale:
        print("  ^ the board has been rebuilt since these baselines were captured.\n"
              "    Any MOVED below is the inputs moving, not the engine. Re-capture the\n"
              "    baselines against this board -- deliberately, and in their own commit --\n"
              "    before using this check to clear a refactor.")

    failed = False
    for draft_id, expected in BASELINES.items():
        got = run(draft_id)
        ok = got == expected
        failed |= not ok
        print(f"draft {draft_id}\n  expected {expected}\n  got      {got}  {'MATCH' if ok else 'MOVED'}")

    # A mismatch on a board the baselines never saw is not a regression signal,
    # and exiting 1 for it is what teaches people to stop running this.
    if failed and stale:
        print("\nINCONCLUSIVE: hashes moved, but so did the board underneath them.")
        raise SystemExit(2)
    raise SystemExit(1 if failed else 0)

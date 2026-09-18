# Contract: `SportRules` starting lineup

**Story**: US1 (P1) — gates US2 and US5.

This is the load-bearing contract of the feature. It is an internal Java interface rather than an HTTP
surface, and it is where "compatible with basketball" is either true or false.

## Current state

```java
default List<Assigned> startingLineup(RosterState roster, LeagueSettings settings,
                                      ToDoubleFunction<BoardEntry> valueOf) {
    throw new UnsupportedOperationException(...);
}
```

| | `startingLineup` | `startingLineupValue` |
|---|---|---|
| `FootballRules` | implemented | delegates to `startingLineup` |
| `BasketballRules` | **inherits the throwing default** | standalone |

## Required state

```java
/** Which players the seat would actually start, valued by valueOf, with the slot each fills. */
List<Assigned> startingLineup(RosterState roster, LeagueSettings settings,
                              ToDoubleFunction<BoardEntry> valueOf);   // no default

/** Expected value of that lineup. MUST delegate to startingLineup. */
double startingLineupValue(RosterState roster, LeagueSettings settings);
```

**The default is removed, not overridden.** A throwing default is inheritable, and a future sport that
forgets to implement it gets a runtime failure instead of a compile error. Removing it turns the same
mistake into a build break — which is the whole point (FR-001).

## Obligations on every implementation

1. **One rule, two readings.** `sum(startingLineup(r, s, f).value()) == startingLineupValue(r, s)` when
   `f` is that sport's own `value`. Not approximately — exactly. This is asserted per registered sport.
2. **Correct for non-ADP-monotone value functions.** Realized weekly points are the motivating case:
   a late-round player routinely outscores an early one in a given week. Candidates are sorted by
   `valueOf` before slots are filled. Football's javadoc already states why this is not decoration;
   basketball's matroid greedy is likewise optimal only for a pass in non-increasing value order (R5).
3. **Total assignment.** Every returned `Assigned` names a real slot in the league's settings, no slot
   is filled twice, and no player is seated twice.
4. **No projection assumption.** The method values a lineup with whatever function it is handed. It must
   not assume that function is a projection — the backward-looking callers in this feature pass realized
   points, which is exactly the case the current throwing default wrongly excludes (R4).

## Basketball implementation note

`BasketballRules.prepareLineup(roster, settings, valueOf)` **already takes an arbitrary value function**
and already solves the hard problem — the maximum-weight independent set over a transversal matroid,
provably optimal by the matroid greedy theorem. `startingLineupValue` is currently
`lineupValue(prepareLineup(roster, settings, this::value))`.

So `startingLineup` is implemented by reading the seated players and their slots off the `Lineup` that
`prepareLineup` already builds, and `startingLineupValue` is then redefined to delegate to it — matching
FootballRules. **No new solver, and no second implementation of the matroid logic.**

## Acceptance

- `startingLineup` on an NBA roster returns seated players with slots and does not throw (US1.1).
- Value/report agreement holds for every registered sport (US1.2, SC-003).
- A deliberately ADP-inverted value function produces a lineup optimal for *that* function, in both
  sports (US1.3, R5).
- Football's existing values are unchanged (US1.4) — asserted against current output, since
  `startingLineupValue`'s delegation there is already in place and must stay provably a no-op.
- No throwing implementations of `startingLineup` remain anywhere (SC-003).

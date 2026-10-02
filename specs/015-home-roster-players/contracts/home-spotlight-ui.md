# UI contract: Player spotlight section on the root home page (spec 015)

No HTTP contract changes. This feature consumes `specs/014-home-player-spotlight/contracts/player-spotlight-api.md`
and the existing weekly-report endpoint as they are. This file pins the **UI** contract the tests and
the verification pass check.

## Placement

On `/` (`DraftPicker`), in this order:

1. Page header
2. Your leagues
3. From Sleeper (error form or list form; either may be absent)
4. **Player spotlight** ← new
5. Mock drafts

The section is absent (not an empty state) when `drafts` is still loading or `visibleLineages` is
empty.

## Markup shape

```text
<section class="section picker-section home-spotlight" aria-labelledby="home-spotlight-h">
  <div class="panel-head">
    <h2 id="home-spotlight-h" class="section-title">Player spotlight</h2>
    <a href="/leagues/{selectedId}">Open league</a>                ← FR-009, follows the selected tab
  </div>
  <div role="tablist" aria-label="Leagues" class="home-spot-leagues">
    <button role="tab" id="home-spot-league-{id}" aria-selected aria-controls="home-spot-panel-{id}"
            tabindex="0|-1">{league name} <sport pill/></button>       ← one per visible lineage
  </div>
  <div role="tabpanel" id="home-spot-panel-{id}" aria-labelledby="home-spot-league-{id}" hidden?>
    … one per VISITED tab; the unselected ones are `hidden`
    SpotlightLists idPrefix="home-{id}"                              ← the league home's lists, unchanged
  </div>
</section>
```

## Behaviour

| # | Given | When | Then |
|---|---|---|---|
| U1 | ≥1 visible lineage | the page loads | the section renders with the first tab selected, and only that tab's spotlight is requested |
| U2 | a tab not yet visited | it is clicked | its spotlight is requested; the previous tab's panel is hidden, not unmounted |
| U3 | a tab already visited | it is clicked again | no request is made and no skeleton appears |
| U4 | the spotlight is ok, `applies`, no `topOfNight`, `period` set | – | exactly one weekly-report request for that league, `week=0` |
| U5 | the spotlight is ok with `topOfNight`, or `period: null`, or `applies: false` | – | no weekly-report request |
| U6 | the spotlight fails (5xx or 404) | – | that panel says it couldn't load, naming the league; other tabs and other sections are untouched |
| U7 | `applies: false` | – | the past-season sentence, never a blank panel |
| U8 | the sport filter changes so the selected league is hidden | – | the first visible tab becomes selected |
| U9 | keyboard focus is on a league tab | ArrowRight / ArrowLeft / Home / End | selection moves and wraps, matching the segmented control |
| U10 | viewport width 375px | – | no horizontal page scroll; the league strip scrolls within itself; the columns collapse to the phone segmented control |
| U11 | the league home and the home tab, same league, same moment | compared | identical entries, points, periods, owners and reasons |

## Source-scan rule (FR-014)

The new home section component, like `PlayerSpotlight.tsx`, must contain no sport literal. The
pattern `NoSportNameInPlayerSpotlightTest` enforces flags any quoted `nba`/`nfl` and `Sport.NBA`/`Sport.NFL`,
comparisons or not. The sport reaches the tab only as the pill's label, passed through the existing
pill component as a value.

Add the new file to that test as an **unconditional** check. The existing component check returns
early when its file is missing (`...WhenItExists`), and a skipped check passing green is the trap
`project_backend_suite_skips_its_silently` records. The new file exists in the same change, so the
test must fail if it is renamed away.

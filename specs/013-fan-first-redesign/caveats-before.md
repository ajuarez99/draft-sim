# Caveats inventory, BEFORE the 013 fan-first redesign

Generated from source at commit 5b3f446 (worktree `013-fan-first-redesign`, no source files changed). Purpose: this is the baseline inventory of every user-visible caveat, method statement and empty/reason message in the pages and components the redesign will touch, so the build can be diffed against it and no caveat is lost when subtitles are rewritten and methodology moves into "How this works" disclosures. `caveats-after.md` must map every row below (C001 onward) to where its text lives after the build (same text, moved into a disclosure, reworded with the qualification intact, or an explicit, justified removal). Kinds: CAVEAT = qualifies or limits how a number may be read; METHOD = explains how a figure was produced; EMPTY = empty-state or why-this-is-missing message. Template variables are written as {var}; backend-supplied strings (reasons, notes, errors) are marked as such. Rows are grouped by source file; conditions say when the text renders. Pure labels, headings and code comments are excluded; navigation-only components (Rail, AppShell, Skeleton) were reviewed and carry none. Test files (*.test.tsx) were not scanned.


## provenance.ts

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C001 | CAVEAT | web/src/provenance.ts:14 | always (STATED seat) | your call |
| C002 | CAVEAT | web/src/provenance.ts:15 | always (FITTED seat) | from history |
| C003 | CAVEAT | web/src/provenance.ts:16 | always (BLENDED seat) | both |
| C004 | CAVEAT | web/src/provenance.ts:13 | NEUTRAL seat: deliberately no badge/dot label (absence of label is itself the signal) | (no text; badge: null) |

## managerBehaviour.ts

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C005 | CAVEAT | web/src/managerBehaviour.ts:62 | relativeReachRead: reach present but std error null (<2 scoreable picks) | too few picks to compare with their room |
| C006 | CAVEAT | web/src/managerBehaviour.ts:63 | \|relative reach\| <= one std error | drafts like the room |
| C007 | METHOD | web/src/managerBehaviour.ts:66 | reach > 0 and outside one std error | {n} picks earlier than their draft room |
| C008 | METHOD | web/src/managerBehaviour.ts:67 | reach < 0 and outside one std error | {n} picks later than their draft room |
| C009 | METHOD | web/src/managerBehaviour.ts:77 | unpredictability >= 1.25 | erratic |
| C010 | METHOD | web/src/managerBehaviour.ts:78 | unpredictability <= 0.8 | very predictable |
| C011 | METHOD | web/src/managerBehaviour.ts:87 | per tilt with \|v-1\|>0.05, top 2 | leans {pos} / fades {pos} |
| C012 | EMPTY | web/src/managerBehaviour.ts:93 | no reach clause, no erratic/predictable, no tilt | No clear positional lean in what they have drafted |
| C013 | CAVEAT | web/src/managerBehaviour.ts:94 | behaviourText: clauses joined; reach clause suppressed entirely when picksScored == 0 | {clauses joined with " · "} |
| C014 | EMPTY | web/src/managerBehaviour.ts:109-111 | reachGapText: draftsObserved > 0 and picksScored === 0 | {d} of history, but no pick in {it\|them} can be scored for reach — no board snapshot exists from close enough to those drafts. The position leanings are real; there is no reach number, and the engine uses the league average in its place. ({d} = "N draft(s)") |

## SeasonFallbackNote.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C015 | CAVEAT | web/src/components/SeasonFallbackNote.tsx:26-27 | requestedSeason != null and differs from shown season | {requestedSeason} has no scored weeks yet, so this is {season} — the most recent season this league has played. |

## SignIn.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C016 | METHOD | web/src/pages/SignIn.tsx:59-61 | always | Enter your Sleeper username (or paste your Sleeper profile link) to see your own leagues and get your seat highlighted at the table. No password -- this app never asks Sleeper for anything private. |
| C017 | EMPTY | web/src/pages/SignIn.tsx:83 | status === not-found | No Sleeper user called “{lastTried}”. Check the spelling and try again. |
| C018 | EMPTY | web/src/pages/SignIn.tsx:88 | status === unreachable | Couldn’t reach Sleeper just now -- that’s not on you. |

## CompletedDraftBoard.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C019 | METHOD | web/src/pages/CompletedDraftBoard.tsx:85 | always (panel head sub) | What actually happened |

## DraftPicker.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C020 | METHOD | web/src/pages/DraftPicker.tsx:39-47 | while a Sleeper league is being set up (stage in progress) | Fetching players / Reading your league's history / Checking market prices / Building the board / Loading past seasons |
| C021 | METHOD | web/src/pages/DraftPicker.tsx:320 | PageHeader sub; drafts not yet loaded | Bots fill every seat but yours, and you take your own picks on your turn. |
| C022 | METHOD | web/src/pages/DraftPicker.tsx:323-325 | sub; a sport filter is active and a league of that sport exists | {leagueName} is a {teams}-team, {rounds}-round league — get more reps before its next draft, in a room only you control. |
| C023 | METHOD | web/src/pages/DraftPicker.tsx:328 | sub; drafts exist and filter = all | Bots fill every seat but yours, or seat the real managers from any of your leagues below. |
| C024 | METHOD | web/src/pages/DraftPicker.tsx:330 | sub; otherwise (no league for filter) | Bots fill every seat but yours — add a league below to seat your real managers instead of them. |
| C025 | EMPTY | web/src/pages/DraftPicker.tsx:359 | no leagues and sport filter != all | No {SPORT} leagues yet. |
| C026 | EMPTY | web/src/pages/DraftPicker.tsx:361 | no leagues, filter all, Sleeper has un-ingested leagues | No leagues set up yet — pick one from Sleeper below. |
| C027 | EMPTY | web/src/pages/DraftPicker.tsx:362 | no leagues, filter all, nothing from Sleeper | No leagues yet — add one below. |
| C028 | METHOD | web/src/pages/DraftPicker.tsx:432 | title tooltip on season link (multi-season league) | {season} {draft board\|mock draft} · {teams} managers |
| C029 | CAVEAT | web/src/pages/DraftPicker.tsx:497 | after Refresh: backend returned no seatsMapped number | {status} · no seat count from this backend |
| C030 | CAVEAT | web/src/pages/DraftPicker.tsx:506-507 | after Refresh: seatsMapped reported | All {teams} managers identified / Only {seatsMapped} of {teams} managers identified |
| C031 | CAVEAT | web/src/pages/DraftPicker.tsx:511 | after Refresh: t.observed === false (Sleeper unreachable, status is stale DB value) |  · stale |
| C032 | EMPTY | web/src/pages/DraftPicker.tsx:491 | after Refresh failed | {t.failed error message} |
| C033 | METHOD | web/src/pages/DraftPicker.tsx:519 | title tooltip on league card Refresh button | Check Sleeper now and refresh this draft's status and seat mapping |
| C034 | EMPTY | web/src/pages/DraftPicker.tsx:552 | Sleeper league list failed to load | Couldn’t load your Sleeper leagues ({sleeperError}). |
| C035 | EMPTY | web/src/pages/DraftPicker.tsx:606 | setup stage failed for a Sleeper league | {stage label}: {error message} |
| C036 | EMPTY | web/src/pages/DraftPicker.tsx:644 | mock list empty, filter nba | NBA mock drafts aren't available yet. |
| C037 | EMPTY | web/src/pages/DraftPicker.tsx:645 | mock list empty, other filters | No mock drafts yet — start one above. |
| C038 | METHOD | web/src/pages/DraftPicker.tsx:662-668 | each mock row | {teams}-team {SPORT} mock · {source league} / {rounds} rounds · your pick {r.p} · finished at\|paused at {r.p}; {picksMade}/{totalPicks} picks · {pct}% |
| C039 | METHOD | web/src/pages/DraftPicker.tsx:702 | League data details summary | Add a Sleeper league by link or ID |
| C040 | METHOD | web/src/pages/DraftPicker.tsx:705-706 | League data details body, always (when expanded) | Paste a Sleeper league link or ID to add its draft history. Load the player pool and board separately first if this is a brand new install. |
| C041 | EMPTY | web/src/pages/DraftPicker.tsx:708 | add-league failed | {addError message} |

## ManagerTendencies.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C042 | CAVEAT | web/src/pages/ManagerTendencies.tsx:36 | manager has both a stated and an empirical reach bias | you said {+stated} · history says {+empirical} over {n} draft(s) ({picksScored} picks) |
| C043 | CAVEAT | web/src/pages/ManagerTendencies.tsx:81-87 | reach axis caption, whenever a reach figure is drawn | later  [{read.text}{ + if \|rel\|>15} (±{se})]  earlier |
| C044 | CAVEAT | web/src/pages/ManagerTendencies.tsx:84 | \|relative reach\| exceeds axis scale of 15 |  + (suffix after reach text; bar is clamped) |
| C045 | CAVEAT | web/src/pages/ManagerTendencies.tsx:85 | std error non-null | (±{se}) |
| C046 | METHOD | web/src/pages/ManagerTendencies.tsx:99 | no positional tilt beyond 0.05 from neutral | No strong positional lean |
| C047 | METHOD | web/src/pages/ManagerTendencies.tsx:105 | per tilt (top 2) | {pos} early / {pos} late |
| C048 | METHOD | web/src/pages/ManagerTendencies.tsx:108-109 | unpredictability >=1.25 / <=0.8 (shown only when a tilt exists) | erratic / very predictable |
| C049 | EMPTY | web/src/pages/ManagerTendencies.tsx:170 | provenance NEUTRAL and draftsObserved == 0 | Drafts like the room — nothing entered, no history yet. |
| C050 | EMPTY | web/src/pages/ManagerTendencies.tsx:193-194 | no reach number; text also as title tooltip when gap exists | {reachGapText(m)} else: No history and no stated value — there is no reach number to show. |
| C051 | CAVEAT | web/src/pages/ManagerTendencies.tsx:213 | provenance dot title tooltip (non-NEUTRAL) | Tendencies {your call\|from history\|both} |
| C052 | METHOD | web/src/pages/ManagerTendencies.tsx:218 | Edit button tooltip | Stop editing without saving / Edit your private note about this manager |
| C053 | METHOD | web/src/pages/ManagerTendencies.tsx:155 | manager has a private note (tooltip = note) | “{note}” |
| C054 | METHOD | web/src/pages/ManagerTendencies.tsx:368 | PageHeader sub, always | What a manager's own draft history says, fitted by the engine — reach bias and unpredictability aren't something you type in, only something you can watch. A note is a reminder for yourself: only you can see it, and it never changes how a mock or live sim drafts. |
| C055 | METHOD | web/src/pages/ManagerTendencies.tsx:373-374 | always | Football and basketball are fitted separately and listed together; the same person appears once per sport. Only managers who share a league with you are listed. |
| C056 | METHOD | web/src/pages/ManagerTendencies.tsx:380-383 | always (reach-note) | Reach is measured against the other managers in the same draft, not the market board. The board itself runs several picks off for every room, so an absolute figure would mostly measure that. The shaded band is one standard error: with about 15 picks per draft most managers sit inside it, and that reads as “drafts like the room”. |
| C057 | EMPTY | web/src/pages/ManagerTendencies.tsx:391-392 | no managers to show | No managers loaded yet. / No {SPORT} managers with anything to show yet. |
| C058 | EMPTY | web/src/pages/ManagerTendencies.tsx:398-400 | hidden empty profiles > 0 | {hidden} more profile(s) is/are empty — managers with no drafts and nothing entered in that sport. Every manager gets a profile per sport whether or not they play it. |
| C059 | EMPTY | web/src/pages/ManagerTendencies.tsx:386 | fetch error | {error message} |

## ManagerHistory.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C060 | METHOD | web/src/pages/ManagerHistory.tsx:121-128 | rail sub, per sport with a career | {SPORT} {seasonsCounted} (count of counted seasons per sport, never combined across sports) |
| C061 | METHOD | web/src/pages/ManagerHistory.tsx:189-193 | PageHeader sub, one clause per sport | {SPORT} {w}-{l}[-{t}] across {seasonsCounted} season(s)[ · {titles} title(s)] |
| C062 | EMPTY | web/src/pages/ManagerHistory.tsx:61 | loading | Loading manager history… |
| C063 | METHOD | web/src/pages/ManagerHistory.tsx:232 | season row champion tooltip | Champion |
| C064 | EMPTY | web/src/pages/ManagerHistory.tsx:245-249 | season row with missing league name/wins/losses/PF/PA | — |
| C065 | METHOD | web/src/pages/ManagerHistory.tsx:272-275 | always (drafting panel) | Fitted from their own draft history, the same numbers the simulator uses -- not the record above, which is what actually happened on the scoreboard. |
| C066 | EMPTY | web/src/pages/ManagerHistory.tsx:277 | draftHistory empty | No drafts observed yet -- drafts like the room, no history to fit from. |
| C067 | CAVEAT | web/src/pages/ManagerHistory.tsx:288-292 | draft history sport with picksScored > 0 | Reach vs. their draft room: {read.text or —} over {n} draft(s) ({provenance lowercased: neutral\|stated\|fitted\|blended}) |
| C068 | EMPTY | web/src/pages/ManagerHistory.tsx:300 | draft history sport with picksScored == 0 | {n} draft(s) observed, no reach number |
| C069 | EMPTY | web/src/pages/ManagerHistory.tsx:305 | picksScored === 0 | {reachGapText(h)} |
| C070 | METHOD | web/src/pages/ManagerHistory.tsx:310 | positional tilt present | {pos} {tilt}× · ... |
| C071 | METHOD | web/src/pages/ManagerHistory.tsx:349-355 | CareerPanel, always | Every figure below comes from the same per-week optimal-lineup computation the Roster management page uses -- never Sleeper's own stored season potential, which reads more flattering for the same manager-season. Each figure states the seasons it covers: with one or two played seasons per manager in this database, an unlabeled average would read as a career number that is really a single season's. |
| C072 | EMPTY | web/src/pages/ManagerHistory.tsx:377 | winRate null | no games played yet |
| C073 | EMPTY | web/src/pages/ManagerHistory.tsx:385 | pointsPerSeason null | no counted season yet |
| C074 | METHOD | web/src/pages/ManagerHistory.tsx:386 | pointsPerSeason present | {pointsFor} for, {pointsAgainst} against |
| C075 | EMPTY | web/src/pages/ManagerHistory.tsx:398 | averageEfficiency null | no scored week with a per-player breakdown yet |
| C076 | CAVEAT | web/src/pages/ManagerHistory.tsx:399-402 | averageEfficiency present (excluded part only if weeksExcluded > 0) | {weeksCounted} week(s) counted[, {weeksExcluded} excluded for want of a per-player breakdown] |
| C077 | EMPTY | web/src/pages/ManagerHistory.tsx:414 | winsAboveExpected null | no counted season produced a computable figure |
| C078 | CAVEAT | web/src/pages/ManagerHistory.tsx:532 | every CareerStat | over {seasonsCounted} season(s) |
| C079 | METHOD | web/src/pages/ManagerHistory.tsx:454 | waiver Moves per season stat, always | waiver claims and free-agent adds, whatever the outcome -- trades cannot be attributed to one manager (see below) |
| C080 | METHOD | web/src/pages/ManagerHistory.tsx:462 | FAAB present: Typical FAAB bid | of that season's own starting budget -- a dollar figure does not compare across seasons of different budgets |
| C081 | METHOD | web/src/pages/ManagerHistory.tsx:468 | FAAB present: Largest FAAB bid | of that season's own starting budget |
| C082 | METHOD | web/src/pages/ManagerHistory.tsx:475-476 | FAAB present: FAAB spent per season | {claimsPerSeason} bid(s) per season, {bidSuccessRate}% won |
| C083 | EMPTY | web/src/pages/ManagerHistory.tsx:484 | no FAAB data in any counted season | No FAAB bids in any counted season -- see below for which seasons used waiver priority instead. |
| C084 | CAVEAT | web/src/pages/ManagerHistory.tsx:491 | per FAAB-excluded season | {season} ({leagueName}): {reason from backend} |
| C085 | METHOD | web/src/pages/ManagerHistory.tsx:553-555 | per rank entry | {ordinal(position)} of {population} in {leagueName} |
| C086 | CAVEAT | web/src/pages/ManagerHistory.tsx:575 | per unavailable figure (Playoff appearances / Trades per season) | {figure label}: {reason from backend} |

## ManagerComparison.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C087 | METHOD | web/src/pages/ManagerComparison.tsx:79 | PageHeader sub, always | The head-to-head record and the same career figures each manager's own history page shows, side by side. |
| C088 | EMPTY | web/src/pages/ManagerComparison.tsx:87 | data.sharedNothing | {aName} and {bName} have never shared a league season in either sport -- there is nothing to compare. |
| C089 | EMPTY | web/src/pages/ManagerComparison.tsx:113-114 | sport shared but games === 0 | {aName} and {bName} shared this sport, but the two have never actually been scheduled against each other with a scored week -- see the seasons below. |
| C090 | METHOD | web/src/pages/ManagerComparison.tsx:118 | games > 0 | Head to head across {games} played game(s). |
| C091 | EMPTY | web/src/pages/ManagerComparison.tsx:198-199 | comparison figure null for a side | — |
| C092 | CAVEAT | web/src/pages/ManagerComparison.tsx:179-180 | comparison table, always | {aName}: {seasonsCounted.a ?? 0} season(s) counted · {bName}: {seasonsCounted.b ?? 0} season(s) counted |
| C093 | CAVEAT | web/src/pages/ManagerComparison.tsx:235 | meeting with winner TIE | Tied -- counted as neither a win nor a loss. |
| C094 | CAVEAT | web/src/pages/ManagerComparison.tsx:255 | per excluded season | {season} ({leagueName}): {reason from backend} |
| C095 | EMPTY | web/src/pages/ManagerComparison.tsx:55 | loading | Loading comparison… |

## MockSetup.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C096 | CAVEAT | web/src/pages/MockSetup.tsx:15 | seat dropdown option, NEUTRAL manager |  (no data -- same as a bot) |
| C097 | CAVEAT | web/src/pages/MockSetup.tsx:16 | seat dropdown option, STATED manager |  (your call, no history) |
| C098 | CAVEAT | web/src/pages/MockSetup.tsx:17 | seat dropdown option, FITTED manager |  (from history) |
| C099 | CAVEAT | web/src/pages/MockSetup.tsx:18 | seat dropdown option, BLENDED manager |  (your call + history) |
| C100 | METHOD | web/src/pages/MockSetup.tsx:159-161 | PageHeader sub; handoff has a source league name | Using {sourceLeagueName}'s settings — {teams} teams[, {rounds} rounds]. Bots fill every seat but yours and auto-pick down the draft order. |
| C101 | METHOD | web/src/pages/MockSetup.tsx:164 | sub; no source league | Bots fill every seat but yours and auto-pick down the draft order. You take your own picks on your turn. |
| C102 | METHOD | web/src/pages/MockSetup.tsx:167 | sub; at least one manager has non-NEUTRAL provenance | Assign a real manager to a seat to see their tendencies play out instead of a league-average bot. |
| C103 | EMPTY | web/src/pages/MockSetup.tsx:170-171 | sub; no manager is fitted/stated | No {SPORT} manager has enough drafted history to model yet, so every seat drafts league-average either way. |
| C104 | EMPTY | web/src/pages/MockSetup.tsx:216 | managers still loading | Loading managers you can seat… |
| C105 | METHOD | web/src/pages/MockSetup.tsx:144 | seat summary line, always | {n} real manager(s) · {n} bot(s) · you at {round.pick} |
| C106 | METHOD | web/src/pages/MockSetup.tsx:220 | seat strip aria-label (not visible, accessible) | Draft seats -- click one to make it yours |

## MockDraftView.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C107 | METHOD | web/src/pages/MockDraftView.tsx:37-45 | toBoardSeat: every mock seat is given NEUTRAL provenance, reach null, 0 picks scored, and hideProvenanceDots is set (no provenance labelling in mock board) | (no text; provenance dots hidden via hideProvenanceDots) |
| C108 | METHOD | web/src/pages/MockDraftView.tsx:133-135 | mock forked from a live draft (sourceDraftId != null) | Forked from a live draft, continuing from pick {forkedAtPickNo}. |
| C109 | EMPTY | web/src/pages/MockDraftView.tsx:88 | fetch/pick error | {error message} |

## DraftView.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C110 | CAVEAT | web/src/pages/DraftView.tsx:271 | resim returned board that did not preserve a decided pick | Resimulation didn't preserve pick {n} as decided — showing the prior board. |
| C111 | METHOD | web/src/pages/DraftView.tsx:463 | gear chip tooltip | Simulation settings |
| C112 | CAVEAT | web/src/pages/DraftView.tsx:486 | seatsDirty and a result exists | Seats changed since this simulation ran — the board is out of date. |
| C113 | METHOD | web/src/pages/DraftView.tsx:522 | OnTheClock idle label when reveal finished | Every pick simulated |
| C114 | METHOD | web/src/pages/DraftView.tsx:535 | resimming after the user's pick | Recalculating the board past pick {pausedAt}... {pct}% |
| C115 | METHOD | web/src/pages/DraftView.tsx:592-593 | first run in progress | Simulating your draft... {pct}% |
| C116 | METHOD | web/src/pages/DraftView.tsx:600 | pre-start; seat auto-adopted from Sleeper | We found your seat — you're slot {mySlot}. Start the mock draft. |
| C117 | METHOD | web/src/pages/DraftView.tsx:601 | pre-start; seat not known | Click your name in the board above if you know your seat, then start the mock draft. |
| C118 | EMPTY | web/src/pages/DraftView.tsx:611 | pre-start, always | Board looks empty? Load this league's players first. (link to /) |
| C119 | METHOD | web/src/pages/DraftView.tsx:714-718 | settings chaos slider value label | most likely board (temperature 0) / realistic ({t}) (<=1.2) / chaos ({t}) |
| C120 | METHOD | web/src/pages/DraftView.tsx:722 | settings open while a run or resim is in flight | Applies to the next run. |
| C121 | METHOD | web/src/pages/DraftView.tsx:749-753 | snake reversal select, Follow Sleeper option | Follow Sleeper — flips from round {n} / — never flips |
| C122 | METHOD | web/src/pages/DraftView.tsx:755-761 | snake reversal select options | Never flips / Flips from round {n} |
| C123 | CAVEAT | web/src/pages/DraftView.tsx:768 | reversal save failed | {reversalError message} |
| C124 | CAVEAT | web/src/pages/DraftView.tsx:771-776 | settings open, reversalRound === 0 / > 0 | Plain snake: 1…12, 12…1, 1…12. Every football draft here is this. \| Rounds 1–{r-1} snake normally; from round {r} on, each round starts where a plain snake would have ended — Sleeper’s “reversal round”. Nothing in this app has ever run against a real draft that does this. |
| C125 | CAVEAT | web/src/pages/DraftView.tsx:777-780 | reversalRoundOverridden |  You set this; Sleeper said it never flips. / it flips from round {n}. |
| C126 | METHOD | web/src/pages/DraftView.tsx:790 | settings modal, always | Starts over with the settings above. |
| C127 | METHOD | web/src/pages/DraftView.tsx:496-500 | (comment says each board cell's title attr explains cell format; see DraftBoard.tsx - not in scan list) | (not recorded; DraftBoard.tsx is out of the requested scope) |

## LiveDraftView.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C128 | METHOD | web/src/pages/LiveDraftView.tsx:467 | newest landed pick completes that seat's roster and roster positions known | Fills {slot} / Depth (pick fit badge in feed) |
| C129 | METHOD | web/src/pages/LiveDraftView.tsx:642-644 | pick card, pick's position row is running or scarce (leftNow <= SCARCE_LEFT) | {pos}: {leftNow} of {poolSize} starter-pool players left[ · {count} of the last {window} were {pos}] |
| C130 | EMPTY | web/src/pages/LiveDraftView.tsx:722-724 | waiting overlay title | Connecting… / Not connected / Waiting for the draft to start |
| C131 | EMPTY | web/src/pages/LiveDraftView.tsx:731 | live null but stream connected | Opened the live stream, waiting for the first state frame. |
| C132 | EMPTY | web/src/pages/LiveDraftView.tsx:732 | live null and stream not connected | The live stream isn't answering — the backend may not have this endpoint yet. Retrying every few seconds; seats and the board below are still real. |
| C133 | EMPTY | web/src/pages/LiveDraftView.tsx:734 | live, seatsMapped === 0 | Waiting for the commissioner to set the draft order. |
| C134 | METHOD | web/src/pages/LiveDraftView.tsx:735 | live, seats mapped | {n} seats mapped[ · starts {time}] |
| C135 | METHOD | web/src/pages/LiveDraftView.tsx:777 | slot known and roster template known | {startersSet} of {n} starters (Your team) |
| C136 | METHOD | web/src/pages/LiveDraftView.tsx:805 | resimulating | Simulating the rest of the draft… {pct}% |
| C137 | CAVEAT | web/src/pages/LiveDraftView.tsx:812 | result and live, picksMade >= totalPicks | Every pick is in — nothing left to project |
| C138 | CAVEAT | web/src/pages/LiveDraftView.tsx:813 | result and live, picks remaining | Picks after {round.pick} are projected |
| C139 | CAVEAT | web/src/pages/LiveDraftView.tsx:814 | result exists but no live state | Projected from scratch — no live position to project from |
| C140 | EMPTY | web/src/pages/LiveDraftView.tsx:815 | no result yet | No projection yet |
| C141 | CAVEAT | web/src/pages/LiveDraftView.tsx:819 | result exists |  · slot {mySlot}[ (assumed — click your seat) if seat not known] |
| C142 | METHOD | web/src/pages/LiveDraftView.tsx:826-830 | announce toggle tooltip (speech supported) | Stop reading picks out loud / Read each pick out loud, and chime when your turn comes up |
| C143 | METHOD | web/src/pages/LiveDraftView.tsx:839-843 | pick cards toggle tooltip | Stop showing a card for each pick as it lands / Show a card for each pick as it lands: how it fits that roster |
| C144 | METHOD | web/src/pages/LiveDraftView.tsx:851 | Project again tooltip | Re-simulate the rest of the draft from where it stands now |
| C145 | METHOD | web/src/pages/LiveDraftView.tsx:859 | Continue as a mock tooltip | Start an interactive mock draft picking up from where this live draft is right now |
| C146 | METHOD | web/src/pages/LiveDraftView.tsx:908 | waiting overlay while resimming | Projecting the board meanwhile… {pct}% |
| C147 | EMPTY | web/src/pages/LiveDraftView.tsx:741-742 | error / liveError | {error message} / {liveError message} |

## LeagueHistory.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C148 | EMPTY | web/src/pages/LeagueHistory.tsx:57 | Record book, no highest/lowest weeks | No weekly scores have been loaded for this league's seasons yet, so there are no records to show. |
| C149 | EMPTY | web/src/pages/LeagueHistory.tsx:116-117 | Matchup margins empty | {marginsUnavailableReason from backend} else: Head-to-head pairings aren't available for this league's seasons. |
| C150 | METHOD | web/src/pages/LeagueHistory.tsx:140 | every margin card | Margin |
| C151 | EMPTY | web/src/pages/LeagueHistory.tsx:183-184 | points leaders empty | No weekly scores are stored for this league's seasons yet, so there's no all-time leaderboard to show. |
| C152 | CAVEAT | web/src/pages/LeagueHistory.tsx:202 | each all-time points leader row | over {n} season(s) |
| C153 | EMPTY | web/src/pages/LeagueHistory.tsx:231-232 | Streaks empty | {marginsUnavailableReason from backend} else: Head-to-head pairings aren't available for this league's seasons. |
| C154 | CAVEAT | web/src/pages/LeagueHistory.tsx:243 | Streaks panel, all streaks within-season | Longest run within a single season — a streak never carries across the offseason into the next year. |
| C155 | CAVEAT | web/src/pages/LeagueHistory.tsx:244 | Streaks panel, some streak crosses seasons | Some streaks below cross a season boundary; each row states its own span. |
| C156 | CAVEAT | web/src/pages/LeagueHistory.tsx:273 | streak row not withinSeasonOnly |  (crosses seasons) |
| C157 | CAVEAT | web/src/pages/LeagueHistory.tsx:291 | rank cell, rankStatus IN_PROGRESS | season in progress |
| C158 | CAVEAT | web/src/pages/LeagueHistory.tsx:292 | rank cell, rankStatus NOT_COMPUTED (plus Compute button) | not computed yet |
| C159 | CAVEAT | web/src/pages/LeagueHistory.tsx:293 | rank cell, rankStatus UNAVAILABLE (or RANKED without finalRank) | no weekly scores stored |
| C160 | CAVEAT | web/src/pages/LeagueHistory.tsx:307 | rank cell RANKED, title tooltip | Through week {finalRankWeek} |
| C161 | METHOD | web/src/pages/LeagueHistory.tsx:350 | Rank column header tooltip | End-of-season power rank |
| C162 | METHOD | web/src/pages/LeagueHistory.tsx:362 | champion marker tooltip | Champion |
| C163 | EMPTY | web/src/pages/LeagueHistory.tsx:374 | standings row with no manager | roster {rosterId} (unowned) |
| C164 | EMPTY | web/src/pages/LeagueHistory.tsx:378-382 | standings value null | — |
| C165 | METHOD | web/src/pages/LeagueHistory.tsx:459 | PageHeader sub, always | Standings as Sleeper reports them — wins, losses, points, the champion. What this app thinks about a draft (reach, value) lives on a manager's own history page, kept visually separate from what actually happened. |
| C166 | EMPTY | web/src/pages/LeagueHistory.tsx:473 | fetch error and 404 | This league's past seasons haven't been loaded yet. (+ Load past seasons button) |
| C167 | EMPTY | web/src/pages/LeagueHistory.tsx:486 | loading | Loading league history… |
| C168 | EMPTY | web/src/pages/LeagueHistory.tsx:491 | history.seasons empty | No seasons loaded for this league yet. |
| C169 | EMPTY | web/src/pages/LeagueHistory.tsx:503 | season has no standings | No standings for {season} yet — Sleeper reports them once the season is under way. |

## PowerRankings.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C170 | CAVEAT | web/src/pages/PowerRankings.tsx:36-37 | BALLOT_HONOUR_NOTE; shown wherever ballots are described | Ballots aren't verified: anyone who knows a member's Sleeper name could submit as them, so this runs on trust. |
| C171 | METHOD | web/src/pages/PowerRankings.tsx:80 | KIND_CAVEAT for Box score kind | Box score: cumulative average starting-lineup points per week, from games already played. A hot start survives an injury it should not. Preseason is the exception -- a one-time baseline (this app's board value on each roster's best starting lineup, at first compute), not a played week. |
| C172 | CAVEAT | web/src/pages/PowerRankings.tsx:81 | KIND_CAVEAT for Commissioner kind | The commissioner's own ordering. An opinion, signed -- not a measurement. |
| C173 | METHOD | web/src/pages/PowerRankings.tsx:83 | KIND_CAVEAT for League vote kind | League vote: every manager who submitted a ballot this week, averaged. Managers rank their own team too, and the bias that produces is shown below rather than quietly removed. |
| C174 | METHOD | web/src/pages/PowerRankings.tsx:63-66 | kind labels | Box score / Commissioner / League vote |
| C175 | METHOD | web/src/pages/PowerRankings.tsx:148-158 | week phrasing helpers | Preseason / week {n} / preseason / wk {n} / weeks {a}–{b} |
| C176 | CAVEAT | web/src/pages/PowerRankings.tsx:163 | recordLabel with no record | -- |
| C177 | CAVEAT | web/src/pages/PowerRankings.tsx:190 | roomTakeSentence: no best/worst rank | {n} ballot(s) counted / Not yet ranked |
| C178 | CAVEAT | web/src/pages/PowerRankings.tsx:192-195 | roomTakeSentence by spread of ballot ranks | Ranked {ord} on every ballot / {ord} or {ord} on every ballot / {ord} to {ord} — no consensus / Ranked {ord} to {ord}, {n} ballots |
| C179 | METHOD | web/src/pages/PowerRankings.tsx:200-201 | ladder note: Commissioner mode / Box score mode | Signed, {wk} / As of {wk} |
| C180 | CAVEAT | web/src/pages/PowerRankings.tsx:243 | spaceStatsFor consensus bucket (agreePct >=70 tight, >=40 mixed, else split) | tight / mixed / split (consensus value; display text defined where rendered) |
| C181 | METHOD | web/src/pages/PowerRankings.tsx:244 | delta vs my rank | even / +{n} / -{n} |
| C182 | METHOD | web/src/pages/PowerRankings.tsx:321-330 | buildHeadline | {Week N\|Preseason} power rankings / {name} is your new No. 1 / {name} jumps {n} spots to {ord} / {name} falls {n} spots to {ord} / Nobody agrees on {name} |
| C183 | CAVEAT | web/src/pages/PowerRankings.tsx:335 | buildDeck, always | {ballotCount} of {memberCount} ballot(s) in for {week phrase}. |
| C184 | METHOD | web/src/pages/PowerRankings.tsx:344-350 | buildDeck second clause | {name} climbed {n} spot(s). / {name} dropped {n} spot(s). / {name} splits the room, {ord} to {ord}. |
| C185 | METHOD | web/src/pages/PowerRankings.tsx:381-382 | scoreLabel | avg rank {score} (member) / lineup value {n} (preseason box score) / {score} pts (box score) |
| C186 | EMPTY | web/src/pages/PowerRankings.tsx:442 | power rankings response lacks sportState | This league’s power rankings came back in a format this page cannot read — the server is running an older build than the site. Redeploy the backend. |
| C187 | METHOD | web/src/pages/PowerRankings.tsx:545 | commissioner ranking saved | Saved {week} |
| C188 | METHOD | web/src/pages/PowerRankings.tsx:560 | ballot submitted | Ballot submitted for {week} |
| C189 | EMPTY | web/src/pages/PowerRankings.tsx:577 | loading | Loading power rankings… |
| C190 | METHOD | web/src/pages/PowerRankings.tsx:636 | seed for commissioner/ballot ordering (label of seed source) | the member list / the box score / the league vote |
| C191 | METHOD | web/src/pages/PowerRankings.tsx:658 | ladder column head when not member-space and a reference exists | Since {wk n-1} / Since — |
| C192 | METHOD | web/src/pages/PowerRankings.tsx:707-708 | oddsSummary (data.playoffOdds) present; oddsNote shown wherever rendered below | Playoff odds are {iterations} simulated seasons against the real remaining schedule, from {weeksOfScoring} week(s) of scoring. |
| C193 | METHOD | web/src/pages/PowerRankings.tsx:781 | eyebrow, always | {Week N\|Preseason} · {leagueName\|League} · {teamCount} teams |
| C194 | CAVEAT | web/src/pages/PowerRankings.tsx:789 | hero deck, always | {buildDeck output: "N of M ballots in for week X." + optional riser/faller/divisive clause} |
| C195 | CAVEAT | web/src/pages/PowerRankings.tsx:792 | hero pill, always | {heroBallots} of {memberCount} ballots in · {week phrase} |
| C196 | CAVEAT | web/src/pages/PowerRankings.tsx:796 | a later week is collecting ballots (currentWeek != heroWeek) | {ballotCount} of {memberCount} in · {week phrase} open |
| C197 | METHOD | web/src/pages/PowerRankings.tsx:804 | #1 card, always | Number one |
| C198 | METHOD | web/src/pages/PowerRankings.tsx:814 | #1 card meta, score label | avg rank {score} |
| C199 | METHOD | web/src/pages/PowerRankings.tsx:823-828 | #1 card This week stat, ref rank exists | This week: – / ▲ n / ▼ n |
| C200 | CAVEAT | web/src/pages/PowerRankings.tsx:834-835 | #1 card Makes playoffs stat; pctLabel gives -- when null | Makes playoffs {pct}% / -- |
| C201 | METHOD | web/src/pages/PowerRankings.tsx:851 | your-team strip, delta nonzero |  · the room moved you up\|down {n} |
| C202 | CAVEAT | web/src/pages/PowerRankings.tsx:855 | your-team strip, makesPlayoffsPct non-null |  · {pct}% to make it |
| C203 | METHOD | web/src/pages/PowerRankings.tsx:860 | signed-in member who ballot-ranked self | You voted yourself {ord}. |
| C204 | METHOD | web/src/pages/PowerRankings.tsx:870-894 | story cards | Riser of the week / Now {ord}, from {ord}. \| Free fall / Now {ord}, from {ord}. \| Nobody agrees / Ranked as high as {ord} and as low as {ord}. |
| C205 | METHOD | web/src/pages/PowerRankings.tsx:908-909 | ladder head sub | {kind label} · {week phrase}[ · {n} ballots if member mode] |
| C206 | METHOD | web/src/pages/PowerRankings.tsx:933 | Recompute button tooltip (commissioner only) | Recompute box-score rankings for the current week (and the preseason baseline, the first time) and save them |
| C207 | METHOD | web/src/pages/PowerRankings.tsx:935 | commissioner recompute button | Computing… / Recompute {week} (commissioner) |
| C208 | CAVEAT | web/src/pages/PowerRankings.tsx:949-950 | ladder shows an earlier week than currentWeek | Showing {week} — the latest {kind} week there is. Nothing for {week} yet. |
| C209 | EMPTY | web/src/pages/PowerRankings.tsx:956 | ladder has no rows | No {kind} snapshots yet for this league. |
| C210 | EMPTY | web/src/pages/PowerRankings.tsx:959 | no box-score rows; viewer can commission | Use "Recompute {week}" above to build the first one. |
| C211 | EMPTY | web/src/pages/PowerRankings.tsx:960 | no box-score rows; viewer cannot commission | Ask the commissioner to run the first box-score snapshot. |
| C212 | EMPTY | web/src/pages/PowerRankings.tsx:962 | no league-vote rows | Ballots build this one -- submit yours above, and the room fills in as others do. |
| C213 | METHOD | web/src/pages/PowerRankings.tsx:973-988 | member-space ladder column heads | Avg rank / Your ballot / Where the room had them (axis: 1st · halfway · {n}th) / Ballot range |
| C214 | CAVEAT | web/src/pages/PowerRankings.tsx:1049 | odds pill tooltip | {pct}% to make the playoffs |
| C215 | CAVEAT | web/src/pages/PowerRankings.tsx:1059 | member-space row, viewer has no ballot rank | — (viewer has a ballot but not this team) / No ballot |
| C216 | CAVEAT | web/src/pages/PowerRankings.tsx:1069,1080 | member-space spread bar and range pill tooltips | {roomTakeSentence(e)} |
| C217 | METHOD | web/src/pages/PowerRankings.tsx:1093 | non-member ladder row note | {ladderNoteFor: Signed, {wk} / As of {wk}} |
| C218 | CAVEAT | web/src/pages/PowerRankings.tsx:1101-1103 | ladder footer, MEMBER mode | A team ranked by fewer than half this week's ballots is placed after every team that cleared that bar, rather than winning the week on one enthusiastic vote. |
| C219 | METHOD | web/src/pages/PowerRankings.tsx:1109-1118 | member-space legend | ballot range, best to worst / room median / your ballot / halfway line |
| C220 | METHOD | web/src/pages/PowerRankings.tsx:1123-1125 | ladder footer, always (also repeated at sidebar 1443-1445) | Ranks are manager ballots, averaged. {oddsNote} [How this works →] |
| C221 | METHOD | web/src/pages/PowerRankings.tsx:1130 | How this works disclosure open | {KIND_CAVEAT for ladderMode} |
| C222 | METHOD | web/src/pages/PowerRankings.tsx:1132-1137 | How this works disclosure open | Movement compares this mode against {reference kind} for the same week, when that mode has a snapshot for it -- otherwise the column is hidden rather than showing a fake zero. Playoff odds are a team-level simulation: each roster's weekly scoring, shrunk toward the league average by how few games it stands on, played out over the real remaining schedule. It knows nothing about injuries, byes or trades, and a league whose playoff seeding this app does not model (divisions, a custom seed type) shows "--" rather than a number that would be quietly wrong. |
| C223 | EMPTY | web/src/pages/PowerRankings.tsx:1162 | compare open, no snapshots for team | No snapshots yet for this team in any mode. |
| C224 | METHOD | web/src/pages/PowerRankings.tsx:1167-1173 | compare open | Every mode with data has {name} {ord}. / {n} places between the highest and lowest read on {name}. {kind ord, ...}. |
| C225 | METHOD | web/src/pages/PowerRankings.tsx:1178-1181 | compare table heads | Mode / Rank / Placement, 1st → {n}th / Score |
| C226 | EMPTY | web/src/pages/PowerRankings.tsx:1189 | no entry for a mode | -- |
| C227 | METHOD | web/src/pages/PowerRankings.tsx:1199 | compare axis dot tooltip | {kind} · {no week\|week phrase} · rank {n} |
| C228 | EMPTY | web/src/pages/PowerRankings.tsx:1203 | no score for a mode | no score |
| C229 | METHOD | web/src/pages/PowerRankings.tsx:1236 | Trend panel, chart ready (>=2 weeks) | {kind}, {week range} |
| C230 | EMPTY | web/src/pages/PowerRankings.tsx:1238 | Trend panel, exactly 1 week | One week of data so far. |
| C231 | EMPTY | web/src/pages/PowerRankings.tsx:1239 | Trend panel, 0 weeks | No weeks yet for this mode. |
| C232 | METHOD | web/src/pages/PowerRankings.tsx:1293 | Homers panel head, homers exist | self vs. room, every manager |
| C233 | METHOD | web/src/pages/PowerRankings.tsx:1300-1302 | Homers disclosure open | Where a manager put their own team, against where the room put it. Negative means they rank themselves higher than everyone else does. |
| C234 | METHOD | web/src/pages/PowerRankings.tsx:1339-1340 | Homer of the week | Ranked their own team {n} spot(s) higher\|lower than the room did. |
| C235 | EMPTY | web/src/pages/PowerRankings.tsx:1370 | no commissioner known | No commissioner is recorded for this league yet. It is read from Sleeper whenever the league refreshes. |
| C236 | METHOD | web/src/pages/PowerRankings.tsx:1375 | commissioner has a top team | {note} or {name} tops the commissioner's board this week. |
| C237 | METHOD | web/src/pages/PowerRankings.tsx:1378 | commissioner top | Signed, {week phrase} · full order |
| C238 | EMPTY | web/src/pages/PowerRankings.tsx:1387 | commissioner hasn't ranked; viewer can commission | You haven't set a ranking for {week} yet. |
| C239 | EMPTY | web/src/pages/PowerRankings.tsx:1388 | commissioner hasn't ranked; viewer cannot | The commissioner hasn't ranked {week} yet. |
| C240 | METHOD | web/src/pages/PowerRankings.tsx:1399 | blockState ok and ballot exists | In for {week phrase}. You can resubmit until kickoff. |
| C241 | EMPTY | web/src/pages/PowerRankings.tsx:1403 | blockState ok, no ballot | Nothing submitted yet for {week phrase}. |
| C242 | CAVEAT | web/src/pages/PowerRankings.tsx:1405 | blockState ok | (BALLOT_HONOUR_NOTE) Ballots aren't verified: anyone who knows a member's Sleeper name could submit as them, so this runs on trust. |
| C243 | EMPTY | web/src/pages/PowerRankings.tsx:1406 | blockState loading | Loading your ballot… |
| C244 | EMPTY | web/src/pages/PowerRankings.tsx:1410-1411 | blockState signed-out | You're reading this league as a guest. Sign in as a member to put in a ballot -- the rankings stay visible either way. |
| C245 | EMPTY | web/src/pages/PowerRankings.tsx:1420-1421 | blockState not-member | Signed in as {user}, who isn't on a roster here. Rosters are read from Sleeper whenever the league refreshes, so if you just joined, check back shortly. |
| C246 | EMPTY | web/src/pages/PowerRankings.tsx:1425 | blockState voting-closed | Voting is closed for {week phrase}. |
| C247 | EMPTY | web/src/pages/PowerRankings.tsx:1429 | blockState load-error | We couldn't reach your ballot just now. |
| C248 | METHOD | web/src/pages/PowerRankings.tsx:1461-1466 | ballot modal head | {Week N} commissioner ranking / Your {week} ballot; {ballotCount} of {memberCount} in |
| C249 | CAVEAT | web/src/pages/PowerRankings.tsx:1474 | commissioner ballot modal | Your own ordering. An opinion, signed -- not a measurement. |
| C250 | METHOD | web/src/pages/PowerRankings.tsx:1486 | commissioner modal seeded from fallback | Starting order: {seed source}. Drag by the grip, or tap a team and use the arrows -- nothing is saved until you submit. |
| C251 | EMPTY | web/src/pages/PowerRankings.tsx:1494 | non-commissioner in commissioner mode, commissioner known | Only this league's commissioner can set this ranking. |
| C252 | EMPTY | web/src/pages/PowerRankings.tsx:1495 | commissioner unknown | No commissioner is recorded for this league yet. It is read from Sleeper whenever the league refreshes. |
| C253 | CAVEAT | web/src/pages/PowerRankings.tsx:1501-1503 | member ballot modal, blockState ok | Drag a team by the grip on its right, or tap it and use the arrows that appear. Your own team is in the list; we show the bias rather than removing it. Nothing saves until you submit. |
| C254 | METHOD | web/src/pages/PowerRankings.tsx:1522 | ballot modal seeded from fallback | Starting order: {seed source}. Drag by the grip, or tap a team and use the arrows -- nothing is saved until you submit. |
| C255 | METHOD | web/src/pages/PowerRankings.tsx:1526 | ballot modal, ok | Drag by the grip, or tap a team and use ↑ / ↓ to move it. Escape drops the selection. |
| C256 | CAVEAT | web/src/pages/PowerRankings.tsx:1528 | ballot modal, ok | (BALLOT_HONOUR_NOTE) Ballots aren't verified: anyone who knows a member's Sleeper name could submit as them, so this runs on trust. |
| C257 | EMPTY | web/src/pages/PowerRankings.tsx:1534-1556 | ballot modal non-ok states (same copy as sidebar block states): signed-out, not-member, voting-closed, load-error, loading | (duplicates of rows for lines 1410-1429 and 1406) |

## LeagueAnalysis.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C258 | METHOD | web/src/pages/LeagueAnalysis.tsx:48-52 | scoring label (when displayed) | full PPR / half PPR / standard |
| C259 | EMPTY | web/src/pages/LeagueAnalysis.tsx:97 | NotYet block with no backend reason | Nothing to show yet. (otherwise {reason from backend}) |
| C260 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:83 | injury tag tooltip on lineup player | {injury status}; displayed as Q / D / raw status |
| C261 | METHOD | web/src/pages/LeagueAnalysis.tsx:141 | FormulaNote, always shown under ranking scores (both available and unavailable) | Score = average week × 6, plus (best week + worst week) × 2, plus win % × 400, all ÷ 10. |
| C262 | METHOD | web/src/pages/LeagueAnalysis.tsx:144-145 | FormulaNote details disclosure | ffwrapped's formula, as published: {formula} |
| C263 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:192 | ranking scores table row | {raw} raw (formula's own raw output beside the 1-100 scaled score) |
| C264 | METHOD | web/src/pages/LeagueAnalysis.tsx:254-257 | week strip | best wk {n} · {pts} · worst wk {n} · {pts} |
| C265 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:262 | week bar tooltip | Week {n}: {pts} projected |
| C266 | METHOD | web/src/pages/LeagueAnalysis.tsx:290,300 | lineup card | Starting lineup {n} slots / Bench {n} players |
| C267 | EMPTY | web/src/pages/LeagueAnalysis.tsx:303 | bench empty | Every rostered player is in the lineup. |
| C268 | METHOD | web/src/pages/LeagueAnalysis.tsx:363 | position segment tooltip | {pos}: {pts} projected points |
| C269 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:404-406 | roster has missing projections (missing > 0) | {n} unprojected; tooltip: Rostered players Sleeper publishes no projection for — IR, Out, PUP. They count as zero, and they are named on the bench below. |
| C270 | EMPTY | web/src/pages/LeagueAnalysis.tsx:466 | projected bump block, < 2 projected weeks | One projected week is a point, not a line. |
| C271 | METHOD | web/src/pages/LeagueAnalysis.tsx:531-532 | bump legend hint | Pin up to {cap} to compare them. / {n} of {cap} pinned. |
| C272 | METHOD | web/src/pages/LeagueAnalysis.tsx:548 | pin button disabled at cap | Three is the most that stay reliably distinguishable. Unpin one first. |
| C273 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:607 | position matrix cell tooltip | {team} — {pos}: {pts} projected points, {rank} of {teams} |
| C274 | EMPTY | web/src/pages/LeagueAnalysis.tsx:651 | matchup with no opponent (bye) | No opponent this week — a bye. |
| C275 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:702 | matchup margin == 0 | Dead level on projection. |
| C276 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:705-707 | matchup margin != 0 (deliberately not a win probability) | {team} projected ahead by {pts} |
| C277 | EMPTY | web/src/pages/LeagueAnalysis.tsx:811 | scores grid cell, no game scored | — ; tooltip: no game scored |
| C278 | METHOD | web/src/pages/LeagueAnalysis.tsx:820 | scores grid cell tooltip | {team} — week {n}: {pts}, {rank} of {n} |
| C279 | METHOD | web/src/pages/LeagueAnalysis.tsx:844-845 | scores block, >1 week | The same weeks as movement: where each roster ranked on points in each one. Your line is crimson; pin up to three to compare them. |
| C280 | EMPTY | web/src/pages/LeagueAnalysis.tsx:865-866 | scores block, only one scored week | One scored week is a column, not a line. The movement chart, and the total, average, high and low — which are all that same number until there are two weeks — appear from week two. |
| C281 | EMPTY | web/src/pages/LeagueAnalysis.tsx:898 | head-to-head slot with no player | nobody |
| C282 | EMPTY | web/src/pages/LeagueAnalysis.tsx:936 | fewer than 2 rosters | One roster is not a comparison. |
| C283 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:977-988 | head-to-head margin | Same roster on both sides. / level / {pts} ahead for {team} |
| C284 | METHOD | web/src/pages/LeagueAnalysis.tsx:1024-1025 | head-to-head, always | Difference is {left} minus {right}. |
| C285 | METHOD | web/src/pages/LeagueAnalysis.tsx:1038 | head-to-head position-group diff | level / +{n} |
| C286 | METHOD | web/src/pages/LeagueAnalysis.tsx:1112 | PageHeader sub, always | What each roster is made of: a composite ranking score from games already played, the rest of the regular season projected onto the lineup each manager would actually start, and next week's games read off those lineups. Who is best — the ladders, the vote, the bump chart — is Power rankings. |
| C287 | EMPTY | web/src/pages/LeagueAnalysis.tsx:1123 | loading | Loading league analysis… |
| C288 | METHOD | web/src/pages/LeagueAnalysis.tsx:1139-1141 | Ranking score panel, always | A composite of scoring and record over the {n} week(s) played, scaled 1–100 with the league average at 50. |
| C289 | METHOD | web/src/pages/LeagueAnalysis.tsx:1151-1152 | Roster projections head sub | weeks {from}–{to} · {full PPR\|half PPR\|standard\|raw scoringKey} |
| C290 | METHOD | web/src/pages/LeagueAnalysis.tsx:1156-1158 | Roster projections panel, always | Rest-of-season points for the starting lineup each roster would field, split by the position the starter actually plays — a running back filling a flex slot counts under RB. Open a lineup to see the players the number is made of. |
| C291 | METHOD | web/src/pages/LeagueAnalysis.tsx:1167 | Projected week by week head sub | weeks {from}–{to} |
| C292 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:1171-1173 | Projected week by week panel, always | Where each roster is projected to rank in each remaining week — the bye weeks are the drops. Click a line to follow it. This is the projection; what was actually scored is further down. |
| C293 | METHOD | web/src/pages/LeagueAnalysis.tsx:1183-1184 | Position group rankings panel, always | The same projections read down the columns: where each roster stands at each position. Big number is the rank, small number is the projected points behind it. |
| C294 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:1196 | matchups heading; names week only when matchups available (finished season refuses) | Week {n} matchups / Upcoming matchups |
| C295 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:1222-1224 | Matchups panel, always | The league's real pairings, each side's lineup rebuilt for the chosen week rather than sliced out of the rest-of-season total — a bye or a one-week injury moves who starts. The margin is two projections subtracted, not a win probability. |
| C296 | METHOD | web/src/pages/LeagueAnalysis.tsx:1235 | Week by week head sub | {n} week(s) scored |
| C297 | CAVEAT | web/src/pages/LeagueAnalysis.tsx:1239-1240 | Week by week (scored) panel, always | What every roster actually scored, week by week — played games, not projections, so nothing here moves once a week is in the books. |
| C298 | METHOD | web/src/pages/LeagueAnalysis.tsx:1249 | Head to head head sub | weeks {from}–{to} |
| C299 | METHOD | web/src/pages/LeagueAnalysis.tsx:1253 | Head to head panel, always | Any two rosters, slot against slot, over the same rest-of-season window. |

## RosterManagement.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C300 | METHOD | web/src/pages/RosterManagement.tsx:92 | PageHeader sub, always | What each team scored, against the most it could have scored if every weekly lineup had been perfect. Efficiency is the first divided by the second — it measures lineup decisions, not the roster. |
| C301 | EMPTY | web/src/pages/RosterManagement.tsx:101 | loading | Loading… |
| C302 | EMPTY | web/src/pages/RosterManagement.tsx:107-108 | data.available false | {reason from backend, else: No scored weeks for this league yet.} Efficiency needs at least one completed week — before that there is no lineup decision to grade. (heading: Nothing to measure yet) |
| C303 | METHOD | web/src/pages/RosterManagement.tsx:116 | data available | Season {season} · {n} week(s) scored |
| C304 | METHOD | web/src/pages/RosterManagement.tsx:128 | table head | Scored against potential, points |
| C305 | METHOD | web/src/pages/RosterManagement.tsx:183-186 | tx legend | Waiver claims / Free agents / Trades |
| C306 | EMPTY | web/src/pages/RosterManagement.tsx:193 | no trades | No trades have been made. |
| C307 | METHOD | web/src/pages/RosterManagement.tsx:222 | dropped player on a waiver add | for {player} |
| C308 | METHOD | web/src/pages/RosterManagement.tsx:229-231 | Waivers panel, always | Rank is the player's average finish among others at his position since the move — lower is better, and the number of weeks it covers is shown beside it, so a single week is not mistaken for a season. |
| C309 | CAVEAT | web/src/pages/RosterManagement.tsx:241-242 | add has no postMovePositionalRank | ungraded (tooltip: No week has been played since this move) |
| C310 | CAVEAT | web/src/pages/RosterManagement.tsx:249-253 | add has a post-move rank | {pos}{rank} avg over {n} wk (tooltip: Average finish among {pos} since the move. Lower is better.) |
| C311 | CAVEAT | web/src/pages/RosterManagement.tsx:282-283 | efficiency null for a team | — (tooltip: No scored week with a per-player breakdown yet) |
| C312 | METHOD | web/src/pages/RosterManagement.tsx:295 | bar caption | perfect / {n} left on the bench |
| C313 | CAVEAT | web/src/pages/RosterManagement.tsx:313-316 | any team has weeksExcluded | Week(s) {weeks} had no per-player scoring stored for {n} team(s), so it is/they are left out of potential rather than counted as zero. Efficiency for those teams covers the weeks that remain. |
| C314 | CAVEAT | web/src/pages/RosterManagement.tsx:118 | SeasonFallbackNote rendered when requestedSeason differs from season | (see SeasonFallbackNote.tsx row) |

## ExpectedWins.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C315 | METHOD | web/src/pages/ExpectedWins.tsx:65 | PageHeader sub, always | What each team's scoring would have earned against a random opponent every week, against what the schedule actually gave them. The gap is luck, not skill. |
| C316 | EMPTY | web/src/pages/ExpectedWins.tsx:74 | loading | Loading… |
| C317 | EMPTY | web/src/pages/ExpectedWins.tsx:80-81 | data.available false | {reason from backend, else: No completed games for this league yet.} Expected wins compares a team against the rest of the league in the same week, so it needs at least one played week. (heading: No games to measure yet) |
| C318 | METHOD | web/src/pages/ExpectedWins.tsx:90-91 | data available | Season {season} · {n} week(s) · league average {ppg} points per game |
| C319 | METHOD | web/src/pages/ExpectedWins.tsx:101 | table head | Luck, in wins above expected |
| C320 | METHOD | web/src/pages/ExpectedWins.tsx:102 | Schedule column header tooltip | Opponents' points per game minus the league's |
| C321 | METHOD | web/src/pages/ExpectedWins.tsx:115-117 | data available, always | Schedule is the average of a team's opponents' points per game minus the league-wide average. Positive means a harder schedule — they faced better scoring than everyone else did. |
| C322 | METHOD | web/src/pages/ExpectedWins.tsx:122-125 | Where the luck came from panel; only teams with \|wins above expected\| >= 0.25 get a card | Where the luck came from |
| C323 | METHOD | web/src/pages/ExpectedWins.tsx:199-200 | luck card, luckSource SWING_WEEKS | Week {n} won\|lost with {pts} points ({ord} that week) against {opponent} |
| C324 | METHOD | web/src/pages/ExpectedWins.tsx:220-222 | luck card no swing; \|SoS\| < 1 | No single week did this, and the schedule was close to average ({sos}). The gap came from close weeks near the middle of the league's scores. |
| C325 | METHOD | web/src/pages/ExpectedWins.tsx:227 | luck card no swing; schedule agrees with luck direction | No single week did this — their opponents averaged {n} more\|fewer points than the league ({sos}, harder\|easier schedule). |
| C326 | METHOD | web/src/pages/ExpectedWins.tsx:229 | luck card no swing; schedule opposes luck direction | No single week did this, and the schedule ran the other way ({sos}, harder\|easier). The edge\|shortfall came from winning\|losing close weeks near the middle of the league's scores. |

## SeasonForecast.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C327 | METHOD | web/src/pages/SeasonForecast.tsx:82 | PageHeader sub, always | The rest of the schedule, simulated from every team's scoring so far. These are the odds as of the last recompute — the page reads a stored simulation rather than running a new one. |
| C328 | EMPTY | web/src/pages/SeasonForecast.tsx:91 | loading | Loading… |
| C329 | METHOD | web/src/pages/SeasonForecast.tsx:108 | refusal NOT_COMPUTED with finalized weeks | Recompute through week {n} (commissioner-only control; plus CommissionerKeyNote) |
| C330 | EMPTY | web/src/pages/SeasonForecast.tsx:117-119 | available and season complete | Nothing left to forecast: The {season} season is over, so there is nothing left to forecast. How it ended — the champion and the final standings — is in History. |
| C331 | METHOD | web/src/pages/SeasonForecast.tsx:126 | forecast in progress | Through week {n} · {iterations} simulated seasons |
| C332 | METHOD | web/src/pages/SeasonForecast.tsx:137 | table head | Win range, 10th–90th percentile |
| C333 | CAVEAT | web/src/pages/SeasonForecast.tsx:150-152 | forecast available, always | Odds come from the snapshot taken at the last commissioner recompute, so they match the playoff odds shown on the power rankings exactly. A range is the middle 80% of simulated outcomes — a wide one means the schedule still decides this team's season. |
| C334 | EMPTY | web/src/pages/SeasonForecast.tsx:164-167 | SeasonOver notice (also inside Refusal when seasonComplete) | The {season} season is over, so there is nothing left to forecast. / How it ended — the champion and the final standings — is in History. |
| C335 | EMPTY | web/src/pages/SeasonForecast.tsx:193 | refusal heading | No forecast for this league |
| C336 | EMPTY | web/src/pages/SeasonForecast.tsx:187 | refusal reason UNMODELLED_SEEDING | This league seeds its playoffs in a way this app doesn't model — divisions, or a non-default seeding rule. Rather than show odds computed under the wrong bracket, it shows none. |
| C337 | EMPTY | web/src/pages/SeasonForecast.tsx:189 | refusal reason NOT_COMPUTED | This season has been played, but no odds have been computed for it yet. A commissioner recompute produces them — the page reads a stored simulation rather than running one on load. |
| C338 | EMPTY | web/src/pages/SeasonForecast.tsx:190 | refusal, other reason (no week scored) | No week has been scored yet, so there is nothing to project from. Odds appear once the first week is final. |
| C339 | CAVEAT | web/src/pages/SeasonForecast.tsx:221-222 | snapshot week is behind latestFinalWeek | {n} week(s) scored since this forecast. The odds below are as of week {snapshotWeek}. (+ Recompute through week {n} control) |
| C340 | CAVEAT | web/src/pages/SeasonForecast.tsx:249-250 | RecomputeControl hidden unless canCommission and on the requested season (absence is a condition) | (no text) |
| C341 | CAVEAT | web/src/pages/SeasonForecast.tsx:299 | team has no stored win distribution | no distribution stored |
| C342 | EMPTY | web/src/pages/SeasonForecast.tsx:302 | averageSeed null | — |

## WeeklyReport.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C343 | METHOD | web/src/pages/WeeklyReport.tsx:88 | PageHeader sub, always | Every matchup, the week's best performances, and the awards nobody wants — read back from what actually happened. |
| C344 | EMPTY | web/src/pages/WeeklyReport.tsx:113 | loading | Loading… |
| C345 | EMPTY | web/src/pages/WeeklyReport.tsx:118 | available false | No week has been scored yet / Week {n} has not been scored |
| C346 | EMPTY | web/src/pages/WeeklyReport.tsx:121 | available false | {reason from backend, else: No results stored for this week yet.} |
| C347 | CAVEAT | web/src/pages/WeeklyReport.tsx:130 | data available and week not final | In progress — scores can still change until the week closes. |
| C348 | METHOD | web/src/pages/WeeklyReport.tsx:134 | available | Week {n} matchups |
| C349 | EMPTY | web/src/pages/WeeklyReport.tsx:146 | no awards | Nobody qualified for an award this week. |
| C350 | CAVEAT | web/src/pages/WeeklyReport.tsx:163-166 | award omitted | {award title} could not be worked out for this week: this week was stored before the app recorded which players were actually started, so naming the swap would be a guess. (STARTERS_NOT_STORED) / {raw reason} |
| C351 | METHOD | web/src/pages/WeeklyReport.tsx:323-326 | award titles | Got away with it / Deserved better / One-player carry / Self-inflicted wound |
| C352 | METHOD | web/src/pages/WeeklyReport.tsx:214 | Rankings (players play multiple per period), Best nights panel | The biggest single games anyone rostered this week. |
| C353 | CAVEAT | web/src/pages/WeeklyReport.tsx:242 | basis === ALL_GAMES_PLAYED, Best week panel | Every game played, added up — real production, not the points that decided a matchup. |
| C354 | CAVEAT | web/src/pages/WeeklyReport.tsx:257 | Best week row | {n} game(s) (denominator beside total) |
| C355 | EMPTY | web/src/pages/WeeklyReport.tsx:274 | section reason PER_GAME_DETAIL_MISSING | Game-by-game detail has not been stored for this week yet, so this ranking cannot be built. It is not that nobody played. |
| C356 | EMPTY | web/src/pages/WeeklyReport.tsx:275 | section unavailable, other reason | This ranking is unavailable ({reason}). |

## Superlatives.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C357 | METHOD | web/src/pages/Superlatives.tsx:37-38 | card subtitles | Escape artist (Closest wins) / Heartbreak kid (Closest losses) |
| C358 | CAVEAT | web/src/pages/Superlatives.tsx:50 | JOEL_EMBIID card, always (unconditional note) | Games missed, cause unknown |
| C359 | METHOD | web/src/pages/Superlatives.tsx:105 | PageHeader sub, always | Season-long awards, one card each: the highs and lows, the closest games, luck, the bench and the waiver wire. If an award can't be worked out yet, its card says why. |
| C360 | EMPTY | web/src/pages/Superlatives.tsx:114 | loading | Loading… |
| C361 | EMPTY | web/src/pages/Superlatives.tsx:118-119 | data.available false | No scored weeks yet / {reason from backend, else: No week of this season has been scored yet.} |
| C362 | METHOD | web/src/pages/Superlatives.tsx:126 | available | Season so far · through week {n} |
| C363 | CAVEAT | web/src/pages/Superlatives.tsx:129-131 | available | This league has no playoff start set, so every scored week counts. \| Regular season — weeks 1 through {n}. |
| C364 | CAVEAT | web/src/pages/Superlatives.tsx:219 | card s.early | early — this is mostly noise |
| C365 | METHOD | web/src/pages/Superlatives.tsx:242 | UNETHICAL card, suspensionWeeksObserved non-empty | tracking began week {n} |
| C366 | CAVEAT | web/src/pages/Superlatives.tsx:256 | UNETHICAL card, no observed suspension weeks, and not (available with no holders and an emptyReason) | suspension tracking hasn't covered a scored week yet |
| C367 | EMPTY | web/src/pages/Superlatives.tsx:262 | card not available | {s.reason from backend} |
| C368 | EMPTY | web/src/pages/Superlatives.tsx:264 | card available but empty | {s.emptyReason from backend} |
| C369 | METHOD | web/src/pages/Superlatives.tsx:284-285 | Jabari Smith Jr. card player holder | [{team} · ]{n} add(s) by {n} team(s) |
| C370 | METHOD | web/src/pages/Superlatives.tsx:299-300 | Jabari add row | week {n} / {formatAddType(d)} |
| C371 | METHOD | web/src/pages/Superlatives.tsx:351 | CLOSE_WINS / CLOSE_LOSSES shared value line | by under {closeGameMargin} points |
| C372 | CAVEAT | web/src/pages/Superlatives.tsx:358-359 | card has coverage | {weeksCovered} of {weeksCovered+weeksExcluded} weeks[ — {reasons joined by "; "}] |
| C373 | METHOD | web/src/pages/Superlatives.tsx:394 | HIGHEST/LOWEST week holder line | {pts} points · week {n} |
| C374 | METHOD | web/src/pages/Superlatives.tsx:399 | BIGGEST_BLOWOUT/CLOSEST_GAME holder line | {margin} points over {opponent} · week {n} |
| C375 | METHOD | web/src/pages/Superlatives.tsx:405 | CLOSE_WINS/CLOSE_LOSSES holder line | {weeksLabel: week(s) a, b–c} |
| C376 | METHOD | web/src/pages/Superlatives.tsx:412 | LUCKIEST/UNLUCKIEST holder line | {reading from backend} · {week\|weeks a–b} |
| C377 | METHOD | web/src/pages/Superlatives.tsx:419-421 | MOST_BENCH_POINTS holder line | {pts} points left on the bench · {week span}[ · worst: week {n} ({pts})] |
| C378 | METHOD | web/src/pages/Superlatives.tsx:434 | WAIVER_WIRE_WARRIOR total line | {pts} points from waiver pickups · weeks 1–{throughWeek or ?} |
| C379 | METHOD | web/src/pages/Superlatives.tsx:437-441 | WAIVER_WIRE_WARRIOR pickup lines | {player} ({pos}) — {pts} pts, added week {n} off waivers\|free agency, started week(s) {list} |
| C380 | CAVEAT | web/src/pages/Superlatives.tsx:454 | JOEL_EMBIID total line | {pts} estimated points lost |
| C381 | CAVEAT | web/src/pages/Superlatives.tsx:457-458 | JOEL_EMBIID absence lines | {player} ({pos}) — {n} games missed ({n} weeks), ~{pts} per game (estimated) |
| C382 | CAVEAT | web/src/pages/Superlatives.tsx:475-477 | UNETHICAL conduct lines | {player} — Suspended[ · weeks] / Commissioner's call: {reason}[ · weeks] |
| C383 | CAVEAT | web/src/pages/Superlatives.tsx:521 | standings figure LUCKIEST/UNLUCKIEST | {+\|−}{n} wins vs expected |
| C384 | METHOD | web/src/pages/Superlatives.tsx:524 | standings figure UNETHICAL | {n} player-week(s) |
| C385 | CAVEAT | web/src/pages/Superlatives.tsx:526 | standings figure JOEL_EMBIID | {pts} estimated points lost |
| C386 | METHOD | web/src/pages/Superlatives.tsx:531-543 | unit formats | {n} points / win(s) / game(s) / add(s); Free agent / Waiver / Waiver (${bid}) |
| C387 | METHOD | web/src/pages/Superlatives.tsx:554 | detail disclosure summary | Swing weeks (LUCKIEST/UNLUCKIEST) / Games |
| C388 | METHOD | web/src/pages/Superlatives.tsx:585-586 | LUCKIEST/UNLUCKIEST swing week row | Week {n}: won\|lost with {pts} ({ord} that week) vs {opponent} |
| C389 | METHOD | web/src/pages/Superlatives.tsx:602-604 | WEEK_SCORE/GAME detail rows | Week {n}: {pts} / Week {n} vs {opp}: {pts}–{pts} (margin {m}) |
| C390 | CAVEAT | web/src/pages/Superlatives.tsx:731 | Conduct list section, always | This list is for this season only — a new season starts with an empty one. |
| C391 | CAVEAT | web/src/pages/Superlatives.tsx:736 | requestedSeason != null (fallback season shown) | Showing the {season} season — the list for the new season opens once it has a scored week. |
| C392 | EMPTY | web/src/pages/Superlatives.tsx:744 | no commissioner known | No commissioner is known for this league, so the list can't be edited. |
| C393 | EMPTY | web/src/pages/Superlatives.tsx:751 | list empty | Nobody's on the list. |
| C394 | METHOD | web/src/pages/Superlatives.tsx:758-759 | conduct list row | applies from week {n} / added by {addedBy or the commissioner} |

## SuperlativeStandingsModal.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C395 | METHOD | web/src/components/SuperlativeStandingsModal.tsx:88-89 | Jabari standings, collapsed tie groups | {n} player(s) tied with {n} add(s) each / {n} more player(s) with {n} add(s) each |
| C396 | METHOD | web/src/components/SuperlativeStandingsModal.tsx:103 | Jabari standing row | {adds} add(s) by {n} team(s) |
| C397 | METHOD | web/src/components/SuperlativeStandingsModal.tsx:137 | modal for player-headed award (JABARI_SMITH_JR) | This award ranks players, not teams. |
| C398 | CAVEAT | web/src/components/SuperlativeStandingsModal.tsx:138 | s.early | early — this is mostly noise |
| C399 | CAVEAT | web/src/components/SuperlativeStandingsModal.tsx:141-142 | s.coverage present | {weeksCovered} of {total} weeks[ — {reasons joined by "; "}] |
| C400 | CAVEAT | web/src/components/SuperlativeStandingsModal.tsx:157-174 | standing row with no value | — rank; {r.missingReason from backend} in place of figure; {r.note} |
| C401 | METHOD | web/src/components/SuperlativeStandingsModal.tsx:183-185 | Jabari standings collapsed group summary | Rank {n} · {collapsedSummary} — Show all |

## LiveStatusBar.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C402 | CAVEAT | web/src/components/LiveStatusBar.tsx:65 | trackDraft observed === false | {status} (stale) |
| C403 | CAVEAT | web/src/components/LiveStatusBar.tsx:80-81 | live known, teams > 0 | All {n} managers identified / Only {n} of {n} managers identified |
| C404 | EMPTY | web/src/components/LiveStatusBar.tsx:101-107 | idle label (on-the-clock seat unknown) | Connecting to the draft / Not connected / Draft complete / Draft has not started / Waiting for the draft order |
| C405 | METHOD | web/src/components/LiveStatusBar.tsx:112 | progress bar tooltip | {made} of {total} picks made |
| C406 | METHOD | web/src/components/LiveStatusBar.tsx:126 | Refresh button tooltip | Check Sleeper again now and refresh which managers are in which seats |
| C407 | CAVEAT | web/src/components/LiveStatusBar.tsx:131 | freshness indicator | No contact / Stale · {ago} / Live · {ago} |

## SeatPopover.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C408 | CAVEAT | web/src/components/SeatPopover.tsx:41 | NEUTRAL seat footnote | {reachGapText(seat)} (null when no drafts) |
| C409 | CAVEAT | web/src/components/SeatPopover.tsx:47 | STATED seat footnote, no reach gap | What you entered. No history to check it against. (or reachGapText when drafts exist but nothing scoreable) |
| C410 | CAVEAT | web/src/components/SeatPopover.tsx:49 | FITTED seat footnote | {n} draft(s) observed · {n} picks scoreable |
| C411 | CAVEAT | web/src/components/SeatPopover.tsx:51 | BLENDED seat footnote | Your input, pulled toward {n} draft(s) of history ({n} picks) |
| C412 | METHOD | web/src/components/SeatPopover.tsx:136 | seat head badge (non-NEUTRAL) | {your call\|from history\|both} |
| C413 | METHOD | web/src/components/SeatPopover.tsx:140-141 | seat is not the viewer's | this is me (chip); you (chip when isMe) |
| C414 | METHOD | web/src/components/SeatPopover.tsx:147 | Edit tooltip | Stop editing without saving / Edit your private note about this manager |
| C415 | EMPTY | web/src/components/SeatPopover.tsx:156 | loading stated tendencies | Loading… |
| C416 | EMPTY | web/src/components/SeatPopover.tsx:175 | NEUTRAL seat with draftsObserved == 0 | Drafts like the room — nothing entered for this seat. |
| C417 | METHOD | web/src/components/SeatPopover.tsx:181 | otherwise | {behaviourText(seat)} |
| C418 | METHOD | web/src/components/SeatPopover.tsx:185 | seat has private note (title tooltip) | Only you can see this note / “{note}” |
| C419 | METHOD | web/src/components/SeatPopover.tsx:191 | onBrand read provided (live room) | {OnBrandLine} |

## AvailabilityPanel.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C420 | CAVEAT | web/src/components/AvailabilityPanel.tsx:51-53 | verdict per player, by survival at the user's next pick (<0.35 / >=0.65 / between) | Act now / Safe / Coin flip |
| C421 | METHOD | web/src/components/AvailabilityPanel.tsx:196 | panel expanded | Who's still there when you pick |
| C422 | EMPTY | web/src/components/AvailabilityPanel.tsx:220 | no picks left | No picks left |
| C423 | METHOD | web/src/components/AvailabilityPanel.tsx:230 | depth chip tooltip | Show the next {n} of your picks |
| C424 | METHOD | web/src/components/AvailabilityPanel.tsx:247 | sheet toggle tooltip | Show the player list / Hide the player list and show the whole board |
| C425 | METHOD | web/src/components/AvailabilityPanel.tsx:260-273 | table heads; Next picks header tooltip lists pick labels | Board / Next picks / Verdict ({round.pick · ...} tooltip) |
| C426 | CAVEAT | web/src/components/AvailabilityPanel.tsx:314 | survival strip cell tooltip | {round.pick}: {pct}% likely still there |
| C427 | EMPTY | web/src/components/AvailabilityPanel.tsx:329 | not started | Your realistic options show up here once the draft starts. |
| C428 | EMPTY | web/src/components/AvailabilityPanel.tsx:330 | started but rows empty | No players survive to these picks in any run. |

## PlayerPicker.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C429 | METHOD | web/src/components/PlayerPicker.tsx:91-92 | always | Round {round.pick} — pick #{n}. Recalculates every pick after this one based on what you take — may take a few seconds. |
| C430 | EMPTY | web/src/components/PlayerPicker.tsx:144-145 | no rows (survival < 20%) | Nothing survives above 20% here. Close this and take the model's suggested pick from the prompt behind it instead. |
| C431 | METHOD | web/src/components/PlayerPicker.tsx:66 | list filter (survival >= 20% at the paused pick) | (rule is implicit; only explained by the empty message) |

## PickFeed.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C432 | METHOD | web/src/components/PickFeed.tsx:79 | lead row, position run detected | {count} of the last {window} were {pos} |
| C433 | METHOD | web/src/components/PickFeed.tsx:81 | lead row, no run clause, fit provided (live room) | {Fills slot \| Depth} |
| C434 | METHOD | web/src/components/PickFeed.tsx:122 | clickable rows aria-label | Pick {r.p}, {player} to {manager}. Show pick card |

## OnTheClock.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C435 | METHOD | web/src/components/OnTheClock.tsx:75 | idle | {idleLabel or: Nobody on the clock} |
| C436 | METHOD | web/src/components/OnTheClock.tsx:81 | idle, maxPickNo > 0 | {n} picks · {n} rounds |
| C437 | METHOD | web/src/components/OnTheClock.tsx:98-101 | active | Your pick / On the clock; You / {manager} / Unknown seat — {r.p} |
| C438 | METHOD | web/src/components/OnTheClock.tsx:114 | manager clickable | {manager} — click for details |
| C439 | METHOD | web/src/components/OnTheClock.tsx:131 | waiting for own pick | {n} pick until you / {n} picks until you |
| C440 | METHOD | web/src/components/OnTheClock.tsx:138 | otherwise | {pickNo}/{max} round {n} of {n} |

## OnTheClockPickInput.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C441 | METHOD | web/src/components/OnTheClockPickInput.tsx:73 | always | Round {round} — pick #{pickNo}. |
| C442 | EMPTY | web/src/components/OnTheClockPickInput.tsx:138 | no rows | Nothing left at this position. |

## DraftBoard.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C443 | METHOD | web/src/components/DraftBoard.tsx:97 | column header tooltip | {manager} — click for details |
| C444 | CAVEAT | web/src/components/DraftBoard.tsx:108 | column provenance dot (hidden when hideProvenanceDots; absent for mock board) | {your call\|from history\|both} / drafts like the league average (NEUTRAL tooltip; NEUTRAL has no visible badge) |
| C445 | CAVEAT | web/src/components/DraftBoard.tsx:151-163 | cell tooltip: user pick / projected cell | Your pick — {name} \| {player}\n{manager} — {pct}% of runs\n[Not the most likely player here; the most likely one went earlier.]\n{alt} {pct}% ... |

## PlayerCard.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C446 | METHOD | web/src/components/PlayerCard.tsx:35,45 | user picked in sim / model comparison | You picked / Model's own pick here |
| C447 | CAVEAT | web/src/components/PlayerCard.tsx:52-54 | always (cell click) | Round {r.p} — pick #{n} — {pct}% of runs[ (not the most likely player here) when !isModal] |
| C448 | METHOD | web/src/components/PlayerCard.tsx:60 | alternatives exist | Alternatives |

## PickPrompt.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C449 | METHOD | web/src/components/PickPrompt.tsx:38 | user is on the clock in sim | Recalculates every pick after this one based on what you took — may take a few seconds. |
| C450 | METHOD | web/src/components/PickPrompt.tsx:44-53 | buttons | Take {model player} / Take {best available} (best available) / Choose a player |

## TurnIndicator.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C451 | METHOD | web/src/components/TurnIndicator.tsx:51 | mock complete | Mock draft complete |

## OnBrandPanel.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C452 | CAVEAT | web/src/components/OnBrandPanel.tsx:8-10 | tooltip on reach verdict | Hand-set thresholds, not fitted: needs {MIN_PICKS_FOR_VERDICT}+ picks; "on brand" = same sign, or both within ±{REACH_TOLERANCE} picks. |
| C453 | CAVEAT | web/src/components/OnBrandPanel.tsx:11-13 | tooltip on lean verdict | Hand-set thresholds, not fitted: a lean is a tilt of {LEAN_TILT} or more; needs {MIN_PICKS_FOR_VERDICT}+ picks; "on brand" = taking it more often than the room does. |
| C454 | CAVEAT | web/src/components/OnBrandPanel.tsx:15-18 | verdict text | on brand / off brand / {reason: e.g. no history, too early, no ADP on these picks, no lean, lean not fitted} |
| C455 | METHOD | web/src/components/OnBrandPanel.tsx:25 | mix text | {n} {pos} · ... / no picks yet |
| C456 | CAVEAT | web/src/components/OnBrandPanel.tsx:32-33 | reach line | reach so far n/a vs {profileReach} (no ADP) / reach {so far} vs {profile}; label: vs what you entered (STATED) \| vs board ADP |
| C457 | METHOD | web/src/components/OnBrandPanel.tsx:45-46 | lean line | leans {pos} ({pct} of picks vs room {pct}) / no positional lean |
| C458 | METHOD | web/src/components/OnBrandPanel.tsx:61 | Room read disclosure summary (live room) | Room read |

## onBrand.ts

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C459 | CAVEAT | web/src/onBrand.ts:35 | STATED or NEUTRAL seat: lean never computed (tilt only ever fitted) | (no text; lean null, reason 'lean not fitted') |

## ScarcityMeter.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C460 | EMPTY | web/src/components/ScarcityMeter.tsx:26 | failed, no scarcity, S == 0, or all pools empty | no board built yet |
| C461 | METHOD | web/src/components/ScarcityMeter.tsx:41-44 | scarcity chip | {leftNow} / {poolSize}[ · ~{expected}[ at {round.pick}]] |
| C462 | METHOD | web/src/components/ScarcityMeter.tsx:48 | running chip | {count} of the last {window} were {pos} |
| C463 | METHOD | web/src/components/ScarcityMeter.tsx:53 | always when meter has data (scarcity.definition from scarcity.ts:93) | Starter pool: the board's top {S} ({teams} teams × {startersPerTeam} starters) |
| C464 | CAVEAT | web/src/components/ScarcityMeter.tsx:55 | scarcity.gatedByDepth | projected count from pick ~{projectedFrom} |

## CommissionerKeyNote.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C465 | METHOD | web/src/components/CommissionerKeyNote.tsx:13 | commissioner key stored on this device | Commissioner key saved on this device. [Clear] |

## commissionerKey.ts

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C466 | METHOD | web/src/commissionerKey.ts:82-83 | window.prompt text when commissioner action needs key | That commissioner key wasn't accepted. Enter it again, or cancel. / This action needs the commissioner key. Enter it once; it is kept on this device only. |

## NotFound.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C467 | EMPTY | web/src/components/NotFound.tsx:4-8 | per resource (league / mock / draft / page) | No league here, or it isn't one of yours. / No mock draft at this address. / No draft at this address. / No draft, mock or page at this address. (+ Nothing here; Back to your leagues) |

## PickInsightCard.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C468 | METHOD | web/src/components/PickInsightCard.tsx:104 | fit known | Fills {slot} / Depth |
| C469 | METHOD | web/src/components/PickInsightCard.tsx:106 | fit known | Roster complete / Still needs: {open joined by " · "} |
| C470 | METHOD | web/src/components/PickInsightCard.tsx:111 | fit known and summary | {summarize: Fills {slot}. \| Depth pick, no open starting slot. [Roster complete. \| Still open: {list}. [No {positions} yet.]]} (from pickInsight.ts:57-66) |
| C471 | METHOD | web/src/components/PickInsightCard.tsx:115 | ADP known | {n} before ADP / {n} past ADP / On ADP (\|delta\|<=1) (pickInsight.ts:216-219) |
| C472 | CAVEAT | web/src/components/PickInsightCard.tsx:118-120 | model share known | Model had this at {pct} / Model had this under {pct} [as of pick {n} when projection > 1 pick stale] |
| C473 | CAVEAT | web/src/components/PickInsightCard.tsx:123 | insight.surprise | Surprise |
| C474 | METHOD | web/src/components/PickInsightCard.tsx:132 | next pick known | Likely next @ {round.pick} / Likely next |
| C475 | CAVEAT | web/src/components/PickInsightCard.tsx:134 | seat known (pickInsight.ts:109-114) | stated by you / no history — neutral seat / fitted from {n} draft(s) |
| C476 | METHOD | web/src/components/PickInsightCard.tsx:142 | top candidate wideOpen | Wide open |
| C477 | CAVEAT | web/src/components/PickInsightCard.tsx:151 | likelyNext updating | Updating… |
| C478 | CAVEAT | web/src/components/PickInsightCard.tsx:152 | likelyNext busy (429) | projection server busy, will retry next pick |
| C479 | CAVEAT | web/src/components/PickInsightCard.tsx:153 | likelyNext none | {reason: no projection yet / no picks left / projection not back yet} |

## TendenciesForm.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C480 | METHOD | web/src/components/TendenciesForm.tsx:96,103,108 | button tooltips | Save your private note about this manager / Delete your note about this manager / Discard changes and stop editing |

## StartMockModal.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C481 | METHOD | web/src/components/StartMockModal.tsx:110-111 | always | Practice round / Start a mock draft |
| C482 | METHOD | web/src/components/StartMockModal.tsx:131 | step label | 2 · Use settings from |
| C483 | EMPTY | web/src/components/StartMockModal.tsx:133 | no leagues of chosen sport | No {SPORT} leagues to clone yet — add one from Home first. |
| C484 | METHOD | web/src/components/StartMockModal.tsx:152 | league option | {teams} teams · {rounds} rounds · {season} |

## BumpChart.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C485 | CAVEAT | web/src/components/BumpChart.tsx:260-263 | series end tooltip | {name} · week {n} · {score label\|rank} {rank}[ · {score}][ · {note}][ · thin coverage] |
| C486 | CAVEAT | web/src/components/BumpChart.tsx:315-317 | point tooltip | {name} · week {n} · {score label\|rank} {rank}[ · {score}][ · {note}][ · thin coverage when ballotCount*2 < memberCount] |

## RankBoard.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C487 | METHOD | web/src/components/RankBoard.tsx:967 | incomplete ranking | {n} of {n} still unranked |

## PageHeader.tsx

| # | Kind | Location | Condition | Exact text |
|---|---|---|---|---|
| C488 | METHOD | web/src/components/PageHeader.tsx:42 | renders every page's sub prop (hosts the subtitle text for all PageHeader pages) | (container, no text) |

## Counts

| Kind | Rows |
|---|---|
| CAVEAT | 132 |
| METHOD | 236 |
| EMPTY | 120 |
| Total | 488 |

Files with rows: 45

| C359 | web/src/pages/Superlatives.tsx:153 | reworded + HowThisWorks | Subtitle is one sentence; "If an award can't be worked out yet, its row says why" ("card" became "row" in the trophy list) is a How this works under the list. || C065 | web/src/pages/ManagerHistory.tsx:303 | HowThisWorks | Same sentence, in the draft-tendencies section's How this works. That section moved up directly under the player card, so "the record above" now reads "the record on this page" (clarity only, no claim changed). || C157 | web/src/pages/LeagueHistory.tsx:615 | moved | "season in progress" now appears once, in the season's section title, instead of in every rank cell; an in-progress rank cell is a dash with title="Season in progress". The RANK_REASON entry (line 295) is kept for the other statuses. || C018 | web/src/pages/SignIn.tsx:96 | unchanged |  || C017 | web/src/pages/SignIn.tsx:91 | unchanged |  || C016 | web/src/pages/SignIn.tsx:85 | moved (visible) | Same sentence, now a muted note under the field on the centered welcome (was the hero sub-paragraph). Still visible, not behind a click. |# Caveats inventory, AFTER the 013 fan-first redesign

Maps each `caveats-before.md` row to where it lives now. Line numbers are searched from the current source by the row text, so a number is the first line carrying that text; "unchanged" means the text and condition are as before (the surrounding section moved from `.panel` to `.section`).

Covered: T014-T021 (part A: DraftPicker, PowerRankings, LeagueAnalysis, RosterManagement, ExpectedWins, SeasonForecast, WeeklyReport, Superlatives) and T022-T027 (part B: LeagueHistory, ManagerTendencies, ManagerHistory, ManagerComparison, DraftBoard, MockSetup; CompletedDraftBoard, MockDraftView, DraftView and LiveDraftView are board-first rooms with no subtitle and were not edited). Every other row is marked "unchanged" because its file was not touched in this redesign. Rows whose line is shown as `~n` were located only approximately (the text is assembled from parts or lives in a tooltip), and the code there is untouched. Part B changes: LeagueHistory (subtitle, draft-opinion paragraph into How this works, Compute gated by canCommission), ManagerTendencies (subtitle and method into How this works, the (±n) stays visible), ManagerHistory (two method paragraphs into How this works, stat cards become one unboxed row), MockSetup (subtitle shortened, two caveats moved into the page body, bot sentence into How this works), DraftBoard (CSS only).

| C### | new location (file:line) | how (beside number / HowThisWorks / unchanged / reworded) | note |
|---|---|---|---|
| C001 | web/src/provenance.ts:14 | unchanged |  |
| C002 | web/src/provenance.ts:15 | unchanged |  |
| C003 | web/src/provenance.ts:~15 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C004 | web/src/provenance.ts:~15 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C005 | web/src/managerBehaviour.ts:62 | unchanged |  |
| C006 | web/src/managerBehaviour.ts:63 | unchanged |  |
| C007 | web/src/managerBehaviour.ts:66 | unchanged |  |
| C008 | web/src/managerBehaviour.ts:67 | unchanged |  |
| C009 | web/src/managerBehaviour.ts:77 | unchanged |  |
| C010 | web/src/managerBehaviour.ts:78 | unchanged |  |
| C011 | web/src/managerBehaviour.ts:~78 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C012 | web/src/managerBehaviour.ts:93 | unchanged |  |
| C013 | web/src/managerBehaviour.ts:~93 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C014 | web/src/managerBehaviour.ts:109 | unchanged |  |
| C015 | web/src/components/SeasonFallbackNote.tsx:27 | unchanged |  |
| C016 | web/src/pages/SignIn.tsx:59 | unchanged |  |
| C017 | web/src/pages/SignIn.tsx:83 | unchanged |  |
| C018 | web/src/pages/SignIn.tsx:88 | unchanged |  |
| C019 | web/src/pages/CompletedDraftBoard.tsx:85 | unchanged |  |
| C020 | web/src/pages/DraftPicker.tsx:41 | unchanged |  |
| C021 | web/src/pages/DraftPicker.tsx:694 | HowThisWorks | Same sentence, now at the end of the Mock drafts section; the subtitle was rewritten. |
| C022 | web/src/pages/DraftPicker.tsx:325 | reworded | Subtitle now "...{n}-team, {n}-round league - practice before its next draft, in a room only you control." Facts kept. |
| C023 | web/src/pages/DraftPicker.tsx:694 | reworded + HowThisWorks | Subtitle reworded as one fan sentence; the "bots fill every seat but yours" claim is in the Mock drafts How this works. |
| C024 | web/src/pages/DraftPicker.tsx:694 | reworded + HowThisWorks | Subtitle reworded as one fan sentence; the "bots fill every seat but yours" claim is in the Mock drafts How this works. |
| C025 | web/src/pages/DraftPicker.tsx:360 | unchanged |  |
| C026 | web/src/pages/DraftPicker.tsx:362 | unchanged |  |
| C027 | web/src/pages/DraftPicker.tsx:363 | unchanged |  |
| C028 | web/src/pages/DraftPicker.tsx:108 | unchanged |  |
| C029 | web/src/pages/DraftPicker.tsx:498 | unchanged |  |
| C030 | web/src/pages/DraftPicker.tsx:507 | unchanged |  |
| C031 | web/src/pages/DraftPicker.tsx:~512 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C032 | web/src/pages/DraftPicker.tsx:~492 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C033 | web/src/pages/DraftPicker.tsx:520 | unchanged |  |
| C034 | web/src/pages/DraftPicker.tsx:553 | unchanged |  |
| C035 | web/src/pages/DraftPicker.tsx:~607 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C036 | web/src/pages/DraftPicker.tsx:645 | unchanged |  |
| C037 | web/src/pages/DraftPicker.tsx:646 | unchanged |  |
| C038 | web/src/pages/DraftPicker.tsx:666 | unchanged |  |
| C039 | web/src/pages/DraftPicker.tsx:707 | unchanged |  |
| C040 | web/src/pages/DraftPicker.tsx:710 | unchanged |  |
| C041 | web/src/pages/DraftPicker.tsx:~713 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C042 | web/src/pages/ManagerTendencies.tsx:37 | unchanged |  |
| C043 | web/src/pages/ManagerTendencies.tsx:88 | unchanged |  |
| C044 | web/src/pages/ManagerTendencies.tsx:~88 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C045 | web/src/pages/ManagerTendencies.tsx:~88 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C046 | web/src/pages/ManagerTendencies.tsx:100 | unchanged |  |
| C047 | web/src/pages/ManagerTendencies.tsx:~100 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C048 | web/src/pages/ManagerTendencies.tsx:~100 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C049 | web/src/pages/ManagerTendencies.tsx:171 | unchanged |  |
| C050 | web/src/pages/ManagerTendencies.tsx:~171 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C051 | web/src/pages/ManagerTendencies.tsx:7 | unchanged |  |
| C052 | web/src/pages/ManagerTendencies.tsx:~7 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C053 | web/src/pages/ManagerTendencies.tsx:~7 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C054 | web/src/pages/ManagerTendencies.tsx:369 | reworded + HowThisWorks | Subtitle is now "Who in your leagues reaches, who waits on a position, and who drafts like the room, read from their own draft history."; the original paragraph (engine-fitted, note is private and never changes a sim) is the first paragraph of the page's How this works. |
| C055 | web/src/pages/ManagerTendencies.tsx:374 | unchanged | Still visible above the list; it limits who appears, so it stays on the page. |
| C056 | web/src/pages/ManagerTendencies.tsx:412 | HowThisWorks | Same paragraph (reach vs the same draft room, the shaded band is one standard error, most sit inside it) now in How this works. The number it qualifies stays visible: the "(±n)" beside each reach caption (C045) is NOT moved to a tooltip. |
| C057 | web/src/pages/ManagerTendencies.tsx:384 | unchanged |  |
| C058 | web/src/pages/ManagerTendencies.tsx:~384 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C059 | web/src/pages/ManagerTendencies.tsx:~384 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C060 | web/src/pages/ManagerHistory.tsx:~121-128 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C061 | web/src/pages/ManagerHistory.tsx:193 | unchanged |  |
| C062 | web/src/pages/ManagerHistory.tsx:62 | unchanged |  |
| C063 | web/src/pages/ManagerHistory.tsx:233 | unchanged |  |
| C064 | web/src/pages/ManagerHistory.tsx:~233 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C065 | web/src/pages/ManagerHistory.tsx:314 | HowThisWorks | Same sentence, in the draft-tendencies section's How this works; the section title "What this app thinks about their drafting" still separates it from the record. |
| C066 | web/src/pages/ManagerHistory.tsx:272 | unchanged |  |
| C067 | web/src/pages/ManagerHistory.tsx:283 | unchanged |  |
| C068 | web/src/pages/ManagerHistory.tsx:295 | unchanged |  |
| C069 | web/src/pages/ManagerHistory.tsx:~295 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C070 | web/src/pages/ManagerHistory.tsx:~295 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C071 | web/src/pages/ManagerHistory.tsx:353 | HowThisWorks | Same paragraph, in the Career profile section's How this works. The per-figure "over N season(s)" caveat (C078) stays beside every figure. |
| C072 | web/src/pages/ManagerHistory.tsx:378 | unchanged |  |
| C073 | web/src/pages/ManagerHistory.tsx:386 | unchanged |  |
| C074 | web/src/pages/ManagerHistory.tsx:387 | unchanged |  |
| C075 | web/src/pages/ManagerHistory.tsx:399 | unchanged |  |
| C076 | web/src/pages/ManagerHistory.tsx:402 | unchanged |  |
| C077 | web/src/pages/ManagerHistory.tsx:415 | unchanged |  |
| C078 | web/src/pages/ManagerHistory.tsx:88 | unchanged |  |
| C079 | web/src/pages/ManagerHistory.tsx:455 | unchanged |  |
| C080 | web/src/pages/ManagerHistory.tsx:463 | unchanged |  |
| C081 | web/src/pages/ManagerHistory.tsx:463 | unchanged |  |
| C082 | web/src/pages/ManagerHistory.tsx:477 | unchanged |  |
| C083 | web/src/pages/ManagerHistory.tsx:485 | unchanged |  |
| C084 | web/src/pages/ManagerHistory.tsx:~485 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C085 | web/src/pages/ManagerHistory.tsx:~485 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C086 | web/src/pages/ManagerHistory.tsx:~485 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C087 | web/src/pages/ManagerComparison.tsx:79 | unchanged |  |
| C088 | web/src/pages/ManagerComparison.tsx:87 | unchanged |  |
| C089 | web/src/pages/ManagerComparison.tsx:113 | unchanged |  |
| C090 | web/src/pages/ManagerComparison.tsx:118 | unchanged |  |
| C091 | web/src/pages/ManagerComparison.tsx:~118 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C092 | web/src/pages/ManagerComparison.tsx:179 | unchanged |  |
| C093 | web/src/pages/ManagerComparison.tsx:235 | unchanged |  |
| C094 | web/src/pages/ManagerComparison.tsx:~235 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C095 | web/src/pages/ManagerComparison.tsx:55 | unchanged |  |
| C096 | web/src/pages/MockSetup.tsx:16 | unchanged |  |
| C097 | web/src/pages/MockSetup.tsx:17 | unchanged |  |
| C098 | web/src/pages/MockSetup.tsx:18 | unchanged |  |
| C099 | web/src/pages/MockSetup.tsx:19 | unchanged |  |
| C100 | web/src/pages/MockSetup.tsx:159 | reworded + HowThisWorks | Subtitle now "Practice {league}'s draft - {teams} teams[, {rounds} rounds], with you in the seat you pick." Facts kept; the "bots fill every seat but yours" claim is in the How this works. |
| C101 | web/src/pages/MockSetup.tsx:163 | reworded + HowThisWorks | Subtitle now "Pick your seat, seat the managers you want to face, and start the practice draft."; both original sentences are in the How this works. |
| C102 | web/src/pages/MockSetup.tsx:173 | moved (visible) | Moved out of the subtitle into a muted line at the top of the setup section; same text, still on the page. |
| C103 | web/src/pages/MockSetup.tsx:176 | moved (visible) | Moved out of the subtitle into a muted line at the top of the setup section; same text, still on the page. |
| C104 | web/src/pages/MockSetup.tsx:218 | unchanged |  |
| C105 | web/src/pages/MockSetup.tsx:145 | unchanged |  |
| C106 | web/src/pages/MockSetup.tsx:222 | unchanged |  |
| C107 | web/src/pages/MockDraftView.tsx:~37-45 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C108 | web/src/pages/MockDraftView.tsx:134 | unchanged |  |
| C109 | web/src/pages/MockDraftView.tsx:~134 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C110 | web/src/pages/DraftView.tsx:271 | unchanged |  |
| C111 | web/src/pages/DraftView.tsx:462 | unchanged |  |
| C112 | web/src/pages/DraftView.tsx:486 | unchanged |  |
| C113 | web/src/pages/DraftView.tsx:522 | unchanged |  |
| C114 | web/src/pages/DraftView.tsx:535 | unchanged |  |
| C115 | web/src/pages/DraftView.tsx:592 | unchanged |  |
| C116 | web/src/pages/DraftView.tsx:600 | unchanged |  |
| C117 | web/src/pages/DraftView.tsx:601 | unchanged |  |
| C118 | web/src/pages/DraftView.tsx:~601 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C119 | web/src/pages/DraftView.tsx:715 | unchanged |  |
| C120 | web/src/pages/DraftView.tsx:722 | unchanged |  |
| C121 | web/src/pages/DraftView.tsx:~722 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C122 | web/src/pages/DraftView.tsx:~722 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C123 | web/src/pages/DraftView.tsx:~722 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C124 | web/src/pages/DraftView.tsx:772 | unchanged |  |
| C125 | web/src/pages/DraftView.tsx:~772 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C126 | web/src/pages/DraftView.tsx:790 | unchanged |  |
| C127 | web/src/pages/DraftView.tsx:~790 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C128 | web/src/pages/LiveDraftView.tsx:~467 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C129 | web/src/pages/LiveDraftView.tsx:644 | unchanged |  |
| C130 | web/src/pages/LiveDraftView.tsx:~644 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C131 | web/src/pages/LiveDraftView.tsx:731 | unchanged |  |
| C132 | web/src/pages/LiveDraftView.tsx:732 | unchanged |  |
| C133 | web/src/pages/LiveDraftView.tsx:734 | unchanged |  |
| C134 | web/src/pages/LiveDraftView.tsx:735 | unchanged |  |
| C135 | web/src/pages/LiveDraftView.tsx:775 | unchanged |  |
| C136 | web/src/pages/LiveDraftView.tsx:805 | unchanged |  |
| C137 | web/src/pages/LiveDraftView.tsx:812 | unchanged |  |
| C138 | web/src/pages/LiveDraftView.tsx:813 | unchanged |  |
| C139 | web/src/pages/LiveDraftView.tsx:814 | unchanged |  |
| C140 | web/src/pages/LiveDraftView.tsx:815 | unchanged |  |
| C141 | web/src/pages/LiveDraftView.tsx:819 | unchanged |  |
| C142 | web/src/pages/LiveDraftView.tsx:~819 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C143 | web/src/pages/LiveDraftView.tsx:841 | unchanged |  |
| C144 | web/src/pages/LiveDraftView.tsx:851 | unchanged |  |
| C145 | web/src/pages/LiveDraftView.tsx:859 | unchanged |  |
| C146 | web/src/pages/LiveDraftView.tsx:908 | unchanged |  |
| C147 | web/src/pages/LiveDraftView.tsx:~908 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C148 | web/src/pages/LeagueHistory.tsx:58 | unchanged |  |
| C149 | web/src/pages/LeagueHistory.tsx:~58 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C150 | web/src/pages/LeagueHistory.tsx:11 | unchanged |  |
| C151 | web/src/pages/LeagueHistory.tsx:184 | unchanged |  |
| C152 | web/src/pages/LeagueHistory.tsx:58 | unchanged |  |
| C153 | web/src/pages/LeagueHistory.tsx:~58 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C154 | web/src/pages/LeagueHistory.tsx:244 | unchanged |  |
| C155 | web/src/pages/LeagueHistory.tsx:245 | unchanged |  |
| C156 | web/src/pages/LeagueHistory.tsx:274 | unchanged |  |
| C157 | web/src/pages/LeagueHistory.tsx:292 | unchanged |  |
| C158 | web/src/pages/LeagueHistory.tsx:293 | reworded | Cell still reads "not computed yet". Compute shows only when the payload says canCommission === true; otherwise one line per season reads "Final ranks appear once the commissioner computes them." (a missing field counts as false). |
| C159 | web/src/pages/LeagueHistory.tsx:294 | unchanged |  |
| C160 | web/src/pages/LeagueHistory.tsx:310 | unchanged |  |
| C161 | web/src/pages/LeagueHistory.tsx:355 | unchanged |  |
| C162 | web/src/pages/LeagueHistory.tsx:367 | unchanged |  |
| C163 | web/src/pages/LeagueHistory.tsx:379 | unchanged |  |
| C164 | web/src/pages/LeagueHistory.tsx:~379 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C165 | web/src/pages/LeagueHistory.tsx:466 | reworded + HowThisWorks | Subtitle now "Every season's standings, with the champion and each year's final power rank."; the original paragraph (including that draft opinion lives on the manager page) is in the page's How this works. |
| C166 | web/src/pages/LeagueHistory.tsx:480 | unchanged |  |
| C167 | web/src/pages/LeagueHistory.tsx:493 | unchanged |  |
| C168 | web/src/pages/LeagueHistory.tsx:498 | unchanged |  |
| C169 | web/src/pages/LeagueHistory.tsx:510 | unchanged |  |
| C170 | web/src/pages/PowerRankings.tsx:38 | unchanged |  |
| C171 | web/src/pages/PowerRankings.tsx:81 | HowThisWorks | KIND_CAVEAT[ladderMode] is the first paragraph of the ladder How this works (it was behind a click before too, via the old howOpen toggle). |
| C172 | web/src/pages/PowerRankings.tsx:82 | HowThisWorks | KIND_CAVEAT[ladderMode] is the first paragraph of the ladder How this works (it was behind a click before too, via the old howOpen toggle). |
| C173 | web/src/pages/PowerRankings.tsx:84 | HowThisWorks | KIND_CAVEAT[ladderMode] is the first paragraph of the ladder How this works (it was behind a click before too, via the old howOpen toggle). |
| C174 | web/src/pages/PowerRankings.tsx:9 | unchanged |  |
| C175 | web/src/pages/PowerRankings.tsx:81 | unchanged |  |
| C176 | web/src/pages/PowerRankings.tsx:~96 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C177 | web/src/pages/PowerRankings.tsx:191 | unchanged |  |
| C178 | web/src/pages/PowerRankings.tsx:193 | unchanged |  |
| C179 | web/src/pages/PowerRankings.tsx:~201 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C180 | web/src/pages/PowerRankings.tsx:~244 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C181 | web/src/pages/PowerRankings.tsx:~245 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C182 | web/src/pages/PowerRankings.tsx:323 | unchanged |  |
| C183 | web/src/pages/PowerRankings.tsx:337 | reworded | buildDeck is now ONE sentence, "{lead}; {n} of {m} ballots in for {week}." Ballot tally is kept in the sentence (test strings unchanged); the lead clause is the old second clause. |
| C184 | web/src/pages/PowerRankings.tsx:352 | reworded | buildDeck is now ONE sentence, "{lead}; {n} of {m} ballots in for {week}." Ballot tally is kept in the sentence (test strings unchanged); the lead clause is the old second clause. |
| C185 | web/src/pages/PowerRankings.tsx:383 | unchanged |  |
| C186 | web/src/pages/PowerRankings.tsx:442 | unchanged |  |
| C187 | web/src/pages/PowerRankings.tsx:~545 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C188 | web/src/pages/PowerRankings.tsx:560 | unchanged |  |
| C189 | web/src/pages/PowerRankings.tsx:577 | unchanged |  |
| C190 | web/src/pages/PowerRankings.tsx:636 | unchanged |  |
| C191 | web/src/pages/PowerRankings.tsx:~658 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C192 | web/src/pages/PowerRankings.tsx:708 | HowThisWorks | oddsNote is the first paragraph of the ladder How this works. |
| C193 | web/src/pages/PowerRankings.tsx:~782 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C194 | web/src/pages/PowerRankings.tsx:337 | reworded | buildDeck is now ONE sentence, "{lead}; {n} of {m} ballots in for {week}." Ballot tally is kept in the sentence (test strings unchanged); the lead clause is the old second clause. |
| C195 | web/src/pages/PowerRankings.tsx:792 | unchanged |  |
| C196 | web/src/pages/PowerRankings.tsx:~796 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C197 | web/src/pages/PowerRankings.tsx:804 | unchanged |  |
| C198 | web/src/pages/PowerRankings.tsx:382 | unchanged |  |
| C199 | web/src/pages/PowerRankings.tsx:~391 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C200 | web/src/pages/PowerRankings.tsx:834 | unchanged |  |
| C201 | web/src/pages/PowerRankings.tsx:851 | unchanged |  |
| C202 | web/src/pages/PowerRankings.tsx:~855 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C203 | web/src/pages/PowerRankings.tsx:860 | unchanged |  |
| C204 | web/src/pages/PowerRankings.tsx:870 | reworded | Riser/Free fall are now unboxed inline callouts; the delta reads "up +n" / "down -n" (arrow plus sign). Sentence text unchanged. |
| C205 | web/src/pages/PowerRankings.tsx:~908 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C206 | web/src/pages/PowerRankings.tsx:933 | unchanged |  |
| C207 | web/src/pages/PowerRankings.tsx:935 | unchanged |  |
| C208 | web/src/pages/PowerRankings.tsx:949 | unchanged |  |
| C209 | web/src/pages/PowerRankings.tsx:956 | unchanged |  |
| C210 | web/src/pages/PowerRankings.tsx:959 | unchanged |  |
| C211 | web/src/pages/PowerRankings.tsx:960 | unchanged |  |
| C212 | web/src/pages/PowerRankings.tsx:962 | unchanged |  |
| C213 | web/src/pages/PowerRankings.tsx:981 | unchanged |  |
| C214 | web/src/pages/PowerRankings.tsx:~1057 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C215 | web/src/pages/PowerRankings.tsx:1060 | unchanged |  |
| C216 | web/src/pages/PowerRankings.tsx:~1070 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C217 | web/src/pages/PowerRankings.tsx:~1094 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C218 | web/src/pages/PowerRankings.tsx:1104 | unchanged |  |
| C219 | web/src/pages/PowerRankings.tsx:1111 | unchanged |  |
| C220 | web/src/pages/PowerRankings.tsx:1128 | HowThisWorks | The "How this works ->" link-button + inline reveal became a real HowThisWorks disclosure at the end of the ladder section; the duplicate sidebar copy of the link was removed (same disclosure). |
| C221 | web/src/pages/PowerRankings.tsx:1129 | HowThisWorks | The "How this works ->" link-button + inline reveal became a real HowThisWorks disclosure at the end of the ladder section; the duplicate sidebar copy of the link was removed (same disclosure). |
| C222 | web/src/pages/PowerRankings.tsx:1131 | HowThisWorks | The "How this works ->" link-button + inline reveal became a real HowThisWorks disclosure at the end of the ladder section; the duplicate sidebar copy of the link was removed (same disclosure). |
| C223 | web/src/pages/PowerRankings.tsx:1158 | unchanged |  |
| C224 | web/src/pages/PowerRankings.tsx:1164 | unchanged |  |
| C225 | web/src/pages/PowerRankings.tsx:1176 | unchanged |  |
| C226 | web/src/pages/PowerRankings.tsx:~1187 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C227 | web/src/pages/PowerRankings.tsx:~1197 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C228 | web/src/pages/PowerRankings.tsx:1199 | unchanged |  |
| C229 | web/src/pages/PowerRankings.tsx:~1232 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C230 | web/src/pages/PowerRankings.tsx:1234 | unchanged |  |
| C231 | web/src/pages/PowerRankings.tsx:1235 | unchanged |  |
| C232 | web/src/pages/PowerRankings.tsx:1289 | unchanged |  |
| C233 | web/src/pages/PowerRankings.tsx:1297 | unchanged |  |
| C234 | web/src/pages/PowerRankings.tsx:1335 | unchanged |  |
| C235 | web/src/pages/PowerRankings.tsx:1366 | unchanged |  |
| C236 | web/src/pages/PowerRankings.tsx:1371 | unchanged |  |
| C237 | web/src/pages/PowerRankings.tsx:~1374 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C238 | web/src/pages/PowerRankings.tsx:1383 | unchanged |  |
| C239 | web/src/pages/PowerRankings.tsx:1384 | unchanged |  |
| C240 | web/src/pages/PowerRankings.tsx:1395 | unchanged |  |
| C241 | web/src/pages/PowerRankings.tsx:1399 | unchanged |  |
| C242 | web/src/pages/PowerRankings.tsx:~1401 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C243 | web/src/pages/PowerRankings.tsx:1402 | unchanged |  |
| C244 | web/src/pages/PowerRankings.tsx:1406 | unchanged |  |
| C245 | web/src/pages/PowerRankings.tsx:1416 | unchanged |  |
| C246 | web/src/pages/PowerRankings.tsx:1421 | unchanged |  |
| C247 | web/src/pages/PowerRankings.tsx:1425 | unchanged |  |
| C248 | web/src/pages/PowerRankings.tsx:622 | unchanged |  |
| C249 | web/src/pages/PowerRankings.tsx:1464 | unchanged |  |
| C250 | web/src/pages/PowerRankings.tsx:1476 | unchanged |  |
| C251 | web/src/pages/PowerRankings.tsx:1484 | unchanged |  |
| C252 | web/src/pages/PowerRankings.tsx:1366 | unchanged |  |
| C253 | web/src/pages/PowerRankings.tsx:1491 | unchanged |  |
| C254 | web/src/pages/PowerRankings.tsx:1476 | unchanged |  |
| C255 | web/src/pages/PowerRankings.tsx:1476 | unchanged |  |
| C256 | web/src/pages/PowerRankings.tsx:~1478 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C257 | web/src/pages/PowerRankings.tsx:~1484 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C258 | web/src/pages/LeagueAnalysis.tsx:50 | unchanged |  |
| C259 | web/src/pages/LeagueAnalysis.tsx:98 | unchanged |  |
| C260 | web/src/pages/LeagueAnalysis.tsx:84 | unchanged |  |
| C261 | web/src/pages/LeagueAnalysis.tsx:142 | HowThisWorks | FormulaNote is now inside RankingScoreHow (the Ranking score How this works). |
| C262 | web/src/pages/LeagueAnalysis.tsx:134 | HowThisWorks | FormulaNote is now inside RankingScoreHow (the Ranking score How this works). |
| C263 | web/src/pages/LeagueAnalysis.tsx:173 | HowThisWorks | The "{raw} raw" under each score moved to a "Raw formula output" paragraph in the Ranking score How this works (task T016 specified this); it lists every roster so nothing is lost. |
| C264 | web/src/pages/LeagueAnalysis.tsx:~235 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C265 | web/src/pages/LeagueAnalysis.tsx:323 | unchanged |  |
| C266 | web/src/pages/LeagueAnalysis.tsx:351 | unchanged |  |
| C267 | web/src/pages/LeagueAnalysis.tsx:364 | unchanged |  |
| C268 | web/src/pages/LeagueAnalysis.tsx:424 | unchanged |  |
| C269 | web/src/pages/LeagueAnalysis.tsx:~465 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C270 | web/src/pages/LeagueAnalysis.tsx:527 | unchanged |  |
| C271 | web/src/pages/LeagueAnalysis.tsx:552 | unchanged |  |
| C272 | web/src/pages/LeagueAnalysis.tsx:609 | unchanged |  |
| C273 | web/src/pages/LeagueAnalysis.tsx:668 | unchanged |  |
| C274 | web/src/pages/LeagueAnalysis.tsx:712 | unchanged |  |
| C275 | web/src/pages/LeagueAnalysis.tsx:763 | unchanged |  |
| C276 | web/src/pages/LeagueAnalysis.tsx:767 | unchanged |  |
| C277 | web/src/pages/LeagueAnalysis.tsx:~873 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C278 | web/src/pages/LeagueAnalysis.tsx:~882 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C279 | web/src/pages/LeagueAnalysis.tsx:905 | unchanged |  |
| C280 | web/src/pages/LeagueAnalysis.tsx:926 | unchanged |  |
| C281 | web/src/pages/LeagueAnalysis.tsx:~959 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C282 | web/src/pages/LeagueAnalysis.tsx:997 | unchanged |  |
| C283 | web/src/pages/LeagueAnalysis.tsx:1038 | unchanged |  |
| C284 | web/src/pages/LeagueAnalysis.tsx:1085 | unchanged |  |
| C285 | web/src/pages/LeagueAnalysis.tsx:~1099 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C286 | web/src/pages/LeagueAnalysis.tsx:1172 | reworded + HowThisWorks | Subtitle is one sentence. Composite-from-played-games and the pointer to Power rankings are in the Ranking score How this works; projected-onto-lineups in Roster projections How this works; next-week lineups in the Matchups How this works. |
| C287 | web/src/pages/LeagueAnalysis.tsx:1184 | unchanged |  |
| C288 | web/src/pages/LeagueAnalysis.tsx:162 | HowThisWorks | First paragraph of RankingScoreHow (also states rankings appear after {weeksRequired} scored weeks, from the payload). |
| C289 | web/src/pages/LeagueAnalysis.tsx:1207 | unchanged |  |
| C290 | web/src/pages/LeagueAnalysis.tsx:1215 | HowThisWorks | Roster projections How this works, lightly reworded. |
| C291 | web/src/pages/LeagueAnalysis.tsx:~1226 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C292 | web/src/pages/LeagueAnalysis.tsx:1235 | beside number + HowThisWorks | A one-line "This is the projection; what was actually scored is further down." stays visible above the chart; the full text is in the section How this works. |
| C293 | web/src/pages/LeagueAnalysis.tsx:1249 | HowThisWorks | Position group rankings How this works. |
| C294 | web/src/pages/LeagueAnalysis.tsx:1262 | unchanged |  |
| C295 | web/src/pages/LeagueAnalysis.tsx:1295 | beside number + HowThisWorks | The "margin is two projections subtracted, not a win probability" sentence stays visible above the matchups; the rest is in How this works. |
| C296 | web/src/pages/LeagueAnalysis.tsx:~1308 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C297 | web/src/pages/LeagueAnalysis.tsx:1312 | HowThisWorks | Week by week How this works. |
| C298 | web/src/pages/LeagueAnalysis.tsx:~1322 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C299 | web/src/pages/LeagueAnalysis.tsx:1331 | HowThisWorks | Head to head How this works. |
| C300 | web/src/pages/RosterManagement.tsx:160 | reworded + HowThisWorks | Subtitle: "Who left the most points on their bench, against a perfect lineup every week." Original sentence is the efficiency How this works. |
| C301 | web/src/pages/RosterManagement.tsx:112 | unchanged |  |
| C302 | web/src/pages/RosterManagement.tsx:118 | unchanged |  |
| C303 | web/src/pages/RosterManagement.tsx:~127 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C304 | web/src/pages/RosterManagement.tsx:139 | unchanged |  |
| C305 | web/src/pages/RosterManagement.tsx:209 | unchanged |  |
| C306 | web/src/pages/RosterManagement.tsx:218 | unchanged |  |
| C307 | web/src/pages/RosterManagement.tsx:~247 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C308 | web/src/pages/RosterManagement.tsx:255 | HowThisWorks | Waivers and free agent adds How this works. The per-row "avg over n wk" and the "Lower is better" tooltip (C310) stay beside the rank. |
| C309 | web/src/pages/RosterManagement.tsx:~267 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C310 | web/src/pages/RosterManagement.tsx:276 | unchanged |  |
| C311 | web/src/pages/RosterManagement.tsx:~309 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C312 | web/src/pages/RosterManagement.tsx:332 | unchanged | Text kept; the three largest gaps now render in --down (words still present). |
| C313 | web/src/pages/RosterManagement.tsx:350 | unchanged |  |
| C314 | web/src/pages/RosterManagement.tsx:~155 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C315 | web/src/pages/ExpectedWins.tsx:141 | reworded + HowThisWorks | Subtitle is a takeaway (names the luckiest team only at >= 4 weeks scored, else neutral). Original sentence ("The gap is luck, not skill") is in How this works. |
| C316 | web/src/pages/ExpectedWins.tsx:93 | unchanged |  |
| C317 | web/src/pages/ExpectedWins.tsx:99 | unchanged |  |
| C318 | web/src/pages/ExpectedWins.tsx:110 | unchanged | Section title gains an "early - this is mostly noise" badge when weeksScored < 4 (new, additive). |
| C319 | web/src/pages/ExpectedWins.tsx:126 | unchanged |  |
| C320 | web/src/pages/ExpectedWins.tsx:127 | unchanged |  |
| C321 | web/src/pages/ExpectedWins.tsx:145 | HowThisWorks | Schedule footnote is in the Luck How this works, with the bold "Positive means a harder schedule". |
| C322 | web/src/pages/ExpectedWins.tsx:153 | unchanged |  |
| C323 | web/src/pages/ExpectedWins.tsx:231 | unchanged |  |
| C324 | web/src/pages/ExpectedWins.tsx:253 | unchanged |  |
| C325 | web/src/pages/ExpectedWins.tsx:251 | unchanged |  |
| C326 | web/src/pages/ExpectedWins.tsx:251 | unchanged |  |
| C327 | web/src/pages/SeasonForecast.tsx:157 | reworded + HowThisWorks | Subtitle reworded; "stored simulation / as of the last update" is in How this works, and "Through week N" in the section title still states the as-of week. |
| C328 | web/src/pages/SeasonForecast.tsx:92 | unchanged |  |
| C329 | web/src/pages/SeasonForecast.tsx:109 | unchanged |  |
| C330 | web/src/pages/SeasonForecast.tsx:176 | unchanged |  |
| C331 | web/src/pages/SeasonForecast.tsx:127 | unchanged |  |
| C332 | web/src/pages/SeasonForecast.tsx:138 | unchanged |  |
| C333 | web/src/pages/SeasonForecast.tsx:162 | beside number + HowThisWorks | The range sentence ("middle 80% ... a wide one means the schedule still decides") stays visible under the table; the snapshot/matches-power-rankings sentence is in How this works. |
| C334 | web/src/pages/SeasonForecast.tsx:178 | unchanged |  |
| C335 | web/src/pages/SeasonForecast.tsx:205 | unchanged |  |
| C336 | web/src/pages/SeasonForecast.tsx:199 | unchanged |  |
| C337 | web/src/pages/SeasonForecast.tsx:212 | reworded | Specified by tasks.md T019: refusal now reads "Odds appear when the commissioner updates them."; the original sentence ("...the page reads a stored simulation rather than running one on load") is in a How this works under that refusal. |
| C338 | web/src/pages/SeasonForecast.tsx:202 | unchanged |  |
| C339 | web/src/pages/SeasonForecast.tsx:~233 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C340 | web/src/pages/SeasonForecast.tsx:~261 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C341 | web/src/pages/SeasonForecast.tsx:320 | unchanged |  |
| C342 | web/src/pages/SeasonForecast.tsx:~323 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C343 | web/src/pages/WeeklyReport.tsx:89 | reworded | Subtitle: "Who won each matchup this week, and the awards nobody wants - read back from what actually happened." |
| C344 | web/src/pages/WeeklyReport.tsx:114 | unchanged |  |
| C345 | web/src/pages/WeeklyReport.tsx:119 | unchanged |  |
| C346 | web/src/pages/WeeklyReport.tsx:~122 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C347 | web/src/pages/WeeklyReport.tsx:~131 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C348 | web/src/pages/WeeklyReport.tsx:135 | unchanged |  |
| C349 | web/src/pages/WeeklyReport.tsx:147 | unchanged |  |
| C350 | web/src/pages/WeeklyReport.tsx:164 | unchanged |  |
| C351 | web/src/pages/WeeklyReport.tsx:329 | unchanged |  |
| C352 | web/src/pages/WeeklyReport.tsx:231 | HowThisWorks | Best nights How this works. |
| C353 | web/src/pages/WeeklyReport.tsx:245 | unchanged |  |
| C354 | web/src/pages/WeeklyReport.tsx:~260 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C355 | web/src/pages/WeeklyReport.tsx:277 | unchanged |  |
| C356 | web/src/pages/WeeklyReport.tsx:278 | unchanged |  |
| C357 | web/src/pages/Superlatives.tsx:38 | unchanged |  |
| C358 | web/src/pages/Superlatives.tsx:51 | unchanged |  |
| C359 | web/src/pages/Superlatives.tsx:106 | reworded + HowThisWorks | Subtitle is one sentence; "If an award can't be worked out yet, its card says why" is a How this works under the cards. |
| C360 | web/src/pages/Superlatives.tsx:115 | unchanged |  |
| C361 | web/src/pages/Superlatives.tsx:119 | unchanged |  |
| C362 | web/src/pages/Superlatives.tsx:127 | unchanged |  |
| C363 | web/src/pages/Superlatives.tsx:131 | unchanged |  |
| C364 | web/src/pages/Superlatives.tsx:224 | unchanged |  |
| C365 | web/src/pages/Superlatives.tsx:247 | unchanged |  |
| C366 | web/src/pages/Superlatives.tsx:~261 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C367 | web/src/pages/Superlatives.tsx:~267 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C368 | web/src/pages/Superlatives.tsx:~269 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C369 | web/src/pages/Superlatives.tsx:~289 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C370 | web/src/pages/Superlatives.tsx:~304 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C371 | web/src/pages/Superlatives.tsx:356 | unchanged |  |
| C372 | web/src/pages/Superlatives.tsx:~363 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C373 | web/src/pages/Superlatives.tsx:399 | unchanged |  |
| C374 | web/src/pages/Superlatives.tsx:404 | unchanged |  |
| C375 | web/src/pages/Superlatives.tsx:~410 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C376 | web/src/pages/Superlatives.tsx:~417 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C377 | web/src/pages/Superlatives.tsx:426 | unchanged |  |
| C378 | web/src/pages/Superlatives.tsx:439 | unchanged |  |
| C379 | web/src/pages/Superlatives.tsx:445 | unchanged |  |
| C380 | web/src/pages/Superlatives.tsx:459 | unchanged |  |
| C381 | web/src/pages/Superlatives.tsx:463 | unchanged |  |
| C382 | web/src/pages/Superlatives.tsx:481 | unchanged |  |
| C383 | web/src/pages/Superlatives.tsx:526 | unchanged |  |
| C384 | web/src/pages/Superlatives.tsx:~529 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C385 | web/src/pages/Superlatives.tsx:459 | unchanged |  |
| C386 | web/src/pages/Superlatives.tsx:543 | unchanged |  |
| C387 | web/src/pages/Superlatives.tsx:~566 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C388 | web/src/pages/Superlatives.tsx:591 | unchanged |  |
| C389 | web/src/pages/Superlatives.tsx:~608 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C390 | web/src/pages/Superlatives.tsx:736 | unchanged |  |
| C391 | web/src/pages/Superlatives.tsx:741 | unchanged |  |
| C392 | web/src/pages/Superlatives.tsx:749 | unchanged |  |
| C393 | web/src/pages/Superlatives.tsx:~756 | unchanged | (line approximated from the nearest located row; the code is untouched) |
| C394 | web/src/pages/Superlatives.tsx:763 | unchanged |  |
| C395 | web/src/components/SuperlativeStandingsModal.tsx:88 | unchanged |  |
| C396 | web/src/components/SuperlativeStandingsModal.tsx:~88 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C397 | web/src/components/SuperlativeStandingsModal.tsx:137 | unchanged |  |
| C398 | web/src/components/SuperlativeStandingsModal.tsx:138 | unchanged |  |
| C399 | web/src/components/SuperlativeStandingsModal.tsx:~138 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C400 | web/src/components/SuperlativeStandingsModal.tsx:~138 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C401 | web/src/components/SuperlativeStandingsModal.tsx:185 | unchanged |  |
| C402 | web/src/components/LiveStatusBar.tsx:~65 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C403 | web/src/components/LiveStatusBar.tsx:80 | unchanged |  |
| C404 | web/src/components/LiveStatusBar.tsx:~80 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C405 | web/src/components/LiveStatusBar.tsx:112 | unchanged |  |
| C406 | web/src/components/LiveStatusBar.tsx:126 | unchanged |  |
| C407 | web/src/components/LiveStatusBar.tsx:~126 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C408 | web/src/components/SeatPopover.tsx:~41 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C409 | web/src/components/SeatPopover.tsx:47 | unchanged |  |
| C410 | web/src/components/SeatPopover.tsx:49 | unchanged |  |
| C411 | web/src/components/SeatPopover.tsx:51 | unchanged |  |
| C412 | web/src/components/SeatPopover.tsx:~51 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C413 | web/src/components/SeatPopover.tsx:141 | unchanged |  |
| C414 | web/src/components/SeatPopover.tsx:~141 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C415 | web/src/components/SeatPopover.tsx:156 | unchanged |  |
| C416 | web/src/components/SeatPopover.tsx:175 | unchanged |  |
| C417 | web/src/components/SeatPopover.tsx:~175 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C418 | web/src/components/SeatPopover.tsx:~175 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C419 | web/src/components/SeatPopover.tsx:~175 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C420 | web/src/components/AvailabilityPanel.tsx:~51-53 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C421 | web/src/components/AvailabilityPanel.tsx:196 | unchanged |  |
| C422 | web/src/components/AvailabilityPanel.tsx:220 | unchanged |  |
| C423 | web/src/components/AvailabilityPanel.tsx:230 | unchanged |  |
| C424 | web/src/components/AvailabilityPanel.tsx:~230 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C425 | web/src/components/AvailabilityPanel.tsx:~230 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C426 | web/src/components/AvailabilityPanel.tsx:314 | unchanged |  |
| C427 | web/src/components/AvailabilityPanel.tsx:329 | unchanged |  |
| C428 | web/src/components/AvailabilityPanel.tsx:330 | unchanged |  |
| C429 | web/src/components/PlayerPicker.tsx:91 | unchanged |  |
| C430 | web/src/components/PlayerPicker.tsx:144 | unchanged |  |
| C431 | web/src/components/PlayerPicker.tsx:~144 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C432 | web/src/components/PickFeed.tsx:79 | unchanged |  |
| C433 | web/src/components/PickFeed.tsx:~79 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C434 | web/src/components/PickFeed.tsx:122 | unchanged |  |
| C435 | web/src/components/OnTheClock.tsx:~75 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C436 | web/src/components/OnTheClock.tsx:81 | unchanged |  |
| C437 | web/src/components/OnTheClock.tsx:~81 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C438 | web/src/components/OnTheClock.tsx:114 | unchanged |  |
| C439 | web/src/components/OnTheClock.tsx:125 | unchanged |  |
| C440 | web/src/components/OnTheClock.tsx:~125 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C441 | web/src/components/OnTheClockPickInput.tsx:73 | unchanged |  |
| C442 | web/src/components/OnTheClockPickInput.tsx:138 | unchanged |  |
| C443 | web/src/components/DraftBoard.tsx:97 | unchanged |  |
| C444 | web/src/components/DraftBoard.tsx:~97 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C445 | web/src/components/DraftBoard.tsx:159 | unchanged | Tooltip text unchanged. Visual cue for a second-choice cell changed from a dotted underline to italic; its meaning is still in the tooltip. |
| C446 | web/src/components/PlayerCard.tsx:~35 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C447 | web/src/components/PlayerCard.tsx:54 | unchanged |  |
| C448 | web/src/components/PlayerCard.tsx:60 | unchanged |  |
| C449 | web/src/components/PickPrompt.tsx:38 | unchanged |  |
| C450 | web/src/components/PickPrompt.tsx:49 | unchanged |  |
| C451 | web/src/components/TurnIndicator.tsx:51 | unchanged |  |
| C452 | web/src/components/OnBrandPanel.tsx:9 | unchanged |  |
| C453 | web/src/components/OnBrandPanel.tsx:13 | unchanged |  |
| C454 | web/src/components/OnBrandPanel.tsx:~13 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C455 | web/src/components/OnBrandPanel.tsx:~13 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C456 | web/src/components/OnBrandPanel.tsx:32 | unchanged |  |
| C457 | web/src/components/OnBrandPanel.tsx:45 | unchanged |  |
| C458 | web/src/components/OnBrandPanel.tsx:61 | unchanged |  |
| C459 | web/src/onBrand.ts:~35 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C460 | web/src/components/ScarcityMeter.tsx:26 | unchanged |  |
| C461 | web/src/components/ScarcityMeter.tsx:~26 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C462 | web/src/components/ScarcityMeter.tsx:48 | unchanged |  |
| C463 | web/src/components/ScarcityMeter.tsx:~48 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C464 | web/src/components/ScarcityMeter.tsx:55 | unchanged |  |
| C465 | web/src/components/CommissionerKeyNote.tsx:13 | unchanged |  |
| C466 | web/src/commissionerKey.ts:82 | unchanged |  |
| C467 | web/src/components/NotFound.tsx:4 | unchanged |  |
| C468 | web/src/components/PickInsightCard.tsx:~104 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C469 | web/src/components/PickInsightCard.tsx:~106 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C470 | web/src/components/PickInsightCard.tsx:~111 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C471 | web/src/components/PickInsightCard.tsx:~115 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C472 | web/src/components/PickInsightCard.tsx:118 | unchanged |  |
| C473 | web/src/components/PickInsightCard.tsx:123 | unchanged |  |
| C474 | web/src/components/PickInsightCard.tsx:132 | unchanged |  |
| C475 | web/src/components/PickInsightCard.tsx:~132 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C476 | web/src/components/PickInsightCard.tsx:142 | unchanged |  |
| C477 | web/src/components/PickInsightCard.tsx:151 | unchanged |  |
| C478 | web/src/components/PickInsightCard.tsx:152 | unchanged |  |
| C479 | web/src/components/PickInsightCard.tsx:~152 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C480 | web/src/components/TendenciesForm.tsx:96 | unchanged |  |
| C481 | web/src/components/StartMockModal.tsx:~110-111 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |
| C482 | web/src/components/StartMockModal.tsx:131 | unchanged |  |
| C483 | web/src/components/StartMockModal.tsx:133 | unchanged |  |
| C484 | web/src/components/StartMockModal.tsx:152 | unchanged |  |
| C485 | web/src/components/BumpChart.tsx:263 | unchanged |  |
| C486 | web/src/components/BumpChart.tsx:260 | unchanged |  |
| C487 | web/src/components/RankBoard.tsx:747 | unchanged |  |
| C488 | web/src/components/PageHeader.tsx:~42 | unchanged | (line approximated; text built from parts or a tooltip, code untouched) |

## US9 additions (T084-T093)

No caveat row was removed. New text added in US9, each beside its number or in How this works:
- LeagueHistory How this works: explains "Record vs all (reg. season)" (matches ffwrapped) and "Vs weekly median (reg. season)" (median games only, NOT added to the real record; ffwrapped's "Median record" adds them), that both come from the Luck calculation for one season (other seasons show a dash), and that best/worst marks need a spread. Column headers carry "(reg. season)" and a title.
- Superlatives: every early badge, empty-award message, estimated-points line and "tracking" note is kept on the row. The Embiid total ("N estimated points lost", still says estimated) moved from a holder line to the row's headline figure; it is printed once, not twice. The full standings expand in place (no modal); content unchanged except the modal's repeated early badge and coverage line, which the row above already shows.
- ManagerHistory: the player card states "over N seasons" under every figure (FR-007/SC-008); each rank is a sentence that still names its population and league (FR-008).
- ManagerTendencies / MockSetup: the archetype label's basis is in its title and, for tilt-based labels, the existing "no reach number" reason stays on the row (reach-gap line) or in the seat's hint.

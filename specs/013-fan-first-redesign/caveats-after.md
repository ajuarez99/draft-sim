# Caveats inventory, AFTER the 013 fan-first redesign

Maps each `caveats-before.md` row to where it lives now. Line numbers are searched from the current source by the row text, so a number is the first line carrying that text; "unchanged" means the text and condition are as before (the surrounding section moved from `.panel` to `.section`).

Covered so far: T014-T021 (DraftPicker, PowerRankings, LeagueAnalysis, RosterManagement, ExpectedWins, SeasonForecast, WeeklyReport, Superlatives). Rows for other files are added by later US1 tasks. No shared component was edited (HowThisWorks is Phase 2).

| C### | new location (file:line) | how (beside number / HowThisWorks / unchanged / reworded) | note |
|---|---|---|---|
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

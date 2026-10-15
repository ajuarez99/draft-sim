package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.ingest.espn.EspnFixtures.list;
import static com.ballknowers.draftsim.ingest.espn.EspnFixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Over the hand-built synthetic payload (research R9's shapes, unverified until T053). */
class EspnTranslatorTest {

    private static final EspnLeagueRef REF = EspnLeagueRef.of("9990001", Sport.NBA, 2026);

    private static EspnSeason translate(Map<String, Object> payload) {
        return EspnTranslator.translate(payload, REF);
    }

    private static Map<String, Object> settings(Map<String, Object> p) {
        return map(p.get("settings"));
    }

    @Test
    void aCategoryLeagueIsRefusedNamingTheType() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        map(settings(p).get("scoringSettings")).put("scoringType", "H2H_CATEGORY");
        EspnUnsupported e = assertThrows(EspnUnsupported.class, () -> translate(p));
        assertEquals("UNSUPPORTED_SCORING", e.code());
        assertEquals("H2H_CATEGORY", e.detail());
    }

    @Test
    void aMissingTeamNameFailsLoudlyNamingTheField() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        Map<String, Object> team = list(p.get("teams")).get(0);
        team.remove("name");
        EspnShapeChanged e = assertThrows(EspnShapeChanged.class, () -> translate(p));
        assertEquals("teams[].name", e.fieldPath());
    }

    @Test
    void locationAndNicknameAreAcceptedInPlaceOfAName() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals("Team Charlie", s.teams().get(2).teamName());
    }

    @Test
    void identityAndKeysUseTheAppSeasonAndLinkThePreviousSeason() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals("espn-fba-9990001-2026", s.key());
        assertEquals(2026, s.appSeason());
        assertEquals("espn-fba-9990001-2025", s.previousKey(), "ESPN 2026 is app 2025");
        assertEquals("in_season", s.status());
        assertFalse(s.complete());
    }

    @Test
    void championOnlyWhenTheSeasonIsComplete() {
        EspnSeason live = translate(EspnFixtures.nbaLeague());
        assertTrue(live.teams().stream().allMatch(t -> t.finalPlacement() == null));

        Map<String, Object> past = EspnFixtures.nbaLeaguePreviousSeason();
        EspnSeason done = EspnTranslator.translate(past, REF.withSeason(2025));
        assertTrue(done.complete());
        assertEquals(1, done.teams().get(0).finalPlacement());
        assertNull(done.teams().get(1).finalPlacement());

        // The same rank-1 on a season that is still being played is NOT a champion.
        Map<String, Object> stillOn = EspnFixtures.nbaLeague();
        list(stillOn.get("teams")).get(0).put("rankCalculatedFinal", 1);
        assertNull(translate(stillOn).teams().get(0).finalPlacement());
    }

    @Test
    void undecidedPeriodsHaveNoPointsButEveryPeriodKeepsItsPairings() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals(3, s.periods().size());
        EspnSeason.Period p1 = s.periods().get(0);
        assertTrue(p1.matchups().stream().allMatch(EspnSeason.Matchup::decided));
        assertEquals(100.5, p1.matchups().get(0).home().points());
        EspnSeason.Period p2 = s.periods().get(1);
        assertTrue(p2.matchups().stream().noneMatch(EspnSeason.Matchup::decided), "winner UNDECIDED");
        assertEquals(2, p2.matchups().size(), "the pairings are still there for the schedule");
    }

    @Test
    void aByeHasNoAwaySide() {
        EspnSeason.Period p3 = translate(EspnFixtures.nbaLeague()).periods().get(2);
        EspnSeason.Matchup bye = p3.matchups().get(1);
        assertNull(bye.away());
        assertEquals(3, bye.home().teamId());
    }

    @Test
    void basketballWeekIsTheMatchupPeriodAndFootballWeekIsTheScoringPeriod() {
        assertEquals(List.of(1, 2, 3), translate(EspnFixtures.nbaLeague()).periods().stream().map(EspnSeason.Period::week).toList());

        // NFL: the matchup period "5" is NFL week 5 (scoring period 5): one scoring period per matchup period.
        Map<String, Object> nfl = EspnFixtures.nbaLeague();
        map(settings(nfl).get("scheduleSettings")).put("matchupPeriods", Map.of("1", List.of(2), "2", List.of(3), "3", List.of(4)));
        settings(nfl).put("rosterSettings", Map.of("lineupSlotCounts", Map.of("0", 1, "20", 1)));
        EspnSeason s = EspnTranslator.translate(nfl, EspnLeagueRef.of("9990001", Sport.NFL, 2026));
        assertEquals(List.of(2, 3, 4), s.periods().stream().map(EspnSeason.Period::week).toList());
        assertFalse(s.multiWeekPeriods());
    }

    @Test
    void aFootballPeriodSpanningTwoScoringPeriodsMarksTheSeasonMultiWeek() {
        Map<String, Object> nfl = EspnFixtures.nbaLeague();
        map(settings(nfl).get("scheduleSettings")).put("matchupPeriods", Map.of("1", List.of(1, 2), "2", List.of(3), "3", List.of(4)));
        EspnSeason s = EspnTranslator.translate(nfl, EspnLeagueRef.of("9990001", Sport.NFL, 2026));
        assertTrue(s.multiWeekPeriods());
    }

    @Test
    void anUnmodelledSlotIsListedAndIrIsNotARosterPosition() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        map(map(settings(p).get("rosterSettings")).get("lineupSlotCounts")).put("7", 1);   // SG/SF combo slot
        EspnSeason s = translate(p);
        assertEquals(List.of("SG/SF"), s.unmodelledSlots());
        assertEquals(List.of("PG", "BN", "BN"), s.rosterPositions(), "IR is the reserve list, not a roster position");
    }

    @Test
    void theSeedingRuleIsZeroOnlyForThePlainLadderAndMinusOneOtherwise() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        assertEquals(0, translate(p).settings().get("playoff_seed_type"));
        map(settings(p).get("scheduleSettings")).put("playoffSeedingRule", "TOTAL_POINTS_SCORED");
        assertEquals(-1, translate(p).settings().get("playoff_seed_type"));
        map(settings(p).get("scheduleSettings")).remove("playoffSeedingRule");
        assertEquals(-1, translate(p).settings().get("playoff_seed_type"), "absent is never read as the plain rule");
    }

    @Test
    void settingsAreDerivedNotDefaulted() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        Map<String, Object> out = translate(p).settings();
        assertEquals(2, out.get("leg"));
        assertEquals(2, out.get("playoff_teams"));
        assertEquals(4, out.get("playoff_week_start"));
        assertEquals(0, out.get("playoff_round_type"));
        assertEquals(0, out.get("divisions"));
        assertEquals(2, out.get("waiver_type"));
        assertEquals(100.0, out.get("waiver_budget"));
        map(settings(p).get("scheduleSettings")).put("playoffMatchupPeriodLength", 2);
        assertEquals(-1, translate(p).settings().get("playoff_round_type"));
        map(settings(p).get("scheduleSettings")).put("divisions", List.of(Map.of("id", 0), Map.of("id", 1)));
        assertEquals(2, translate(p).settings().get("divisions"));
    }

    @Test
    void aSingleDefaultDivisionIsNoDivisionsAtAll() {
        // Code review S8: the fixture now carries one default division, the shape ESPN most likely sends for
        // a league without divisions (unverified, T053). It must not make the league read as divisional.
        Map<String, Object> p = EspnFixtures.nbaLeague();
        assertEquals(1, EspnFixtures.list(map(settings(p).get("scheduleSettings")).get("divisions")).size());
        assertEquals(0, translate(p).settings().get("divisions"));
    }

    @Test
    void anUnknownWaiverRuleIsMinusOne() {
        Map<String, Object> p = EspnFixtures.nbaLeague();
        settings(p).put("acquisitionSettings", Map.of("isUsingAcquisitionBudget", false, "acquisitionType", "SOMETHING_NEW"));
        assertEquals(-1, translate(p).settings().get("waiver_type"));
    }

    @Test
    void scoringIsUntranslatedUntilAnEntryIsConfirmed() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals(List.of("stat:0", "stat:6"), s.scoringUntranslated());
        assertTrue(s.scoring().isEmpty(), "an empty rule set would score everyone 0, so none is written");
    }

    @Test
    void positionsAreLabelledAndPositionsTheAppDoesNotModelAreOtherN() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals("PG", s.teams().get(0).entries().get(0).position());
        assertEquals("OTHER_99", s.teams().get(2).entries().get(0).position());
    }

    @Test
    void ownersAndCoOwnersAreRead() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals("{IT035-C}", s.teams().get(2).ownerMemberId());
        assertEquals(List.of("{IT035-D}"), s.teams().get(2).coOwners());
        assertTrue(s.members().get(0).commissioner());
        assertFalse(s.members().get(1).commissioner());
    }

    @Test
    void theDraftIsReadWithItsSlotOrder() {
        EspnSeason.Draft d = translate(EspnFixtures.nbaLeague()).draft();
        assertEquals("snake", d.type());
        assertEquals(List.of(1, 2, 3, 4), d.pickOrder());
        assertEquals(3, d.picks().size());
        assertEquals(1, d.rounds());
        assertEquals("complete", d.status());
    }

    @Test
    void everyRequiredFieldMissingIsAShapeErrorNotANull() {
        for (String path : new String[] {"settings", "status", "members", "teams", "schedule", "draftDetail"}) {
            Map<String, Object> p = EspnFixtures.nbaLeague();
            p.remove(path);
            assertThrows(EspnShapeChanged.class, () -> translate(p), path);
        }
        Map<String, Object> p = EspnFixtures.nbaLeague();
        map(p.get("status")).remove("currentMatchupPeriod");
        assertEquals("status.currentMatchupPeriod", assertThrows(EspnShapeChanged.class, () -> translate(p)).fieldPath());
    }

    @Test
    void anOwnerThatIsNotAListedMemberIsCaughtWhereTheRowsAreWritten() {
        // The translator itself does not cross-check members; the ingest does (EspnShapeChanged teams[].owners).
        Map<String, Object> p = EspnFixtures.nbaLeague();
        list(p.get("teams")).get(0).put("primaryOwner", "{NOBODY}");
        assertEquals("{NOBODY}", translate(p).teams().get(0).ownerMemberId());
    }

    // ---- transactions (T039) -------------------------------------------------------------------------

    @Test
    void transactionsMapToTheStoredTypeSetAndSkipWhatIsNotARecordedMove() {
        EspnSeason.TxResult r = EspnTranslator.transactions(EspnFixtures.nbaTransactions(), 1, true);
        assertEquals(4, r.transactions().size());
        // proposal, lineup-only and pending waiver: skipped and counted, with their TYPE kept for the log.
        assertEquals(3, r.skipped());
        assertTrue(r.skippedTypes().containsAll(List.of("TRADE_PROPOSAL/status", "ROSTER/lineup-only", "WAIVER/status")), r.skippedTypes().toString());

        EspnSeason.Tx waiver = r.transactions().get(0);
        assertEquals("WAIVER", waiver.type());
        assertEquals("complete", waiver.status());
        assertEquals(2, waiver.rosterId());
        assertEquals(Map.of("9990002", 2), waiver.adds());
        assertEquals(Map.of("9990006", 2), waiver.drops());
        assertEquals(12, waiver.faabBid());

        assertEquals("FREE_AGENT", r.transactions().get(1).type());
        assertNull(r.transactions().get(1).faabBid(), "a free-agent add carries no bid");

        EspnSeason.Tx trade = r.transactions().get(2);
        assertEquals("TRADE", trade.type());
        assertNull(trade.rosterId(), "a trade has no single actor");
        assertEquals(Map.of("9990001", 2, "9990004", 1), trade.adds(), "received by");
        assertEquals(Map.of("9990001", 1, "9990004", 2), trade.drops(), "given by");

        EspnSeason.Tx failed = r.transactions().get(3);
        assertEquals("failed", failed.status());
        assertEquals(5, failed.faabBid(), "a failed bid is still a row, and keeps its bid");
    }

    @Test
    void aBidIsKeptOnlyInAFaabLeague() {
        EspnSeason.TxResult r = EspnTranslator.transactions(EspnFixtures.nbaTransactions(), 1, false);
        assertTrue(r.transactions().stream().allMatch(t -> t.faabBid() == null), "a non-FAAB league stores no bid");
    }

    @Test
    void aMissingTransactionsListIsAShapeErrorNotAnEmptyWeek() {
        Map<String, Object> p = EspnFixtures.nbaTransactions();
        p.remove("transactions");
        assertEquals("transactions", assertThrows(EspnShapeChanged.class, () -> EspnTranslator.transactions(p, 1, true)).fieldPath());
    }

    @Test
    void aMoveWithoutATypeOrAnIdFailsLoudly() {
        Map<String, Object> p = EspnFixtures.nbaTransactions();
        list(p.get("transactions")).get(0).remove("type");
        assertEquals("transactions[].type", assertThrows(EspnShapeChanged.class, () -> EspnTranslator.transactions(p, 1, true)).fieldPath());
        Map<String, Object> q = EspnFixtures.nbaTransactions();
        list(q.get("transactions")).get(0).remove("id");
        assertEquals("transactions[].id", assertThrows(EspnShapeChanged.class, () -> EspnTranslator.transactions(q, 1, true)).fieldPath());
    }

    @Test
    void theTransactionsFetchAsksForTheLastDayOfEachMatchupPeriod() {
        EspnSeason s = translate(EspnFixtures.nbaLeague());
        assertEquals(Map.of(1, 7, 2, 14, 3, 21), s.txScoringPeriodByMatchupPeriod());
    }

    @Test
    void nflMatchupPeriodsThatSpanSeveralWeeksAreFlaggedInTheStoredSettings() {
        EspnLeagueRef nfl = EspnLeagueRef.of("9990001", Sport.NFL, 2026);
        Map<String, Object> single = EspnFixtures.nbaLeague();
        map(map(settings(single).get("scheduleSettings"))).put("matchupPeriods",
                Map.of("1", List.of(1), "2", List.of(2), "3", List.of(3)));
        assertFalse(EspnTranslator.translate(single, nfl).settings().containsKey("espn_multi_week_periods"),
                "one scoring period per matchup period is an NFL week");
        assertFalse(EspnTranslator.translate(single, nfl).multiWeekPeriods());

        Map<String, Object> multi = EspnFixtures.nbaLeague();
        map(map(settings(multi).get("scheduleSettings"))).put("matchupPeriods",
                Map.of("1", List.of(1, 2), "2", List.of(3, 4), "3", List.of(5)));
        EspnSeason s = EspnTranslator.translate(multi, nfl);
        assertTrue(s.multiWeekPeriods());
        assertEquals(Boolean.TRUE, s.settings().get("espn_multi_week_periods"));

        // NBA periods are always several days long; that is not a flag.
        assertFalse(translate(EspnFixtures.nbaLeague()).settings().containsKey("espn_multi_week_periods"));
    }
}

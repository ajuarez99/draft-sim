package com.ballknowers.draftsim.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 046 T019 / F16: the Yahoo client against the two REAL pages captured 2026-10-23 (start=0 and start=175),
 * the exact URL it builds, and its stop / cap / shape rules. No network.
 */
class YahooAdpClientTest {

    private final YahooAdpClient client = new YahooAdpClient();

    private static String fixture(String name) throws Exception {
        try (InputStream in = YahooAdpClientTest.class.getResourceAsStream("/fixtures/046/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theBuiltUrlIsExactlyTheOneVerifiedAgainstTheRealEndpoint() {
        assertEquals("https://pub-api-ro.fantasysports.yahoo.com/fantasy/v2/league/nba.l.public"
                        + ";out=settings/players;position=ALL;start=0;count=25;sort=DA_AP;out=draft_analysis?format=json_f",
                YahooAdpClient.pageUrl(0));
        assertEquals("https://pub-api-ro.fantasysports.yahoo.com/fantasy/v2/league/nba.l.public"
                        + ";out=settings/players;position=ALL;start=175;count=25;sort=DA_AP;out=draft_analysis?format=json_f",
                YahooAdpClient.pageUrl(175));
    }

    @Test
    void theUrlSurvivesUriParsingWithItsMatrixParametersAndSlashIntact() {
        // The client hands RestClient a java.net.URI, so what the server receives is what this prints.
        URI uri = URI.create(YahooAdpClient.pageUrl(25));
        assertEquals("/fantasy/v2/league/nba.l.public;out=settings/players;position=ALL;start=25;count=25;sort=DA_AP;out=draft_analysis",
                uri.getRawPath());
        assertEquals("format=json_f", uri.getRawQuery(), "without json_f the endpoint answers XML");
    }

    @Test
    void parsesPageZeroUsingTheAverageFieldsNotThePreseasonOnes() throws Exception {
        List<YahooAdpClient.Player> page = client.parsePage(fixture("yahoo-nba-page-0.json"));

        assertEquals(25, page.size());
        YahooAdpClient.Player jokic = page.get(0);
        assertEquals("Nikola Jokić", jokic.name());
        assertEquals("DEN", jokic.team());
        assertEquals("C", jokic.position());
        // draft_analysis: average_pick "1.4", average_cost "77.0", percent_drafted "1.00";
        // the preseason_average_cost beside it is "77.9" and must NOT be read.
        assertEquals(1.4, jokic.averagePick());
        assertEquals(77.0, jokic.averageCost());
        assertEquals(1.00, jokic.percentDrafted());
        assertEquals("PG,SG", page.get(2).position(), "multi-position stays one comma list for the matcher to split");
    }

    @Test
    void parsesPageOneSeventyFiveWhereTheRankedListEndsMidPage() throws Exception {
        List<YahooAdpClient.Player> page = client.parsePage(fixture("yahoo-nba-page-175.json"));

        // The real tail: 10 ranked entries (119.6 .. 123.8), then 15 whose average_pick, cost and share are all "-".
        assertEquals(10, page.size());
        assertEquals(123.8, page.get(9).averagePick());
        YahooAdpClient.Player p = page.get(0);
        assertEquals(119.6, p.averagePick());
        assertEquals(1.8, p.averageCost());
        assertEquals(0.17, p.percentDrafted());
    }

    @Test
    void aDashIsNullNeverZeroAndANonNumericPickDropsTheEntry() throws Exception {
        assertNull(YahooAdpClient.number(new ObjectMapper().readTree("\"-\"")));
        assertNull(YahooAdpClient.number(new ObjectMapper().readTree("\"\"")));
        assertNull(YahooAdpClient.number(new ObjectMapper().readTree("\"abc\"")));
        assertNull(YahooAdpClient.number(new ObjectMapper().readTree("null")));
        assertEquals(0.0, YahooAdpClient.number(new ObjectMapper().readTree("\"0.0\"")), "a real zero stays a zero");

        String body = """
                {"fantasy_content":{"league":{"players":[
                  {"player":{"name":{"full":"Ranked Guy"},"editorial_team_abbr":"BOS","display_position":"PG",
                    "draft_analysis":{"average_pick":"10.0","average_cost":"-","percent_drafted":"0.9"}}},
                  {"player":{"name":{"full":"Unranked Guy"},"editorial_team_abbr":"BOS","display_position":"PG",
                    "draft_analysis":{"average_pick":"-","average_cost":"3.0","percent_drafted":"0.0"}}},
                  {"count":2}
                ]}}}
                """;
        List<YahooAdpClient.Player> page = client.parsePage(body);

        assertEquals(1, page.size());
        assertEquals("Ranked Guy", page.get(0).name());
        assertNull(page.get(0).averageCost(), "\"-\" cost is null, not 0");
    }

    @Test
    void aResponseWithoutLeaguePlayersIsRejectedAsAShapeChange() {
        assertThrows(IllegalStateException.class, () -> client.parsePage("{\"fantasy_content\":{\"league\":{}}}"));
        assertThrows(IllegalStateException.class, () -> client.parsePage("{\"fantasy_content\":{\"error\":\"x\"}}"));
        assertThrows(IllegalStateException.class, () -> client.parsePage("<xml>not json</xml>"));
    }

    @Test
    void stopsAtTheFirstPageWithNoNumericAveragePick() throws Exception {
        String ranked = fixture("yahoo-nba-page-0.json");
        String unranked = ranked.replaceAll("\"average_pick\"\\s*:\\s*\"[^\"]*\"", "\"average_pick\":\"-\"");
        List<Integer> asked = new ArrayList<>();

        List<YahooAdpClient.Player> all = client.fetchAll(start -> {
            asked.add(start);
            return start < 50 ? ranked : unranked;
        });

        assertEquals(List.of(0, 25, 50), asked, "page 50 had nothing ranked, so no page 75 is requested");
        assertEquals(50, all.size());
    }

    @Test
    void neverReadsMoreThanFourHundredPlayersWhateverThePagesSay() throws Exception {
        String ranked = fixture("yahoo-nba-page-0.json");
        List<Integer> asked = new ArrayList<>();

        List<YahooAdpClient.Player> all = client.fetchAll(start -> {
            asked.add(start);
            return ranked;   // a feed that never ends
        });

        assertEquals(400, all.size());
        assertEquals(16, asked.size());
    }

    @Test
    void aFailingPageFailsTheWholeFetchSoThePreviousCaptureStays() {
        assertThrows(IllegalStateException.class, () -> client.fetchAll(start -> {
            if (start == 25) throw new IllegalStateException("timeout");
            try {
                return fixture("yahoo-nba-page-0.json");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }
}

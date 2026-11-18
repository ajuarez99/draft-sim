package com.ballknowers.draftsim.usage;

import com.ballknowers.draftsim.usage.UsageVocabulary.Result;
import com.ballknowers.draftsim.usage.UsageVocabulary.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageVocabularyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UsageVocabulary real = new UsageVocabulary();

    private static JsonNode body(String s) throws Exception {
        return JSON.readTree(s);
    }

    private static UsageVocabulary from(String json) {
        return new UsageVocabulary(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRealResourceLoadsWithRoutesForEverySiteAndPropsForEveryEvent() {
        assertTrue(real.loaded(), "usage-vocabulary.json must be on the classpath and parse");
        for (String site : new String[]{"timemachine", "leagues", "combined"}) {
            assertTrue(real.knowsSite(site), site);
            assertFalse(real.routes(site).isEmpty(), site + " has routes");
        }
        assertEquals(java.util.Set.of("sport", "who", "daily"), real.events().get("result-shared").keySet());
        assertEquals(java.util.Set.of("via"), real.events().get("league-added").keySet());
        assertTrue(real.events().get("sign-in").isEmpty(), "sign-in has no props");
    }

    @Test
    void aKnownRouteIsAcceptedAndAnUnknownOneIsRejectedForThatSite() throws Exception {
        String route = real.routes("leagues").stream().sorted().findFirst().orElseThrow();
        Result ok = real.validate(body("{\"kind\":\"view\",\"site\":\"leagues\",\"route\":\"" + route + "\"}"));
        assertEquals(Status.OK, ok.status());
        assertEquals(route, ok.route());
        assertEquals("view", ok.kind());

        Result bad = real.validate(body("{\"site\":\"leagues\",\"route\":\"/not/a/route\"}"));
        assertEquals(Status.REJECTED, bad.status());
        assertEquals("leagues", bad.site());
    }

    @Test
    void anUnknownOrMissingSiteIsIgnoredNotRejected() throws Exception {
        assertEquals(Status.IGNORED, real.validate(body("{\"site\":\"evil\",\"route\":\"/\"}")).status());
        assertEquals(Status.IGNORED, real.validate(body("{\"route\":\"/\"}")).status());
        assertEquals(Status.IGNORED, real.validate(body("{\"site\":5,\"route\":\"/\"}")).status());
        assertEquals(Status.IGNORED, real.validate(body("[1,2]")).status());
        assertEquals(Status.IGNORED, real.validate(null).status());
    }

    @Test
    void eventsAreRebuiltFromVocabularyValuesWithKeysSorted() throws Exception {
        Result r = real.validate(body("""
                {"kind":"event","site":"timemachine","name":"result-shared",
                 "props":{"who":"guest","sport":"nba","daily":"yes"}}"""));
        assertEquals(Status.OK, r.status());
        assertEquals("result-shared", r.name());
        assertEquals("daily=yes;sport=nba;who=guest", r.props(), "keys sorted, k=v joined by ;");
        // the same event written in another key order gives the identical canonical form
        Result again = real.validate(body("""
                {"site":"timemachine","name":"result-shared","props":{"daily":"yes","sport":"nba","who":"guest"}}"""));
        assertEquals(r.props(), again.props());
    }

    @Test
    void anEventWithAnUnknownNameKeyValueOrAnExtraOrMissingPropIsRejected() throws Exception {
        String[] bad = {
                "{\"site\":\"timemachine\",\"name\":\"nope\",\"props\":{}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"via\":\"carrier-pigeon\"}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"via\":\"espn\",\"extra\":\"x\"}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"other\":\"espn\"}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"via\":5}}",
                "{\"site\":\"timemachine\",\"name\":\"sign-in\",\"props\":{\"x\":\"y\"}}",
                "{\"site\":\"timemachine\",\"name\":\"sign-in\",\"props\":\"x\"}",
                "{\"site\":\"timemachine\",\"name\":\"sign-in\",\"route\":\"/\"}",
                "{\"site\":\"timemachine\",\"name\":7}",
        };
        for (String b : bad) assertEquals(Status.REJECTED, real.validate(body(b)).status(), b);
        // an event with no props at all is the no-props event
        assertEquals(Status.OK, real.validate(body("{\"site\":\"timemachine\",\"name\":\"sign-in\"}")).status());
        // the client always sends props: {} for sign-in: it must be accepted, not merely have empty props
        Result signIn = real.validate(body("{\"site\":\"timemachine\",\"name\":\"sign-in\",\"props\":{}}"));
        assertEquals(Status.OK, signIn.status());
        assertEquals("", signIn.props());
        assertEquals("sign-in", signIn.name());
    }

    @Test
    void theLiteralBodiesTheClientBuildersProduceAreAccepted() throws Exception {
        // the shapes analytics.ts sends (it adds "kind" to every body)
        Result e = real.validate(body("{\"kind\":\"event\",\"site\":\"combined\",\"name\":\"sign-in\",\"props\":{}}"));
        assertEquals(Status.OK, e.status());
        assertEquals("event", e.kind());
        assertEquals("sign-in", e.name());
        String route = real.routes("timemachine").stream().sorted().findFirst().orElseThrow();
        Result v = real.validate(body("{\"kind\":\"view\",\"site\":\"timemachine\",\"route\":\"" + route + "\"}"));
        assertEquals(Status.OK, v.status());
        assertEquals(route, v.route());
    }

    @Test
    void aLeagueViewNeedsAKnownRouteAndATidyLeagueId() throws Exception {
        String route = real.routes("leagues").stream().sorted().findFirst().orElseThrow();
        Result ok = real.validate(body("{\"kind\":\"league-view\",\"site\":\"leagues\",\"route\":\"" + route
                + "\",\"league\":\"1234567890\"}"));
        assertEquals(Status.OK, ok.status());
        assertEquals("1234567890", ok.leagueId());
        assertEquals("league-view", ok.kind());
        for (String league : new String[]{"", "has space", "a'; drop table x;--", "x".repeat(65)}) {
            Result r = real.validate(body("{\"site\":\"leagues\",\"route\":\"" + route + "\",\"league\":\"" + league + "\"}"));
            assertEquals(Status.REJECTED, r.status(), "league id: " + league);
        }
        assertEquals(Status.REJECTED, real.validate(body("{\"site\":\"leagues\",\"route\":\"" + route
                + "\",\"league\":12}")).status());
    }

    @Test
    void aDisagreeingKindOrAnExtraFieldIsRejected() throws Exception {
        String route = real.routes("leagues").stream().sorted().findFirst().orElseThrow();
        assertEquals(Status.REJECTED, real.validate(body("{\"kind\":\"event\",\"site\":\"leagues\",\"route\":\"" + route + "\"}")).status());
        assertEquals(Status.REJECTED, real.validate(body("{\"site\":\"leagues\",\"route\":\"" + route + "\",\"ua\":\"x\"}")).status());
    }

    @Test
    void aBadOrMissingResourceFailsSoftAndRefusesEverything() throws Exception {
        for (UsageVocabulary v : new UsageVocabulary[]{
                new UsageVocabulary((java.io.InputStream) null),
                from("not json at all"),
                from("{\"sites\": 3}"),
                from("{\"sites\":{\"leagues\":[1]},\"events\":{}}"),
                from("{\"sites\":{\"leagues\":[\"/\"]},\"events\":{\"e\":{\"p\":\"notarray\"}}}")}) {
            assertFalse(v.loaded());
            assertEquals(Status.IGNORED, v.validate(body("{\"site\":\"leagues\",\"route\":\"/\"}")).status());
        }
    }
}

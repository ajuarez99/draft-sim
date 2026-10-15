package com.ballknowers.draftsim.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.ballknowers.draftsim.ingest.espn.EspnAccessDenied;
import com.ballknowers.draftsim.ingest.espn.EspnIT;
import com.ballknowers.draftsim.ingest.espn.EspnLeagueIngestService;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Spec 035 plan F14 / T049. A sentinel credential goes through every ESPN code path that touches cookies
 * (add, replace, delete, a rejected read, a failing refresh whose exception message carries the cookies)
 * with logging at DEBUG (the {@code LOG_LEVEL=DEBUG} default, plus root at DEBUG). The sentinel must appear
 * in no captured log line (message or stack trace), no response body, no {@code league_refresh} row and
 * not in the stored ciphertext.
 *
 * <p>What this does NOT cover: the real {@code EspnClient} on the wire (see {@code EspnClientHttpTest}),
 * and a live ESPN. Spring's own {@code spring.mvc.log-request-details} is left at its default (off).
 */
@TestPropertySource(properties = {"LOG_LEVEL=DEBUG", "logging.level.root=DEBUG"})
class CredentialLeakIT extends EspnIT {

    @Autowired EspnLeagueIngestService espn;
    @Autowired LeagueRepository leagues;

    private ListAppender<ILoggingEvent> appender;
    private Level rootBefore;

    @BeforeEach
    void captureLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootBefore = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        root.setLevel(rootBefore);
    }

    private static boolean leaks(String s) {
        return s != null && (s.contains("SENTINEL_S2") || s.contains("SENTINEL-SWID"));
    }

    @Test
    void theSentinelAppearsNowhereAfterEveryCookieTouchingPath() throws Exception {
        privateLeague = true;
        List<String> bodies = new ArrayList<>();

        bodies.add(add(ALLAN, 1, true).andReturn().getResponse().getContentAsString());
        String put = "{\"espnS2\":\"" + SENTINEL_S2 + "\",\"swid\":\"" + SENTINEL_SWID + "\"}";
        bodies.add(mvc.perform(put("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN)
                .contentType(MediaType.APPLICATION_JSON).content(put)).andReturn().getResponse().getContentAsString());

        // ESPN rejects the cookies: the 403 body must not echo them.
        doThrow(new EspnAccessDenied()).when(client).league(eq("fba"), eq(LEAGUE_ID), anyInt(), anySet(), isNotNull());
        bodies.add(mvc.perform(put("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN)
                .contentType(MediaType.APPLICATION_JSON).content(put)).andReturn().getResponse().getContentAsString());
        // And a malformed one (a ';' could break the Cookie header): rejected without echoing the value.
        bodies.add(mvc.perform(put("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"espnS2\":\"" + SENTINEL_S2 + ";bad\",\"swid\":\"" + SENTINEL_SWID + "\"}")).andReturn().getResponse().getContentAsString());

        // A failing refresh whose exception message carries the cookies (a hostile or sloppy dependency).
        doThrow(new IllegalStateException("upstream said: " + SENTINEL_S2 + " / " + SENTINEL_SWID)).when(client)
                .league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any());
        assertFalse(espn.refreshCurrent(leagues.bySleeperId(LEAGUE_KEY).orElseThrow()));

        bodies.add(mvc.perform(delete("/api/setup/espn/league/" + LEAGUE_KEY + "/credential").header("X-Sleeper-User", ALLAN))
                .andReturn().getResponse().getContentAsString());

        // Not vacuous: the cookies did reach the (stubbed) client, and plenty was logged.
        verify(client, org.mockito.Mockito.atLeastOnce()).league(anyString(), anyString(), anyInt(), anySet(), isNotNull());
        assertTrue(appender.list.stream().anyMatch(e -> e.getLoggerName().startsWith("com.ballknowers.draftsim")),
                "no application log lines were captured: the check would be vacuous");

        for (String body : bodies) assertFalse(leaks(body), "a response body leaked: " + body);
        for (ILoggingEvent e : appender.list) {
            assertFalse(leaks(e.getFormattedMessage()), "a log message leaked: " + e.getLoggerName() + ": " + e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                assertFalse(leaks(ThrowableProxyUtil.asString(e.getThrowableProxy())), "a stack trace leaked via " + e.getLoggerName());
            }
            for (Object arg : e.getArgumentArray() == null ? new Object[0] : e.getArgumentArray()) {
                assertFalse(leaks(String.valueOf(arg)), "a log argument leaked via " + e.getLoggerName());
            }
        }
        List<String> failures = jdbc.queryForList("select coalesce(last_failure, '') from league_refresh r join league l on l.id = r.league_id "
                + "where l.platform_league_id = ?", String.class, LEAGUE_ID);
        assertTrue(failures.stream().anyMatch(f -> f.equals(EspnLeagueIngestService.REFRESH_ERROR)), "the refresh failure was recorded: " + failures);
        for (String f : failures) assertFalse(leaks(f), "league_refresh leaked: " + f);
        assertEquals(0, count("select count(*) from league_refresh where last_failure like '%SENTINEL%'"));
    }

    @Test
    void theStoredCiphertextIsNotThePlaintext() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andReturn();
        byte[] sealed = jdbc.queryForObject("select ciphertext from league_credential where platform_league_id = ?", byte[].class, LEAGUE_ID);
        assertFalse(leaks(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)));
    }
}

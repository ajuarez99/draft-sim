package com.ballknowers.draftsim.ingest.espn;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 035 plan F14: the REAL {@link EspnClient} on a real socket (a local fake ESPN), with a sentinel
 * credential. It sends the cookies to the configured host, never follows a redirect to another host, and
 * no exception or log line it produces carries the cookies.
 *
 * <p>This is the wire-level half of {@code CredentialLeakIT}, which stubs the client. It is still a FAKE
 * server: it proves what this client does with cookies, not what ESPN does.
 */
class EspnClientHttpTest {

    private static final String S2 = "SENTINEL_S2_abcdef0123456789";
    private static final String SWID = "{SENTINEL-SWID-0000}";

    private HttpServer espn;
    private HttpServer elsewhere;
    private final List<String> espnCookies = new CopyOnWriteArrayList<>();
    private final List<String> elsewhereCookies = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private int status;
    private String location;

    @BeforeEach
    void start() throws Exception {
        elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        elsewhere.createContext("/", ex -> {
            elsewhereCookies.add(String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        elsewhere.start();
        espn = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        espn.createContext("/", ex -> {
            espnCookies.add(String.valueOf(ex.getRequestHeaders().getFirst("Cookie")));
            if (location != null) ex.getResponseHeaders().add("Location", location);
            byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) ex.getResponseBody().write(body);
            ex.close();
        });
        espn.start();
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        espn.stop(0);
        elsewhere.stop(0);
    }

    private EspnClient client() {
        // "localhost" is the configured host; the redirect target below is 127.0.0.1, a different one.
        return new EspnClient("http://localhost:" + espn.getAddress().getPort() + "/apis/v3");
    }

    private static boolean leaks(String s) {
        return s != null && (s.contains("SENTINEL_S2") || s.contains("SENTINEL-SWID"));
    }

    private static String everything(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return t + " | " + t.getMessage() + " | " + w;
    }

    private void assertNoLogLeak() {
        for (ILoggingEvent e : logs.list) {
            assertFalse(leaks(e.getFormattedMessage()), e.getLoggerName() + ": " + e.getFormattedMessage());
            if (e.getThrowableProxy() != null) assertFalse(leaks(ThrowableProxyUtil.asString(e.getThrowableProxy())));
        }
    }

    @Test
    void theCookiesAreSentToTheConfiguredHostOnlyWhenACredentialIsGiven() {
        status = 200;
        Map<String, Object> m = client().league("fba", "123", 2027, Set.of("mSettings"), new EspnCredential(S2, SWID));
        assertEquals(1, ((Number) m.get("id")).intValue());
        assertEquals("espn_s2=" + S2 + "; SWID=" + SWID, espnCookies.get(0));
        client().league("fba", "123", 2027, Set.of("mSettings"), null);
        assertEquals("null", espnCookies.get(1), "no credential, no Cookie header");
        assertNoLogLeak();
    }

    @Test
    void aRejectionCarriesACodeNotTheCookies() {
        status = 401;
        EspnAccessDenied e = assertThrows(EspnAccessDenied.class,
                () -> client().league("fba", "123", 2027, Set.of("mSettings"), new EspnCredential(S2, SWID)));
        assertFalse(leaks(everything(e)), everything(e));
        assertEquals("ESPN_ACCESS_DENIED", e.code());
        status = 503;
        EspnHttpError h = assertThrows(EspnHttpError.class,
                () -> client().transactions("fba", "123", 2027, 7, new EspnCredential(S2, SWID)));
        assertFalse(leaks(everything(h)), everything(h));
        assertEquals("ESPN_HTTP_503", h.code());
        assertNoLogLeak();
    }

    @Test
    void aRedirectToAnotherHostIsNeverFollowedSoTheCookiesNeverLeaveForIt() {
        status = 302;
        location = "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/steal";
        EspnHttpError e = assertThrows(EspnHttpError.class,
                () -> client().league("fba", "123", 2027, Set.of("mSettings"), new EspnCredential(S2, SWID)));
        assertEquals("ESPN_HTTP_302", e.code());
        assertTrue(elsewhereCookies.isEmpty(), "the redirect target must never be contacted: " + elsewhereCookies);
        assertFalse(leaks(everything(e)));
        assertNoLogLeak();
    }

    @Test
    void aNonJsonOkIsAShapeProblemNamedByFieldNotByBody() {
        status = 200;
        espn.removeContext("/");
        espn.createContext("/", ex -> {
            byte[] b = ("not json " + S2).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        EspnShapeChanged e = assertThrows(EspnShapeChanged.class,
                () -> client().league("fba", "123", 2027, Set.of("mSettings"), new EspnCredential(S2, SWID)));
        assertFalse(leaks(everything(e)), "a parse error must not echo the response body");
        assertEquals("response", e.fieldPath());
        assertNoLogLeak();
    }
}

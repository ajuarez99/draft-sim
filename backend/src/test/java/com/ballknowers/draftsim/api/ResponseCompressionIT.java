package com.ballknowers.draftsim.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 022 live finding 2: the stat leaderboard is ~1.4 MB of JSON per window and was sent uncompressed. A real
 * server (MockMvc bypasses Tomcat's compression) must gzip a large JSON body when the client asks and leave it plain when the
 * client does not. (A small chunked response is gzipped anyway: Tomcat's min-response-size only applies to a known
 * Content-Length, so no "small stays plain" claim is made.) SKIPS when the local Postgres or the 2025 NBA league is not there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseCompressionIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String MEMBER = "1122386008709910528";

    @LocalServerPort int port;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping");
    }

    private HttpResponse<byte[]> get(String path, String acceptEncoding) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b.header("X-Sleeper-User", MEMBER);
        if (acceptEncoding != null) b.header("Accept-Encoding", acceptEncoding);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void aLargeJsonResponseIsGzippedWhenTheClientAsksAndPlainWhenItDoesNot() throws Exception {
        String path = "/api/leagues/" + NBA_2025 + "/stats?window=SEASON";
        HttpResponse<byte[]> plain = get(path, null);
        Assumptions.assumeTrue(plain.statusCode() == 200, "the 2025 NBA league is not visible in the local database (status " + plain.statusCode() + ")");
        assertTrue(plain.body().length > 2048, "the leaderboard is larger than the compression threshold");
        assertTrue(plain.headers().firstValue("Content-Encoding").isEmpty());

        HttpResponse<byte[]> gz = get(path, "gzip");
        assertEquals(200, gz.statusCode());
        assertEquals("gzip", gz.headers().firstValue("Content-Encoding").orElse(null));
        assertTrue(gz.body().length < plain.body().length / 2, "gzip " + gz.body().length + " vs " + plain.body().length);
    }
}

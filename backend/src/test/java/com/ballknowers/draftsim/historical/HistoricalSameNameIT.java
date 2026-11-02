package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T027 / SC-007: against the imported reference data, every curated same-name person_key maps to its
 * own {@code historical_player} row, and no game row is shared between two people who share a name.
 * Plain JDBC (no Spring context): it only reads. SKIPS when Postgres is unreachable at {@code DB_URL} or the
 * Kaggle import has not been run, so read the skip count.
 */
class HistoricalSameNameIT {

    private static String url;

    @BeforeAll
    static void requiresImportedData() {
        url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean ok = false;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim");
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from historical_player where source = 'KAGGLE'");
             ResultSet rs = ps.executeQuery()) {
            ok = rs.next() && rs.getInt(1) > 1000;
        } catch (SQLException e) {
            // unreachable, or the table is missing
        }
        Assumptions.assumeTrue(ok, "no imported Kaggle history at " + url + " -- skipping same-name IT");
    }

    /** person_key -> display name, from the curated CSV on the classpath. */
    private static Map<String, String> personKeys() throws Exception {
        Map<String, String> keyToName = new LinkedHashMap<>();
        try (InputStream in = HistoricalSameNameIT.class.getResourceAsStream("/historical/historical-same-name.csv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (header) { header = false; continue; }
                String[] f = line.split(",", -1);
                keyToName.putIfAbsent(f[3].trim(), f[2].trim());
            }
        }
        return keyToName;
    }

    @Test
    void everyPersonKeyIsItsOwnPlayerAndNoGameRowIsSharedBetweenSameNamedPeople() throws Exception {
        Map<String, String> keyToName = personKeys();
        assertTrue(keyToName.size() >= 50, "the curated file lost its groups: " + keyToName.size());

        Map<String, Long> idByKey = new HashMap<>();
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            try (PreparedStatement ps = c.prepareStatement(
                    "select id from historical_player where source = 'KAGGLE' and source_key = ?")) {
                for (String key : keyToName.keySet()) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "person_key " + key + " has no historical_player row");
                        idByKey.put(key, rs.getLong(1));
                    }
                }
            }
            // distinct keys -> distinct ids (source_key is unique with source, so this is a guard on the data)
            assertEquals(keyToName.size(), new LinkedHashSet<>(idByKey.values()).size(),
                    "two person_keys resolved to the same historical_player id");

            // group the keys by person name; a group of 2+ is the case that must not merge
            Map<String, List<Long>> idsByName = new LinkedHashMap<>();
            for (var e : keyToName.entrySet()) {
                idsByName.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(idByKey.get(e.getKey()));
            }
            int multi = 0;
            List<String> shared = new ArrayList<>();
            for (var e : idsByName.entrySet()) {
                List<Long> ids = e.getValue();
                if (ids.size() < 2) continue;
                multi++;
                // Two same-named people CAN meet in one game from opposite benches (measured: Eddie Johnson,
                // George Johnson, ...), so the merge signal is two of these ids with a row for the SAME team in
                // the SAME game: one box-score line split across two people, or one person counted twice.
                try (PreparedStatement ps = c.prepareStatement(
                        "select game_id, team, count(*) from historical_player_game where player_id = any (?) "
                                + "group by game_id, team having count(*) > 1")) {
                    ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) shared.add(e.getKey() + " game " + rs.getLong(1));
                    }
                }
            }
            assertTrue(multi > 0, "expected at least one name with two people");
            assertEquals(List.of(), shared, "one team-game carries rows for two same-named people");
        }
    }
}

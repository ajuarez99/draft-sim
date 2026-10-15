package com.ballknowers.draftsim.ingest.espn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads the hand-built ESPN payloads under {@code src/test/resources/espn/}. Every one carries a
 * top-level {@code "_synthetic": true}: none is recorded from ESPN, and the shapes are research R9's
 * third-party guesses until a real league has been read (T053).
 */
public final class EspnFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EspnFixtures() {}

    /** Synthetic NBA league: ESPN seasonId 2027 (app season 2026), league id 9990001, 4 teams. */
    public static Map<String, Object> nbaLeague() {
        try (InputStream in = EspnFixtures.class.getResourceAsStream("/espn/league-nba-synthetic.json")) {
            Map<String, Object> m = JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(m.get("_synthetic"))) throw new IllegalStateException("fixture lost its _synthetic marker");
            return m;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same league one season earlier (ESPN 2026, app 2025): over, nobody active, no further history. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nbaLeaguePreviousSeason() {
        Map<String, Object> m = nbaLeague();
        m.put("seasonId", 2026);
        Map<String, Object> status = (Map<String, Object>) m.get("status");
        status.put("isActive", false);
        status.put("currentMatchupPeriod", 4);
        status.put("previousSeasons", List.of());
        Map<String, Object> team1 = ((List<Map<String, Object>>) m.get("teams")).get(0);
        team1.put("rankCalculatedFinal", 1);
        for (Map<String, Object> g : (List<Map<String, Object>>) m.get("schedule")) g.put("winner", "HOME");
        return m;
    }

    /** The synthetic transactions payload for the NBA fixture's last day of matchup period 1 (scoring period 7). */
    public static Map<String, Object> nbaTransactions() {
        try (InputStream in = EspnFixtures.class.getResourceAsStream("/espn/transactions-nba-synthetic.json")) {
            Map<String, Object> m = JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(m.get("_synthetic"))) throw new IllegalStateException("fixture lost its _synthetic marker");
            return m;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> noTransactions() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("transactions", new java.util.ArrayList<>());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }
}

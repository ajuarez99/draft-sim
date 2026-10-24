package com.ballknowers.draftsim.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recorded 2026-10-21 $200 auction ("Legacy Ball Knowers": 10 teams, 23 rounds, 230 picks, sum of
 * amounts 1,982), loaded from {@code fixtures/046/}. Spec 046.
 *
 * <p>Every identifier is rewritten on load -- the draft id, the league id and each of the ten Sleeper user
 * ids -- so a test that ingests it can never touch a real draft or a real manager row that a scratch or
 * shared database may already hold (the baseline ingest put the real ones there).
 */
public final class AuctionFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REAL_DRAFT_ID = "1415422395308609536";
    private static final String REAL_LEAGUE_ID = "1415422393706455040";

    public static final int TEAMS = 10;
    public static final int ROUNDS = 23;
    public static final int BUDGET = 200;
    public static final int PICKS = 230;
    public static final int SUM_OF_AMOUNTS = 1_982;

    private final String draftJson;
    private final String picksJson;
    private final List<String> userIds;

    /** @param prefix distinguishes one test's identifiers from another's, e.g. {@code "it046-ingest"} */
    public AuctionFixtures(String prefix) {
        String draft = read("fixtures/046/auction-draft.json");
        String picks = read("fixtures/046/auction-picks.json");
        Map<String, Object> d = parse(draft, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) d.get("draft_order");
        List<String> real = new ArrayList<>(order.keySet());
        List<String> fake = new ArrayList<>();
        for (int i = 0; i < real.size(); i++) {
            String f = prefix + "-u" + (i + 1);
            fake.add(f);
            draft = draft.replace(real.get(i), f);
            picks = picks.replace(real.get(i), f);
        }
        draftJson = draft.replace(REAL_DRAFT_ID, prefix + "-draft").replace(REAL_LEAGUE_ID, prefix + "-league");
        picksJson = picks.replace(REAL_DRAFT_ID, prefix + "-draft");
        userIds = fake;
    }

    public Map<String, Object> draft() {
        return parse(draftJson, new TypeReference<>() {});
    }

    /** A fresh, mutable copy on every call. */
    public List<Map<String, Object>> picks() {
        return parse(picksJson, new TypeReference<>() {});
    }

    /** The ten (fake) Sleeper user ids, in the fixture's draft_order key order. */
    public List<String> userIds() {
        return userIds;
    }

    /** A league object as {@code SleeperClient.leagueChain} would return it. */
    public Map<String, Object> league(String leagueId) {
        Map<String, Object> league = new LinkedHashMap<>();
        league.put("league_id", leagueId);
        league.put("name", "IT auction league");
        league.put("season", "2026");
        league.put("sport", "nba");
        league.put("total_rosters", TEAMS);
        league.put("status", "complete");
        return league;
    }

    /** The {@code leagueUsers} payload: one entry per fake user. */
    public List<Map<String, Object>> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : userIds) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("user_id", id);
            u.put("display_name", "IT " + id);
            out.add(u);
        }
        return out;
    }

    private static <T> T parse(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String resource) {
        try (InputStream in = AuctionFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing test resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

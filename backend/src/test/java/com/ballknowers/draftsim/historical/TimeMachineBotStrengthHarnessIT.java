package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec 062 measurement, NOT a pass/fail test: how strong the Time Machine bots are under several
 * {@link BotTuning}s, against REAL imported seasons. For every offered season and three draft slots it creates a
 * game and auto-finishes it (the user is then a drafter who always takes the best-ranked player who would start),
 * and writes one row per tuning to {@code build/tm-bot-strength-<sport>.txt}.
 *
 * <p>Runs only with {@code TM_BOT_HARNESS=NBA} or {@code =NFL} in the environment, against the database at
 * {@code DB_URL}; otherwise it skips. {@code TM_BOT_VARIANTS} narrows the grid of tunings. Its games belong to user {@code it-harness} and are deleted at the end.
 * The services are built by hand so the tuning can vary inside one JVM; they therefore run without a transaction
 * proxy, which is fine for one thread.
 */
@SpringBootTest
class TimeMachineBotStrengthHarnessIT {

    private static final String USER = "it-harness";
    private static final int[] SLOTS = {1, 6, 12};

    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository games;
    @Autowired private NflHistoricalRepository nflRepo;
    @Autowired private DraftContextFactory contexts;
    @Autowired private MockDraftEngine engine;
    @Autowired private SportRulesRegistry rulesRegistry;
    @Autowired private SeasonReplay replay;
    @Autowired private HistoricalProperties nbaProps;
    @Autowired private NflHistoricalProperties nflProps;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private record Variant(String name, BotTuning tuning) {}

    @Test
    void measure() throws Exception {
        String sportCode = System.getenv("TM_BOT_HARNESS");
        Assumptions.assumeTrue("NBA".equals(sportCode) || "NFL".equals(sportCode),
                "set TM_BOT_HARNESS=NBA or NFL to run the bot-strength measurement");

        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("shared (T 1.0)", BotTuning.NONE));
        // TM_BOT_VARIANTS narrows the grid: a comma-separated list of temperatures, e.g. "0.4,0.25".
        String chosen = System.getenv("TM_BOT_VARIANTS");
        String grid = chosen != null && !chosen.isBlank() ? chosen : "0.6,0.4,0.25,0.15,0.05";
        for (String t : grid.split(",")) {
            variants.add(new Variant("T " + t.trim(), new BotTuning(Double.valueOf(t.trim()))));
        }

        List<String> out = new ArrayList<>();
        // win%, top3% and avgFin read the user's REGULAR-SEASON standings place, as they did before spec 063 (when
        // userFinish was that rank), so the 062 numbers stay comparable. champ% is new: the user won the bracket
        // (userOutcome CHAMPION); it is 0 when a season is too short for playoffs.
        out.add(String.format("%-26s %5s %6s %6s %7s %8s %8s %9s %9s %9s %7s", "bots", "games", "win%", "top3%",
                "avgFin", "reach1-3", "rank1by3", "noStarter", "thinTeams", "maxAtPos", "champ%"));
        try {
            for (Variant v : variants) {
                HistoricalSport sport = "NBA".equals(sportCode)
                        ? new NbaHistoricalSport(nbaProps, data, v.tuning())
                        : new NflHistoricalSport(nflProps, data, nflRepo, v.tuning());
                HistoricalGameService svc = new HistoricalGameService(data, games, contexts, engine, rulesRegistry,
                        replay, new HistoricalSports(List.of(sport)), json);
                out.add(run(v.name(), svc, sport));
                System.out.println(out.get(out.size() - 1));
            }
        } finally {
            jdbc.update("delete from historical_game where sleeper_user_id = ?", USER);
        }
        Path file = Path.of("build", "tm-bot-strength-" + sportCode.toLowerCase() + ".txt");
        Files.createDirectories(file.getParent());
        Files.write(file, out);
    }

    private String run(String name, HistoricalGameService svc, HistoricalSport sport) {
        int n = 0, wins = 0, top3 = 0, finishSum = 0, champions = 0;
        double reachSum = 0;
        int reachN = 0, rank1Gone = 0, rank1N = 0;
        int botTeams = 0, noStarter = 0, thin = 0, maxAtPos = 0;
        // the dedicated starting slots per position, e.g. NFL QB 1, RB 2, WR 2, TE 1
        Map<String, Integer> dedicated = new HashMap<>();
        for (String s : sport.rosterPositions()) {
            if (isPosition(s, sport)) dedicated.merge(s, 1, Integer::sum);
        }
        for (SeasonRow season : sport.offeredSeasons()) {
            for (int slot : SLOTS) {
                HistoricalGameState g = svc.create(USER, sport.code(), slot, season.seasonStart());
                g = svc.auto(g.id(), HistoricalGameService.Scope.FINISH, USER).orElseThrow();
                JsonNode r = g.result();
                // The standings place (regular season), NOT userFinish: since spec 063 that is the final place
                // after the playoffs, which would silently change what "win%" measured in spec 062.
                int finish = standingsPlace(r, g.userSlot());
                if (r.hasNonNull("userOutcome") && "CHAMPION".equals(r.get("userOutcome").asText())) champions++;
                n++;
                finishSum += finish;
                if (finish == 1) wins++;
                if (finish <= 3) top3++;

                Map<Integer, Map<String, Integer>> shape = new HashMap<>();
                boolean rank1Taken = false;
                for (HistoricalGameState.PickView p : g.picks()) {
                    boolean bot = "BOT".equals(p.seatType());
                    if (bot && p.round() <= 3) {
                        reachSum += Math.abs(p.player().rank() - p.pickNo());
                        reachN++;
                    }
                    if (p.pickNo() <= 3 && p.player().rank() == 1) rank1Taken = true;
                    if (bot) {
                        // every position a player is eligible at counts: an NBA G/F covers both
                        for (String pos : p.player().positions()) {
                            shape.computeIfAbsent(p.draftSlot(), k -> new HashMap<>()).merge(pos, 1, Integer::sum);
                        }
                    }
                }
                // "rank 1 gone by pick 3" is about the bots only when the user does not pick first
                if (DraftSlot.slot(1, g.teams(), 0) != slot) {
                    rank1N++;
                    if (rank1Taken) rank1Gone++;
                }
                for (Map<String, Integer> c : shape.values()) {
                    botTeams++;
                    boolean missing = false, noSpare = false;
                    for (Map.Entry<String, Integer> d : dedicated.entrySet()) {
                        int have = c.getOrDefault(d.getKey(), 0);
                        if (have == 0) missing = true;
                        if (have <= d.getValue()) noSpare = true;   // no spare at a starting position
                    }
                    if (missing) noStarter++;
                    if (noSpare) thin++;
                    for (int k : c.values()) maxAtPos = Math.max(maxAtPos, k);
                }
            }
        }
        return String.format("%-26s %5d %5.1f%% %5.1f%% %7.2f %8.1f %7.0f%% %8.1f%% %8.1f%% %9d %6.1f%%", name, n,
                100.0 * wins / n, 100.0 * top3 / n, (double) finishSum / n, reachSum / reachN,
                100.0 * rank1Gone / Math.max(1, rank1N), 100.0 * noStarter / botTeams, 100.0 * thin / botTeams,
                maxAtPos, 100.0 * champions / n);
    }

    private static int standingsPlace(JsonNode result, int userSlot) {
        for (JsonNode s : result.get("standings")) {
            if (s.get("slot").asInt() == userSlot) return s.get("place").asInt();
        }
        throw new IllegalStateException("the user's slot " + userSlot + " is not in the standings");
    }

    private static boolean isPosition(String slot, HistoricalSport sport) {
        try {
            return Position.valueOf(slot).sport() == sport.sport();
        } catch (IllegalArgumentException e) {
            return false;   // FLEX, G, F, UTIL, BN
        }
    }
}

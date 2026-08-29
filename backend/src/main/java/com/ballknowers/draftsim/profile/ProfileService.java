package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.config.PriorProperties;
import com.ballknowers.draftsim.config.ShrinkageProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fits the per-manager layer and the shared positional prior from ingested
 * drafts.
 *
 * Everything here is thin by construction. A manager with one scoreable draft
 * contributes ~15 picks. After shrinkage their profile is mostly the league
 * mean, which is the correct outcome at this sample size and the reason
 * draftsObserved and picksScored are carried all the way out to the UI.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final int TILT_ROUNDS = 4;   // "coarse positional tilt" = the first four rounds

    private final DraftRepository drafts;
    private final PlayerRepository players;
    private final ManagerRepository managers;
    private final ShrinkageProperties shrinkage;
    private final PriorProperties priorCfg;

    public ProfileService(DraftRepository drafts, PlayerRepository players, ManagerRepository managers,
                          ShrinkageProperties shrinkage, PriorProperties priorCfg) {
        this.drafts = drafts;
        this.players = players;
        this.managers = managers;
        this.shrinkage = shrinkage;
        this.priorCfg = priorCfg;
    }

    public record Fit(Map<Long, ManagerProfile> profiles, PositionalPriors priors, int scoreablePicks) {}

    public Fit fit(Sport sport) {
        List<DraftRepository.PickRow> picks = drafts.allCompletedPicks();
        Map<Long, Position> posById = new HashMap<>();
        for (Player p : players.findAll(sport)) posById.put(p.id(), p.primary());
        Map<Long, String> names = managers.names();

        PositionalPriors priors = fitPriors(picks, posById);

        // --- reach bias -------------------------------------------------
        Map<Long, List<Double>> reachByManager = new HashMap<>();
        Map<Long, Set<Long>> draftsByManager = new HashMap<>();
        int scoreable = 0;

        for (DraftRepository.PickRow p : picks) {
            if (p.managerId() == null) continue;
            draftsByManager.computeIfAbsent(p.managerId(), k -> new HashSet<>()).add(p.draftId());
            if (p.adpAtTime() == null) continue;   // no contemporaneous board; not scoreable
            scoreable++;
            // positive = took him earlier than the board said
            reachByManager.computeIfAbsent(p.managerId(), k -> new ArrayList<>())
                    .add(p.adpAtTime() - p.pickNo());
        }

        double leagueMeanReach = reachByManager.values().stream()
                .flatMap(List::stream)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        // --- positional tilt over the first four rounds ------------------
        Map<Long, Map<Position, Integer>> earlyByManager = new HashMap<>();
        Map<Position, Integer> earlyLeague = new EnumMap<>(Position.class);
        int earlyTotal = 0;

        for (DraftRepository.PickRow p : picks) {
            if (p.managerId() == null || p.playerId() == null || p.round() > TILT_ROUNDS) continue;
            Position pos = posById.get(p.playerId());
            if (pos == null) continue;
            earlyByManager.computeIfAbsent(p.managerId(), k -> new EnumMap<>(Position.class))
                    .merge(pos, 1, Integer::sum);
            earlyLeague.merge(pos, 1, Integer::sum);
            earlyTotal++;
        }

        Map<Long, ManagerProfile> out = new HashMap<>();
        for (Long managerId : draftsByManager.keySet()) {
            int observed = draftsByManager.get(managerId).size();
            List<Double> reaches = reachByManager.getOrDefault(managerId, List.of());
            int picksScored = reaches.size();

            double rawReach = reaches.stream().mapToDouble(Double::doubleValue).average()
                    .orElse(leagueMeanReach);
            // Shrink on the number of scoreable drafts, not raw pick count: picks
            // within one draft are correlated and would overstate the evidence.
            int shrinkN = picksScored == 0 ? 0 : observed;
            double reach = shrinkage.shrink(rawReach, leagueMeanReach, shrinkN);

            Map<Position, Double> tilt = fitTilt(
                    earlyByManager.getOrDefault(managerId, Map.of()), earlyLeague, earlyTotal, observed);

            out.put(managerId, new ManagerProfile(
                    managerId, names.getOrDefault(managerId, "?"), reach, tilt, observed, picksScored));
        }

        log.info("fit {} manager profiles from {} picks ({} scoreable for reach), league mean reach {}",
                out.size(), picks.size(), scoreable, String.format("%.2f", leagueMeanReach));
        return new Fit(out, priors, scoreable);
    }

    private Map<Position, Double> fitTilt(Map<Position, Integer> mine,
                                          Map<Position, Integer> league,
                                          int leagueTotal,
                                          int draftsObserved) {
        Map<Position, Double> tilt = new EnumMap<>(Position.class);
        int myTotal = mine.values().stream().mapToInt(Integer::intValue).sum();
        if (myTotal == 0 || leagueTotal == 0) return tilt;

        for (Position pos : Position.values()) {
            double leagueShare = league.getOrDefault(pos, 0) / (double) leagueTotal;
            if (leagueShare <= 0) continue;
            double myShare = mine.getOrDefault(pos, 0) / (double) myTotal;
            double rawRatio = myShare / leagueShare;
            // Shrink the ratio toward 1.0 (= drafts like the room does).
            tilt.put(pos, shrinkage.shrink(rawRatio, 1.0, draftsObserved));
        }
        return tilt;
    }

    private PositionalPriors fitPriors(List<DraftRepository.PickRow> picks, Map<Long, Position> posById) {
        Map<Integer, Map<Position, Integer>> counts = new HashMap<>();
        Map<Position, Integer> overallCounts = new EnumMap<>(Position.class);
        int n = 0;

        for (DraftRepository.PickRow p : picks) {
            if (p.playerId() == null) continue;
            Position pos = posById.get(p.playerId());
            if (pos == null) continue;
            counts.computeIfAbsent(p.round(), k -> new EnumMap<>(Position.class)).merge(pos, 1, Integer::sum);
            overallCounts.merge(pos, 1, Integer::sum);
            n++;
        }

        double alpha = priorCfg.alpha();
        int k = Position.values().length;

        Map<Integer, Map<Position, Double>> byRound = new HashMap<>();
        counts.forEach((round, table) -> {
            int total = table.values().stream().mapToInt(Integer::intValue).sum();
            Map<Position, Double> probs = new EnumMap<>(Position.class);
            for (Position pos : Position.values()) {
                probs.put(pos, (table.getOrDefault(pos, 0) + alpha) / (total + alpha * k));
            }
            byRound.put(round, probs);
        });

        Map<Position, Double> overall = new EnumMap<>(Position.class);
        int total = Math.max(n, 1);
        for (Position pos : Position.values()) {
            overall.put(pos, (overallCounts.getOrDefault(pos, 0) + alpha) / (total + alpha * k));
        }

        log.info("fit positional priors from {} picks across {} rounds (alpha={})", n, byRound.size(), alpha);
        return new PositionalPriors(byRound, overall, n);
    }
}

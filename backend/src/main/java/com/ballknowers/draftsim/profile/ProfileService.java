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
 * Combines two sources of knowledge about each seat.
 *
 * Fitted history is thin by construction — one or two drafts, ~15 picks each. What
 * the user states about their own leaguemates is often better information than that,
 * and for a league with no history at all it is the only information there is.
 *
 * So a stated reach bias is used as the SHRINKAGE TARGET rather than as an override.
 * A seat with no history lands exactly on the stated value; a seat with two drafts
 * lands one third of the way from it toward what actually happened. Where nothing is
 * stated the target is the league mean, exactly as before.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    /**
     * "Coarse positional tilt" is fit over the opening quarter-ish of a draft.
     * Expressed as a fraction rather than the four rounds it used to be, for
     * the same reason the priors are: four rounds is 48 picks of a 12-team
     * draft and 56 of a 14-team one, and pooling those as though they were the
     * same window is exactly the confound this pass removes. 4/15 reproduces
     * "the first four rounds" exactly on any 15-round league.
     */
    private static final double TILT_FRACTION = 4.0 / 15.0;

    private final DraftRepository drafts;
    private final PlayerRepository players;
    private final ManagerRepository managers;
    private final ManagerProfileRepository profiles;
    private final ShrinkageProperties shrinkage;
    private final PriorProperties priorCfg;

    public ProfileService(DraftRepository drafts, PlayerRepository players, ManagerRepository managers,
                          ManagerProfileRepository profiles, ShrinkageProperties shrinkage,
                          PriorProperties priorCfg) {
        this.drafts = drafts;
        this.players = players;
        this.managers = managers;
        this.profiles = profiles;
        this.shrinkage = shrinkage;
        this.priorCfg = priorCfg;
    }

    public record Fit(Map<Long, ManagerProfile> profiles, PositionalPriors priors, int scoreablePicks) {}

    public Fit fit(Sport sport) {
        List<DraftRepository.CompletedPick> picks = drafts.allCompletedPicks();
        Map<Long, Position> posById = new HashMap<>();
        for (Player p : players.findAll(sport)) posById.put(p.id(), p.primary());
        Map<Long, String> names = managers.names();
        Map<Long, ManualTendencies> manual = profiles.manualBySport(sport);

        PositionalPriors priors = fitPriors(picks, posById);

        // --- reach bias -------------------------------------------------
        Map<Long, List<Double>> reachByManager = new HashMap<>();
        Map<Long, Set<Long>> draftsByManager = new HashMap<>();
        int scoreable = 0;

        for (DraftRepository.CompletedPick p : picks) {
            if (p.managerId() == null) continue;
            draftsByManager.computeIfAbsent(p.managerId(), k -> new HashSet<>()).add(p.draftId());
            if (p.adpAtTime() == null) continue;   // no contemporaneous board; not scoreable
            scoreable++;
            // Positive = took him earlier than the board said.
            //
            // Both terms count players consumed, so this needs no team-count
            // conversion and does not get one. adp_at_time is a board RANK:
            // BoardService rescales its three input sources to referenceTeams
            // before blending, then re-ranks the blend to a dense 1..N
            // ordering, so what lands on the pick is "the Nth best player",
            // not a 14-team pick number. Pick 30 is likewise "the 30th player
            // taken" in a draft of any size. claude/next-features-roadmap.md
            // called this a scale bug; it is not one — see the note added
            // there. What is genuinely size-dependent is what a fixed reach of
            // +8 picks MEANS behaviourally (half a round at 16 teams, a full
            // round at 8), which is a modelling question, not a unit error.
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

        for (DraftRepository.CompletedPick p : picks) {
            if (p.managerId() == null || p.playerId() == null || !isEarly(p)) continue;
            Position pos = posById.get(p.playerId());
            if (pos == null) continue;
            earlyByManager.computeIfAbsent(p.managerId(), k -> new EnumMap<>(Position.class))
                    .merge(pos, 1, Integer::sum);
            earlyLeague.merge(pos, 1, Integer::sum);
            earlyTotal++;
        }

        // Every known manager gets a profile, not just those with picks: a seat with
        // no history but a stated tendency must not fall back to neutral.
        Set<Long> allManagers = new HashSet<>(names.keySet());
        allManagers.addAll(draftsByManager.keySet());
        allManagers.addAll(manual.keySet());

        Map<Long, ManagerProfile> out = new HashMap<>();
        for (Long managerId : allManagers) {
            ManualTendencies stated = manual.getOrDefault(managerId, ManualTendencies.EMPTY);
            int observed = draftsByManager.getOrDefault(managerId, Set.of()).size();
            List<Double> reaches = reachByManager.getOrDefault(managerId, List.of());
            int picksScored = reaches.size();

            // The stated value, when present, replaces the league mean as the target
            // shrinkage pulls toward. With no observations that IS the result.
            double target = stated.reachBias() != null ? stated.reachBias() : leagueMeanReach;
            double rawReach = reaches.stream().mapToDouble(Double::doubleValue).average().orElse(target);
            // Shrink on scoreable drafts, not raw pick count: picks within one draft
            // are correlated and would overstate the evidence.
            int shrinkN = picksScored == 0 ? 0 : observed;
            double reach = shrinkage.shrink(rawReach, target, shrinkN);

            Map<Position, Double> tilt = fitTilt(
                    earlyByManager.getOrDefault(managerId, Map.of()), earlyLeague, earlyTotal, observed);

            boolean hasData = picksScored > 0;
            boolean hasStated = stated.affectsBehaviour();
            Provenance provenance = hasData && hasStated ? Provenance.BLENDED
                    : hasData ? Provenance.FITTED
                    : hasStated ? Provenance.STATED
                    : Provenance.NEUTRAL;

            out.put(managerId, new ManagerProfile(
                    managerId, names.getOrDefault(managerId, "?"), reach, tilt,
                    stated.unpredictability() == null ? 1.0 : stated.unpredictability(),
                    stated.note(), observed, picksScored, provenance));
        }

        log.info("profiles: {} managers, {} scoreable picks, league mean reach {}, {} with stated tendencies",
                out.size(), scoreable, String.format("%.2f", leagueMeanReach), manual.size());
        return new Fit(out, priors, scoreable);
    }

    /**
     * Writes the fitted half back to manager_profile. Called after ingest.
     *
     * This is a readable snapshot for inspection, not a cache the engine reads —
     * fitting is cheap at this data size and a cache would only add staleness. The
     * load-bearing column is manual_json, which this never touches.
     */
    public int persistFitted(Sport sport) {
        Fit fit = fit(sport);
        fit.profiles().forEach((managerId, p) -> {
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("reachBias", round3(p.reachBias()));
            feature.put("positionalTilt", p.positionalTilt());
            feature.put("picksScored", p.picksScored());
            feature.put("provenance", p.provenance().name());
            profiles.saveFitted(managerId, sport, JsonUtil.write(feature), p.draftsObserved());
        });
        log.info("persisted {} fitted profiles", fit.profiles().size());
        return fit.profiles().size();
    }

    public void setManual(long managerId, Sport sport, ManualTendencies manual) {
        profiles.saveManual(managerId, sport, manual);
        log.info("manual tendencies set for manager {}: {}", managerId, manual);
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
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
            // Shrink the ratio toward 1.0 (= drafts like the room does).
            tilt.put(pos, shrinkage.shrink(myShare / leagueShare, 1.0, draftsObserved));
        }
        return tilt;
    }

    /** Is this pick inside the opening window positional tilt is fit over? */
    private static boolean isEarly(DraftRepository.CompletedPick p) {
        int total = p.totalPicks();
        if (total <= 0) return false;
        return (p.pickNo() - 1) < TILT_FRACTION * total;
    }

    /** Configured bucket count, falling back if an older external weights.yml omits it. */
    private int buckets() {
        return priorCfg.buckets() > 0 ? priorCfg.buckets() : PositionalPriors.DEFAULT_BUCKETS;
    }

    private PositionalPriors fitPriors(List<DraftRepository.CompletedPick> picks, Map<Long, Position> posById) {
        int buckets = buckets();
        Map<Integer, Map<Position, Integer>> counts = new HashMap<>();
        Map<Position, Integer> overallCounts = new EnumMap<>(Position.class);
        int n = 0;

        for (DraftRepository.CompletedPick p : picks) {
            if (p.playerId() == null) continue;
            Position pos = posById.get(p.playerId());
            if (pos == null) continue;
            // Bucketed on fraction-of-draft, not round: the fitted drafts are
            // 12-team and the simulated league is 14, so a round-keyed cell
            // would pool two different slices of the board into one estimate.
            int bucket = PositionalPriors.bucketOf(p.pickNo(), p.totalPicks(), buckets);
            counts.computeIfAbsent(bucket, k -> new EnumMap<>(Position.class)).merge(pos, 1, Integer::sum);
            overallCounts.merge(pos, 1, Integer::sum);
            n++;
        }

        double alpha = priorCfg.alpha();
        int k = Position.values().length;

        Map<Integer, Map<Position, Double>> byBucket = new HashMap<>();
        counts.forEach((bucket, table) -> {
            int total = table.values().stream().mapToInt(Integer::intValue).sum();
            Map<Position, Double> probs = new EnumMap<>(Position.class);
            for (Position pos : Position.values()) {
                probs.put(pos, (table.getOrDefault(pos, 0) + alpha) / (total + alpha * k));
            }
            byBucket.put(bucket, probs);
        });

        Map<Position, Double> overall = new EnumMap<>(Position.class);
        int total = Math.max(n, 1);
        for (Position pos : Position.values()) {
            overall.put(pos, (overallCounts.getOrDefault(pos, 0) + alpha) / (total + alpha * k));
        }

        log.info("fit positional priors from {} picks across {} of {} draft-fraction buckets (alpha={})",
                n, byBucket.size(), buckets, alpha);
        return new PositionalPriors(byBucket, overall, n, buckets);
    }
}

package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.BoardProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.IntConsumer;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final BoardService boards;
    private final ProfileService profiles;
    private final DraftRepository drafts;
    private final LeagueRepository leagues;
    private final PlayerRepository players;
    private final BoardProperties boardCfg;
    private final MonteCarloRunner runner;
    private final DraftContextFactory contexts;

    public SimulationService(BoardService boards, ProfileService profiles, DraftRepository drafts,
                             LeagueRepository leagues, PlayerRepository players,
                             BoardProperties boardCfg, MonteCarloRunner runner,
                             DraftContextFactory contexts) {
        this.boards = boards;
        this.profiles = profiles;
        this.drafts = drafts;
        this.leagues = leagues;
        this.players = players;
        this.boardCfg = boardCfg;
        this.runner = runner;
        this.contexts = contexts;
    }

    public SimulationResult simulate(SimulationRequest req, IntConsumer onProgress) {
        long setupStart = System.nanoTime();
        Sport sport = Sport.NFL;

        DraftRepository.DraftRow draft = drafts.bySleeperId(req.draftSleeperId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "draft " + req.draftSleeperId() + " not ingested"));

        LeagueRepository.LeagueRow leagueRow = leagues.all().stream()
                .filter(l -> l.id() == draft.leagueId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("league missing for draft"));

        LeagueSettings settings = LeagueRepository.toSettings(leagueRow, draft.rounds());
        List<BoardEntry> board = boards.currentBoard(sport);
        if (board.isEmpty()) throw new IllegalStateException("board is empty — run ingest first");

        // Drop anyone the caller has already accounted for (keepers, mistaken entries).
        if (req.excludePlayerIds() != null && !req.excludePlayerIds().isEmpty()) {
            Set<String> exclude = new HashSet<>(req.excludePlayerIds());
            board = board.stream().filter(e -> !exclude.contains(e.player().sleeperId())).toList();
        }

        ProfileService.Fit fit = profiles.fit(sport);
        List<SeatSpec> seats = seatsOf(draft, req.mySlot());
        Map<Integer, Long> completed = resolveStartState(req, draft, sport);

        DraftContext ctx = contexts.build(
                settings, seats, fit.profiles(), fit.priors(), board, completed);
        Map<Integer, ManagerProfile> bySlot = ctx.profileBySlot();

        double temperature = req.temperature() != null ? req.temperature() : ctx.cfg().temperature();
        long seed = System.nanoTime();

        long setupMs = (System.nanoTime() - setupStart) / 1_000_000;
        log.info("request setup (board + profiles + Postgres): {} ms", setupMs);

        return runner.run(ctx, req.mySlot(), req.iterations(), temperature, seed,
                buildConfidence(bySlot, fit, settings), onProgress);
    }

    /**
     * The league's own draft_order, as seats: every mapped slot is a modelled
     * manager, the caller's own slot is marked USER, and anything Sleeper left
     * unmapped falls through to a league-average bot in the factory.
     *
     * Delegates to {@link SeatSpec#fromDraftOrder}, which the mock room's
     * "fork a live draft" path (claude/next-features-roadmap.md's Phase 3/4
     * bridge) shares -- extracted there rather than duplicated.
     */
    private List<SeatSpec> seatsOf(DraftRepository.DraftRow draft, int mySlot) {
        return SeatSpec.fromDraftOrder(draft.slotToManager(), mySlot);
    }

    /**
     * Explicit startState wins. Otherwise, any picks Sleeper has already recorded
     * for this draft are replayed, which is what makes "import and continue" work
     * for a live draft with no extra input.
     */
    private Map<Integer, Long> resolveStartState(SimulationRequest req,
                                                 DraftRepository.DraftRow draft,
                                                 Sport sport) {
        Map<Integer, Long> out = new HashMap<>();
        if (req.startState() != null && !req.startState().isEmpty()) {
            Map<String, Long> ids = players.idsBySleeperId(sport);
            req.startState().forEach((pickNo, sleeperPlayerId) -> {
                Long id = ids.get(sleeperPlayerId);
                if (id != null) out.put(pickNo, id);
            });
            return out;
        }
        for (DraftRepository.PickRow p : drafts.picks(draft.id())) {
            if (p.playerId() != null) out.put(p.pickNo(), p.playerId());
        }
        return out;
    }

    private SimulationResult.Confidence buildConfidence(Map<Integer, ManagerProfile> bySlot,
                                                        ProfileService.Fit fit,
                                                        LeagueSettings settings) {
        var seats = bySlot.values();
        int withHistory = (int) seats.stream()
                .filter(p -> p.provenance() == Provenance.FITTED || p.provenance() == Provenance.BLENDED)
                .count();
        int stated = (int) seats.stream().filter(p -> p.provenance() == Provenance.STATED).count();
        int neutral = (int) seats.stream().filter(p -> p.provenance() == Provenance.NEUTRAL).count();
        int maxDrafts = seats.stream().mapToInt(ManagerProfile::draftsObserved).max().orElse(0);

        List<String> caveats = new ArrayList<>();
        caveats.add("Board is Sleeper search_rank blended with observed draft order at weight "
                + boardCfg.observedWeight() + ". Neither input is a true 14-team PPR ADP.");
        caveats.add("Scoring weights in weights.yml are hand-set, not fit to data.");

        if (stated > 0) {
            caveats.add(stated + " of " + settings.teams() + " seats are running on tendencies you "
                    + "entered by hand, with no draft history behind them. Those seats behave the way "
                    + "you said they would, which is a statement about your judgement rather than "
                    + "evidence about them.");
        }
        if (neutral == settings.teams()) {
            caveats.add("No seat has history or stated tendencies. Every manager is the league-average "
                    + "drafter, so this shows how drafters behave in general, not how these people draft.");
        } else if (neutral > 0) {
            caveats.add(neutral + " of " + settings.teams() + " seats have neither history nor stated "
                    + "tendencies and are running the league-average model.");
        }
        if (maxDrafts > 0 && maxDrafts <= 2) {
            caveats.add("At most " + maxDrafts + " draft(s) observed per manager. After shrinkage each "
                    + "fitted profile stays close to its prior; treat per-manager differences as a hint.");
        }
        caveats.add("Nothing here has been backtested. The probabilities are internally consistent, "
                + "which is not the same as being calibrated.");

        return new SimulationResult.Confidence(
                maxDrafts, fit.scoreablePicks(), withHistory, stated, neutral, settings.teams(),
                "sleeper_search_rank + observed drafts (blend)", caveats);
    }
}

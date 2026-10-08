package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.DraftGradesService.DraftGrades;
import com.ballknowers.draftsim.engine.DraftGradesService.PickGrade;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stats leaderboard's draft, ADP and Draft Grades join (specs/022-player-stat-analysis T049, data-model
 * "Draft and ADP" as amended F1/F12).
 *
 * <ul>
 *   <li><b>Draft state</b> reads {@code draft.status}, never {@code league.status} (F1): {@code complete} is
 *       {@code COMPLETE}, a draft row in any other status {@code NOT_HAPPENED}, no draft row {@code NONE}.</li>
 *   <li><b>Pick, round and manager</b> come from the stored picks of a complete draft; the manager is named by
 *       Draft Grades' rule ({@link DraftGradesService#managerNamesBySlot}), so it matches the page it links to.
 *       A player with no pick has none, which means undrafted when the state is {@code COMPLETE}.</li>
 *   <li><b>ADP</b> is the blend's latest capture on or before the draft's scheduled start date (UTC date), from
 *       {@link BoardRepository#latestBefore}. A null start is {@code NO_DRAFT_DATE}; no capture that early is
 *       {@code NO_ADP_STORED}. {@code captured_on} is a date, so a capture made on draft day counts. The raw
 *       search rank ({@code sleeper_search_rank}) is never read as ADP.</li>
 *   <li><b>{@code draftGrades}</b> copies {@code {available, reason, gradesEarly, weeksCounted}} from the draft's
 *       Draft Grades result, and {@code draftValue} is that pick's {@code valueOverSlot}, matched by pick number;
 *       null for an undrafted player and whenever grades are unavailable.</li>
 * </ul>
 *
 * <p><b>Memoisation.</b> {@link DraftGradesService#read} loads every player of the sport, the drafted players'
 * games and the roster weeks, so it is the one expensive part. The result for a {@code COMPLETE} draft is kept
 * per draft id and reused while the draft's status and the season's {@code player_game} token
 * ({@code count, max(fetched_at)}, the same token {@link SeasonBoxCache} validates on) and the league's scoring
 * settings (a hash of {@code leagues.scoringOf}, which Draft Grades scores with) are unchanged. A new ingest or a
 * scoring change therefore reads the grades again. Limit: Draft Grades also reads weekly-stats finality and roster
 * weeks, which neither covers; a change only there is picked up at the next {@code player_game} write. A plain {@link ConcurrentHashMap} get/put, no lock: two simultaneous first reads
 * may both compute, with identical results.
 */
@Component
public class DraftAndAdpJoin {

    public static final String COMPLETE = "COMPLETE";
    public static final String NOT_HAPPENED = "NOT_HAPPENED";
    public static final String NONE = "NONE";
    public static final String NO_ADP_STORED = "NO_ADP_STORED";
    public static final String NO_DRAFT_DATE = "NO_DRAFT_DATE";
    /** {@code draftGrades.reason} when the league has no draft row at all. */
    public static final String NO_DRAFT = "NO_DRAFT";

    /**
     * {@code draftId} is the Sleeper draft id; null when the state is {@code NONE}. {@code draftSeason} is the
     * season of the league the draft columns were read from, which differs from the answered season when the
     * leaderboard fell back (the draft columns follow the REQUESTED season; amended 2026-10-08).
     */
    public record DraftState(String state, String draftId, int draftSeason) {}

    /** Blend ADP provenance: {@code capturedOn} is set, or {@code reason} is. {@code source} is always "blend". */
    public record AdpState(String source, LocalDate capturedOn, String reason) {}

    /** What Draft Grades said about this draft, so a null {@code draftValue} is never ambiguous (F12). */
    public record DraftGradesState(boolean available, String reason, boolean gradesEarly, int weeksCounted) {}

    /** The drafting pick of one player; {@code managerName} is null for a slot with no known manager. */
    public record DraftPick(int pickNo, int round, String managerName) {}

    /** Everything the leaderboard rows read, keyed by sleeper player id. */
    public record Joined(DraftState draft, AdpState adp, DraftGradesState draftGrades,
                         Map<String, DraftPick> picks, Map<String, Double> draftValue, Map<String, Double> adpBySleeperId) {
        public Double adpOf(String sleeperPlayerId) {
            return adpBySleeperId.get(sleeperPlayerId);
        }
    }

    private record Memo(String status, SeasonToken token, int scoringHash, DraftGrades grades) {}

    private final DraftRepository drafts;
    private final BoardRepository board;
    private final DraftGradesService grades;
    private final PlayerRepository players;
    private final LeagueRepository leagues;
    private final ConcurrentHashMap<Long, Memo> memo = new ConcurrentHashMap<>();

    public DraftAndAdpJoin(DraftRepository drafts, BoardRepository board, DraftGradesService grades,
                           PlayerRepository players, LeagueRepository leagues) {
        this.drafts = drafts;
        this.board = board;
        this.grades = grades;
        this.players = players;
        this.leagues = leagues;
    }

    /**
     * @param league the league whose draft is read: the REQUESTED season's league, even when the stats fell back
     * @param token  the answered season's {@code player_game} token (what the box cache is valid on)
     */
    public Joined join(LeagueRow league, SeasonToken token) {
        Sport sport = league.sport();
        Optional<DraftRepository.DraftRow> found = drafts.forLeague(league.id());
        if (found.isEmpty()) {
            return new Joined(new DraftState(NONE, null, league.season()), new AdpState(BoardRepository.SOURCE_BLEND, null, NO_DRAFT_DATE),
                    new DraftGradesState(false, NO_DRAFT, false, 0), Map.of(), Map.of(), Map.of());
        }
        DraftRepository.DraftRow draft = found.get();
        boolean complete = "complete".equals(draft.status());
        DraftState state = new DraftState(complete ? COMPLETE : NOT_HAPPENED, draft.sleeperDraftId(), league.season());

        Map<Long, String> sleeperIdByLocalId = new HashMap<>();
        for (Map.Entry<String, Long> e : players.idsBySleeperId(sport).entrySet()) {
            sleeperIdByLocalId.put(e.getValue(), e.getKey());
        }

        // ADP: the board as of the draft's scheduled date.
        AdpState adp;
        Map<String, Double> adpBySleeperId = new HashMap<>();
        if (draft.startTime() == null) {
            adp = new AdpState(BoardRepository.SOURCE_BLEND, null, NO_DRAFT_DATE);
        } else {
            LocalDate on = draft.startTime().atZone(ZoneOffset.UTC).toLocalDate();
            Optional<BoardRepository.Capture> capture = board.latestBefore(sport, BoardRepository.SOURCE_BLEND, on);
            if (capture.isEmpty()) {
                adp = new AdpState(BoardRepository.SOURCE_BLEND, null, NO_ADP_STORED);
            } else {
                adp = new AdpState(BoardRepository.SOURCE_BLEND, capture.get().capturedOn(), null);
                for (BoardRepository.Row r : capture.get().rows()) {
                    String sleeperId = sleeperIdByLocalId.get(r.playerId());
                    if (sleeperId != null) adpBySleeperId.put(sleeperId, r.adp());
                }
            }
        }

        DraftGrades dg = complete ? memoisedGrades(draft, token) : grades.read(draft);
        DraftGradesState gradesState = new DraftGradesState(dg.available(), dg.reason(), dg.gradesEarly(),
                dg.weeksCounted());

        Map<String, DraftPick> picks = new HashMap<>();
        Map<String, Double> value = new HashMap<>();
        if (complete) {
            List<DraftRepository.PickRow> stored = drafts.picks(draft.id());
            Map<Integer, String> names = grades.managerNamesBySlot(draft, stored);
            Map<Integer, Double> valueByPick = new HashMap<>();
            if (dg.available()) {
                for (PickGrade pg : dg.picks()) if (pg.valueOverSlot() != null) valueByPick.put(pg.pickNo(), pg.valueOverSlot());
            }
            for (DraftRepository.PickRow pr : stored) {
                String sleeperId = pr.playerId() == null ? null : sleeperIdByLocalId.get(pr.playerId());
                if (sleeperId == null) continue;
                picks.putIfAbsent(sleeperId, new DraftPick(pr.pickNo(), pr.round(), names.get(pr.draftSlot())));
                Double v = valueByPick.get(pr.pickNo());
                if (v != null) value.putIfAbsent(sleeperId, v);
            }
        }
        return new Joined(state, adp, gradesState, picks, value, adpBySleeperId);
    }

    private DraftGrades memoisedGrades(DraftRepository.DraftRow draft, SeasonToken token) {
        int scoringHash = leagues.scoringOf(draft.leagueId()).hashCode();
        Memo have = memo.get(draft.id());
        if (have != null && Objects.equals(have.status(), draft.status()) && Objects.equals(have.token(), token)
                && have.scoringHash() == scoringHash) {
            return have.grades();
        }
        DraftGrades fresh = grades.read(draft);
        memo.put(draft.id(), new Memo(draft.status(), token, scoringHash, fresh));
        return fresh;
    }
}

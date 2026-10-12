package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Where a league's season is, in one place (specs/030-post-draft-season-view FR-001, data-model E2,
 * as amended by plan review B2/N6).
 *
 * <p>This is the <b>only</b> implementation of the rule; the league home reads it through
 * {@code SeasonStartService} rather than rebuilding it from the draft status and the standings.
 * The check order matters and is pinned by tests:
 * <ol>
 *   <li>{@code league.complete()} gives {@link SeasonState#COMPLETE}.</li>
 *   <li>The league is not drafted gives {@link SeasonState#PRE_DRAFT}. "Drafted" means the league's
 *       draft row has status {@code complete}. A league with no draft row at all (an imported
 *       season) falls back to {@code league.status}: drafted unless it is {@code pre_draft} or
 *       {@code drafting}.</li>
 *   <li>No FINAL scored week gives {@link SeasonState#DRAFTED_UNSCORED}.</li>
 *   <li>Otherwise {@link SeasonState#IN_SEASON}.</li>
 * </ol>
 *
 * <p><b>Why stored/final weeks and not Sleeper's status</b> (research R5): the live NBA 2026 league
 * reports {@code status: in_season} before a single game has been played, so Sleeper's word cannot
 * identify the window between the draft and tip-off. And it is {@link ScoredWeeks#of} {@code
 * .latestFinal()} rather than a stored-week count (plan review B2): NBA week 1 is stored partial on
 * its first night, and flipping the home to {@code IN_SEASON} then would remove the preseason block
 * for a whole week with nothing in its place. Draft grades and playoff odds already use the final
 * rule, so this is not a fourth definition of "scored".
 */
@Service
public class SeasonStateService {

    public enum SeasonState { PRE_DRAFT, DRAFTED_UNSCORED, IN_SEASON, COMPLETE }

    private final DraftRepository drafts;
    private final ScoredWeeks scoredWeeks;

    public SeasonStateService(DraftRepository drafts, ScoredWeeks scoredWeeks) {
        this.drafts = drafts;
        this.scoredWeeks = scoredWeeks;
    }

    public SeasonState stateOf(LeagueRepository.LeagueRow league) {
        if (league.complete()) return SeasonState.COMPLETE;
        if (!drafted(league)) return SeasonState.PRE_DRAFT;
        return scoredWeeks.of(league.id()).latestFinal() == 0
                ? SeasonState.DRAFTED_UNSCORED : SeasonState.IN_SEASON;
    }

    private boolean drafted(LeagueRepository.LeagueRow league) {
        Optional<DraftRepository.DraftRow> draft = drafts.forLeague(league.id());
        if (draft.isPresent()) return "complete".equals(draft.get().status());
        String status = league.status();
        return !"pre_draft".equals(status) && !"drafting".equals(status);
    }
}

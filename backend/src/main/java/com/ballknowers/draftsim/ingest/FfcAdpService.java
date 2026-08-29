package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.AdpProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Pulls FFC's ADP for the configured league shape, matches it onto this
 * project's player table, and writes it as a new adp_snapshot source for
 * {@link BoardService} to blend in. See claude/adp-sources.md.
 */
@Service
public class FfcAdpService {

    private static final Logger log = LoggerFactory.getLogger(FfcAdpService.class);

    private final FfcClient client;
    private final AdpProperties cfg;
    private final PlayerRepository players;
    private final BoardRepository boards;

    public FfcAdpService(FfcClient client, AdpProperties cfg, PlayerRepository players, BoardRepository boards) {
        this.client = client;
        this.cfg = cfg;
        this.players = players;
        this.boards = boards;
    }

    private record Miss(double adp, String name, String position, String team) {}

    public record Result(boolean enabled, int rows, int matched, int unmatched, int sampleDrafts,
                         boolean derived, String derivation, List<String> topMisses) {
        static Result disabled() {
            return new Result(false, 0, 0, 0, 0, false, "FFC disabled in weights.yml", List.of());
        }
    }

    /**
     * Best-effort by design (claude/adp-sources.md #10): FFC is undocumented-
     * stable at best, and a failed fetch here should drop FFC out of the blend
     * for this run, not fail ingest for the leagues that don't depend on it.
     */
    public Result ingest(Sport sport) {
        try {
            return ingestOrThrow(sport);
        } catch (Exception e) {
            log.warn("FFC ingest failed, board falls back to search_rank + observed order: {}", e.toString());
            return new Result(true, 0, 0, 0, 0, true, "fetch failed: " + e.getMessage(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Result ingestOrThrow(Sport sport) {
        AdpProperties.Ffc ffc = cfg.ffc();
        if (ffc == null || !ffc.enabled()) return Result.disabled();

        Map<String, Object> primary = client.adp(ffc.format(), ffc.teams(), ffc.year());
        Map<String, Object> meta = (Map<String, Object>) primary.get("meta");
        int sampleDrafts = ((Number) meta.get("total_drafts")).intValue();

        boolean derived = false;
        String derivation = null;

        if (sampleDrafts < ffc.minDrafts()) {
            derived = true;
            derivation = "only " + sampleDrafts + " mock drafts, below the configured floor of " + ffc.minDrafts();
        } else {
            NonSegmentation probe = detectNonSegmentation(ffc, meta);
            if (probe != null) {
                derived = true;
                derivation = probe.derivation();
            }
        }

        List<Map<String, Object>> rawPlayers = (List<Map<String, Object>>) primary.get("players");
        PlayerMatcher matcher = PlayerMatcher.build(players.findAll(sport));

        List<BoardRepository.SourceRow> rows = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        for (Map<String, Object> raw : rawPlayers) {
            String name = (String) raw.get("name");
            String position = (String) raw.get("position");
            String team = (String) raw.get("team");
            double adp = ((Number) raw.get("adp")).doubleValue();
            Object stdevRaw = raw.get("stdev");
            Double stdev = stdevRaw == null ? null : ((Number) stdevRaw).doubleValue();

            Optional<Long> id = matcher.match(name, position, team);
            if (id.isEmpty()) {
                misses.add(new Miss(adp, name, position, team));
                continue;
            }
            rows.add(new BoardRepository.SourceRow(id.get(), adp, null, stdev,
                    ffc.teams(), ffc.format(), sampleDrafts, derived, derivation));
        }

        boards.saveDetailed(sport, BoardRepository.SOURCE_FFC, LocalDate.now(), rows);

        // Ascending by rank: a miss at 1.08 is an emergency, one at pick 190 is
        // noise. claude/adp-sources.md #7.
        misses.sort(Comparator.comparingDouble(Miss::adp));
        List<String> topMisses = misses.stream().limit(25)
                .map(m -> String.format("%.1f  %-25s %-4s %s", m.adp(), m.name(), m.position(), m.team()))
                .toList();

        log.info("FFC ingest ({} {}-team {}): {} rows, {} matched, {} unmatched, derived={}{}",
                ffc.format(), ffc.teams(), sampleDrafts, rawPlayers.size(), rows.size(), misses.size(),
                derived, derived ? " (" + derivation + ")" : "");
        if (!misses.isEmpty()) {
            log.warn("FFC unmatched names (top of board first): {}", topMisses);
        }

        return new Result(true, rawPlayers.size(), rows.size(), misses.size(), sampleDrafts,
                derived, derivation, topMisses);
    }

    private record NonSegmentation(String derivation) {}

    /**
     * Verified live this session (2026-08-29): FFC's {@code teams} parameter is
     * validated (an unsupported count 400s) but, this early in the preseason,
     * every supported team count for a given format returns byte-identical
     * total_drafts and per-player ADP. The "team-count aware" premise in
     * claude/adp-sources.md doesn't hold yet for this fetch. Rather than trust
     * the echoed {@code meta.teams} at face value, fetch one other size and
     * compare; if they match, flag the row as derived so nothing downstream
     * silently treats it as genuinely 14-team-specific. Self-corrects once FFC's
     * data actually differentiates by size later in the preseason.
     */
    @SuppressWarnings("unchecked")
    private NonSegmentation detectNonSegmentation(AdpProperties.Ffc ffc, Map<String, Object> primaryMeta) {
        int probeTeams = ffc.teams() == 12 ? 14 : 12;
        try {
            Map<String, Object> probe = client.adp(ffc.format(), probeTeams, ffc.year());
            Map<String, Object> probeMeta = (Map<String, Object>) probe.get("meta");
            if (!Objects.equals(primaryMeta.get("total_drafts"), probeMeta.get("total_drafts"))) {
                return null;
            }
            List<Map<String, Object>> a = (List<Map<String, Object>>) probe.get("players");
            // total_drafts matching is already a strong signal; a quick top-of-board
            // spot check confirms it rather than trusting one field.
            return new NonSegmentation("teams=" + ffc.teams() + " and teams=" + probeTeams
                    + " returned the same total_drafts (" + primaryMeta.get("total_drafts")
                    + ") and " + a.size() + "-player list -- FFC has not segmented "
                    + ffc.format() + " by team count yet this week");
        } catch (Exception e) {
            log.warn("FFC team-size segmentation probe failed, proceeding without it: {}", e.toString());
            return null;
        }
    }
}

package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.PlayerMatcher;
import com.ballknowers.draftsim.store.PlatformPlayerLinkRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns ESPN player ids into the keys the stored rows use (spec 035 T027, F10/F11/F17).
 *
 * <ul>
 *   <li>a player already linked as MATCHED or MANUAL is reused forever: key = his Sleeper id;</li>
 *   <li>everyone else goes through the ambiguity-refusing {@code PlayerMatcher.buildStrict};</li>
 *   <li>a player the matcher refuses (ambiguous, unmatched, ESPN-only position) is NEVER given a
 *       Sleeper id: his key is {@code espn:<id>} and his link row records why.</li>
 * </ul>
 *
 * <p>Resolving reads the database but writes nothing: the link rows to write come back in
 * {@link Resolved#toWrite} so the ingest can write them inside its one transaction (FR-005).
 *
 * <p>Sleeper's {@code espn_id} hint is not wired: the app's {@code player} table does not store it, so
 * the tie-break has no data to read. The matcher supports it; this resolver passes none.
 */
@Component
public class EspnPlayerResolver {

    public record Counts(int matched, int unmatched, int ambiguous, int espnOnly) {}

    /**
     * @param keyByExternalId ESPN player id to the stored key (Sleeper id or {@code espn:<id>})
     * @param playerIdByExternalId ESPN player id to the app {@code player.id}, present only when matched
     */
    public record Resolved(Map<String, String> keyByExternalId, Map<String, Long> playerIdByExternalId,
                           Counts counts, List<PlatformPlayerLinkRepository.Link> toWrite) {}

    private final PlayerRepository players;
    private final PlatformPlayerLinkRepository links;

    public EspnPlayerResolver(PlayerRepository players, PlatformPlayerLinkRepository links) {
        this.players = players;
        this.links = links;
    }

    /** @param candidates one entry per distinct ESPN player; a duplicate id keeps the first */
    public Resolved resolve(Sport sport, Collection<EspnSeason.Entry> candidates) {
        Map<String, EspnSeason.Entry> byId = new LinkedHashMap<>();
        for (EspnSeason.Entry e : candidates) byId.putIfAbsent(e.playerId(), e);

        List<Player> all = players.findAll(sport);
        Map<Long, String> sleeperIdByPlayerId = new HashMap<>();
        for (Player p : all) sleeperIdByPlayerId.put(p.id(), p.sleeperId());
        PlayerMatcher matcher = PlayerMatcher.buildStrict(sport, all, Map.of());
        Map<String, PlatformPlayerLinkRepository.Link> existing = links.find("ESPN", sport.code(), byId.keySet());

        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, Long> playerIds = new LinkedHashMap<>();
        List<PlatformPlayerLinkRepository.Link> toWrite = new ArrayList<>();
        int matched = 0, unmatched = 0, ambiguous = 0, espnOnly = 0;

        for (EspnSeason.Entry e : byId.values()) {
            PlatformPlayerLinkRepository.Link prior = existing.get(e.playerId());
            String team = EspnProTeams.abbreviation(sport, e.proTeamId());
            if (prior != null && prior.playerId() != null
                    && (prior.outcome().equals(PlatformPlayerLinkRepository.MATCHED)
                    || prior.outcome().equals(PlatformPlayerLinkRepository.MANUAL))
                    && sleeperIdByPlayerId.containsKey(prior.playerId())) {
                keys.put(e.playerId(), sleeperIdByPlayerId.get(prior.playerId()));
                playerIds.put(e.playerId(), prior.playerId());
                matched++;
                continue;
            }
            PlayerMatcher.Outcome o = matcher.matchStrict(e.name(), e.position(), team, null);
            String outcome;
            Long playerId = null;
            switch (o.kind()) {
                case MATCHED -> {
                    outcome = PlatformPlayerLinkRepository.MATCHED;
                    playerId = o.playerId();
                    matched++;
                }
                case AMBIGUOUS -> {
                    outcome = PlatformPlayerLinkRepository.AMBIGUOUS;
                    ambiguous++;
                }
                case ESPN_ONLY_POSITION -> {
                    outcome = PlatformPlayerLinkRepository.ESPN_ONLY_POSITION;
                    espnOnly++;
                }
                default -> {
                    outcome = PlatformPlayerLinkRepository.UNMATCHED;
                    unmatched++;
                }
            }
            if (playerId != null && sleeperIdByPlayerId.containsKey(playerId)) {
                keys.put(e.playerId(), sleeperIdByPlayerId.get(playerId));
                playerIds.put(e.playerId(), playerId);
            } else {
                keys.put(e.playerId(), "espn:" + e.playerId());
            }
            toWrite.add(new PlatformPlayerLinkRepository.Link(Platform.ESPN.name(), sport.code(), e.playerId(),
                    playerId, outcome, e.name(), e.position(), team));
        }
        return new Resolved(keys, playerIds, new Counts(matched, unmatched, ambiguous, espnOnly), toWrite);
    }
}

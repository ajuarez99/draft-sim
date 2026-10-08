package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.*;
import java.util.*;
import static com.ballknowers.draftsim.util.Rounding.round2;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

import static com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

/**
 * UNETHICAL (the conduct list) and the suspension-week window: pure arithmetic over
 * rows the service has already read.
 *
 * <p>Moved out of {@link SeasonSuperlativesService} by specs/021-codebase-cleanup (T051)
 * as a pure move, proven by live JSON parity against the pre-split build.
 */
final class SuperlativeConductMath {

    private SuperlativeConductMath() {}

    /** The filter itself, pure and package-private so a test can drive it without Postgres. */
    static List<Integer> boundedCapturedWeeks(Set<Integer> capturedWeeks, int throughWeek) {
        return capturedWeeks.stream()
                .filter(w -> w >= 1 && w <= throughWeek)
                .sorted()
                .toList();
    }

    /**
     * UNETHICAL (T058): a player-week qualifies for roster {@code T} when he
     * is a key in {@code T}'s {@code players_points} that week (research
     * R11/T019's membership rule, same as JOEL_EMBIID) and either he was
     * captured suspended that week, or a commissioner conduct entry for this
     * league applies from that week on. Package-private and pure -- no
     * Postgres, no DB rows -- so {@code UnethicalAwardTest} (T057) can drive
     * it directly.
     */
    static Map<Integer, List<ConductQualifyingWeeks>> computeUnethical(
            Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer,
            Map<Integer, Set<String>> suspendedByWeek,
            List<LeagueConductRepository.Entry> conductEntries) {
        Map<Integer, List<ConductQualifyingWeeks>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Set<Integer>>> rosterEntry : rosteredByRosterPlayer.entrySet()) {
            int rosterId = rosterEntry.getKey();
            for (Map.Entry<String, Set<Integer>> playerEntry : rosterEntry.getValue().entrySet()) {
                String playerId = playerEntry.getKey();
                Set<Integer> rosteredWeeks = playerEntry.getValue();

                List<Integer> suspendedWeeks = rosteredWeeks.stream()
                        .filter(w -> suspendedByWeek.getOrDefault(w, Set.of()).contains(playerId))
                        .sorted()
                        .toList();
                if (!suspendedWeeks.isEmpty()) {
                    out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                            .add(new ConductQualifyingWeeks(rosterId, playerId, "SUSPENDED", suspendedWeeks, null));
                }

                for (LeagueConductRepository.Entry ce : conductEntries) {
                    if (!ce.playerId().equals(playerId)) continue;
                    List<Integer> weeks = rosteredWeeks.stream()
                            .filter(w -> w >= ce.appliesFromWeek())
                            .sorted()
                            .toList();
                    if (!weeks.isEmpty()) {
                        out.computeIfAbsent(rosterId, k -> new ArrayList<>())
                                .add(new ConductQualifyingWeeks(rosterId, playerId, "COMMISSIONER", weeks, ce.reason()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * (T058, amended 2026-09-23 after live verification): names WHICH source
     * came up empty rather than always blaming "no suspension captured" --
     * live, a capture already existed (just not one that caught a rostered
     * player), and the old fixed string was simply wrong in that case. The
     * commissioner clause is appended only when that list is actually empty,
     * never when it merely failed to produce a qualifying week (an entry
     * whose {@code appliesFromWeek} hasn't been reached yet is a real,
     * non-empty list that just doesn't qualify anyone today). Package-private
     * and pure so a test can drive all four combinations without Postgres.
     */
    static String unethicalEmptyReason(boolean anyCapture, boolean conductListEmpty) {
        String base = anyCapture
                ? "no rostered player has been suspended in a tracked week"
                : "suspension tracking hasn't covered a scored week yet";
        return conductListEmpty ? base + ", and the commissioner's list is empty" : base;
    }
}

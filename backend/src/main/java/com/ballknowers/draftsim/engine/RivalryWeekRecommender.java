package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.RivalryProperties;
import com.ballknowers.draftsim.engine.RivalryMatching.Matching;
import com.ballknowers.draftsim.engine.RivalryMatching.PairId;
import com.ballknowers.draftsim.engine.RivalryMatching.PairScore;
import com.ballknowers.draftsim.engine.RivalryService.Displaced;
import com.ballknowers.draftsim.engine.RivalryService.HeldBack;
import com.ballknowers.draftsim.engine.RivalryService.Pairing;
import com.ballknowers.draftsim.engine.RivalryService.Reason;
import com.ballknowers.draftsim.engine.RivalryService.Unpaired;
import com.ballknowers.draftsim.engine.RivalryService.WeekOption;
import com.ballknowers.draftsim.engine.RivalryService.WeekPairing;
import com.ballknowers.draftsim.engine.RivalryService.WeekRecommendation;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.ScheduledPair;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Function;

import static com.ballknowers.draftsim.engine.RivalryMatching.round3;
import static com.ballknowers.draftsim.engine.RivalryService.num3;

/**
 * Spec 037's week pick, turned into the wire {@link WeekRecommendation}: the gates (config, season format,
 * published schedule, rosters, eligible weeks), the call into {@link RivalryWeekPicker}, and the reason text.
 * No Spring, no DB: {@link RivalryService} loads everything and hands it over, and passes in how it renders a
 * matching's pairings and sit-out so a week's pairings read exactly like spec 034's.
 */
final class RivalryWeekRecommender {

    static final String CAP_LABEL = "league rule";

    private RivalryWeekRecommender() {}

    /** What the week pick needs beyond spec 034's inputs, all already loaded by {@link RivalryService#forLeague}. */
    record WeekContext(LeagueRow league, List<ScheduledPair> scheduled, OptionalInt leg, int lastScoredWeek,
                       PlayoffFormat fmt, int rosterCount, boolean unsupportedRosters) {}

    /**
     * @param unconstrained spec 034's history-best matching, already solved
     * @param pairingsOf    spec 034's rendering of a matching's pairs (scores, reasons, thin flags), sorted
     * @param unpairedOf    spec 034's rendering of a matching's sit-out, null when there is none
     */
    static WeekRecommendation recommend(RivalryProperties props, WeekContext ctx, List<Long> managerIds, PairScore fn,
                                        Matching unconstrained, Map<Long, String> nameOf,
                                        Function<Matching, List<Pairing>> pairingsOf,
                                        Function<Matching, Unpaired> unpairedOf) {
        int boundary = Math.max(ctx.leg().isPresent() ? ctx.leg().getAsInt() : 0, ctx.lastScoredWeek());
        // S3: the boundary eligibility really uses, not the raw leg, so the panel's "as of week" matches it.
        Integer asOf = ctx.leg().isPresent() || ctx.lastScoredWeek() > 0 ? boundary : null;
        // Order of checks (plan "Final week status union"): config, format, schedule, rosters, eligibility, fit.
        if (!props.weekLoaded()) return refusal("CONFIG_MISSING", null, asOf);
        int cap = props.maxRegularSeasonMeetings();
        PlayoffFormat fmt = ctx.fmt();
        if (fmt == null || fmt.playoffWeekStart() < 2) return refusal("NO_SEASON_FORMAT", cap, asOf);
        int regularEnd = fmt.playoffWeekStart() - 1;

        // F4: published means EVERY regular-season week has its full set of games; a short week would make the
        // meeting counts undercount and a pair look allowed that will in fact meet a third time.
        Map<Integer, Integer> gamesInWeek = new HashMap<>();
        for (ScheduledPair p : ctx.scheduled()) gamesInWeek.merge(p.week(), 1, Integer::sum);
        int perWeek = ctx.rosterCount() / 2;
        boolean published = perWeek >= 1;
        for (int w = 1; w <= regularEnd && published; w++) published = gamesInWeek.getOrDefault(w, 0) == perWeek;
        if (!published) return refusal("NOT_PUBLISHED", cap, asOf);

        // F5: the rivalry set is per manager, Sleeper's week is per roster; only a one-to-one season maps.
        if (ctx.unsupportedRosters()) return refusal("UNSUPPORTED_ROSTERS", cap, asOf);

        // R5/F8: strictly after the week Sleeper calls current and after the last scored week; never a played,
        // in-progress or playoff week, and nothing at all once the season is complete.
        List<Integer> eligible = new ArrayList<>();
        if (!ctx.league().complete()) {
            for (int w = boundary + 1; w <= regularEnd; w++) eligible.add(w);
        }
        if (eligible.isEmpty()) return refusal("NO_ELIGIBLE_WEEK", cap, asOf);

        // Manager-level regular-season schedule. After the F5 gate every roster has exactly one manager, so
        // nothing is dropped except self-pairs (impossible here); the filter is kept defensive.
        Map<Integer, List<long[]>> schedule = new TreeMap<>();
        Map<PairId, List<Integer>> weeksOfPair = new HashMap<>();
        for (ScheduledPair p : ctx.scheduled()) {
            if (p.week() > regularEnd) continue;
            if (p.aManagerId() == null || p.bManagerId() == null || p.aManagerId().equals(p.bManagerId())) continue;
            schedule.computeIfAbsent(p.week(), k -> new ArrayList<>()).add(new long[]{p.aManagerId(), p.bManagerId()});
            weeksOfPair.computeIfAbsent(PairId.of(p.aManagerId(), p.bManagerId()), k -> new ArrayList<>()).add(p.week());
        }
        for (List<Integer> weeks : weeksOfPair.values()) Collections.sort(weeks);

        RivalryWeekPicker.WeekPick pick = RivalryWeekPicker.pick(managerIds, schedule, eligible, cap, fn,
                props.maxManagers(), unconstrained);
        String overCapNote = overCapNote(pick.overCapPairs(), nameOf, cap);

        List<Integer> unfit = new ArrayList<>();
        List<WeekOption> options = new ArrayList<>();
        for (RivalryWeekPicker.Candidate c : pick.candidates()) {
            if (!c.feasible()) { unfit.add(c.week()); continue; }
            options.add(option(c, pick, weeksOfPair, boundary, cap, pairingsOf, unpairedOf));
        }
        Collections.sort(unfit);
        if (options.isEmpty()) {
            return new WeekRecommendation("NO_WEEK_FITS", cap, CAP_LABEL, asOf, null, List.of(), List.copyOf(unfit),
                    overCapNote);
        }
        return new WeekRecommendation("OK", cap, CAP_LABEL, asOf, options.get(0),
                List.copyOf(options.subList(1, options.size())), List.copyOf(unfit), overCapNote);
    }

    private static WeekRecommendation refusal(String status, Integer cap, Integer asOf) {
        return new WeekRecommendation(status, cap, cap == null ? null : CAP_LABEL, asOf, null, List.of(), List.of(),
                null);
    }

    private static WeekOption option(RivalryWeekPicker.Candidate c, RivalryWeekPicker.WeekPick pick,
                                     Map<PairId, List<Integer>> weeksOfPair, int boundary, int cap,
                                     Function<Matching, List<Pairing>> pairingsOf,
                                     Function<Matching, Unpaired> unpairedOf) {
        Matching m = c.matching();
        List<WeekPairing> wire = new ArrayList<>();
        for (Pairing p : pairingsOf.apply(m)) {
            wire.add(new WeekPairing(p, c.alreadyIn().contains(new PairId(p.aManagerId(), p.bManagerId()))));
        }
        List<HeldBack> held = new ArrayList<>();
        for (PairId id : c.heldBack()) {
            List<Integer> all = weeksOfPair.getOrDefault(id, List.of());
            List<Integer> others = new ArrayList<>(all);
            boolean inThisWeek = others.remove(Integer.valueOf(c.week()));
            held.add(new HeldBack(id.a(), id.b(), List.copyOf(others), all.size() - (inThisWeek ? 1 : 0) + 1));
        }
        List<Displaced> displaced = new ArrayList<>();
        for (PairId id : c.displaced()) {
            List<Integer> met = new ArrayList<>();
            List<Integer> still = new ArrayList<>();
            for (int w : weeksOfPair.getOrDefault(id, List.of())) {
                if (w == c.week()) continue;
                if (w <= boundary) met.add(w); else still.add(w);
            }
            displaced.add(new Displaced(id.a(), id.b(), List.copyOf(met), List.copyOf(still)));
        }
        double bestTotal = pick.unconstrained().total();
        return new WeekOption(c.week(), round3(c.total()), round3(bestTotal), reason(c, pick, cap), wire,
                unpairedOf.apply(m), held, displaced);
    }

    /**
     * F1: the code is decided by SET comparison, not by comparing totals. ALL_BEST_FIT: nothing held back and
     * the pairs equal the unconstrained pairs. EQUAL_UNDER_CAP: equal total (within EPS) but a different set.
     * BEST_UNDER_CAP: a lower total. Every number in the text is in {@code numbers}.
     */
    private static Reason reason(RivalryWeekPicker.Candidate c, RivalryWeekPicker.WeekPick pick, int cap) {
        int firstTiedWeek = c.firstTiedWeek();
        int held = c.heldBack().size();
        int bestPairs = pick.unconstrained().pairs().size();
        double total = round3(c.total());
        double bestTotal = round3(pick.unconstrained().total());
        Map<String, Object> nums = new LinkedHashMap<>();
        nums.put("week", c.week());
        nums.put("cap", cap);
        nums.put("heldBack", held);
        nums.put("bestPairings", bestPairs);
        nums.put("total", total);
        nums.put("bestTotal", bestTotal);
        nums.put("tiedWeeks", c.tiedWith());
        // B1/S2: only the first week of a tie group is "the earliest"; later ones say which week comes first.
        boolean tied = c.tiedWith() > 1;
        boolean firstOfGroup = c.week() == firstTiedWeek;
        if (tied && !firstOfGroup) nums.put("firstTiedWeek", firstTiedWeek);
        String tieAll = !tied ? "" : firstOfGroup
                ? " It's the earliest of " + c.tiedWith() + " weeks where they all fit."
                : " Ties with week " + firstTiedWeek + " on every count; week " + firstTiedWeek + " comes first.";
        String tieEqual = !tied ? "" : firstOfGroup
                ? " It's the earliest of " + c.tiedWith() + " equally strong weeks."
                : " Ties with week " + firstTiedWeek + " on every count; week " + firstTiedWeek + " comes first.";
        String limit = cap == 1 ? "more than once" : "more than " + cap + " times";
        String code;
        String text;
        // N8b: decided on the displayed (round3) totals, the same ones the ranking uses.
        boolean equalTotal = total == bestTotal;
        if (held == 0 && c.sameAsBest()) {
            code = "ALL_BEST_FIT";
            text = (bestPairs == 1
                    ? "The best pairing fits week " + c.week()
                    : "All " + bestPairs + " of the best pairings fit week " + c.week())
                    + " without any pair meeting " + limit + "." + tieAll;
        } else if (equalTotal) {
            code = "EQUAL_UNDER_CAP";
            text = (held == 0
                    ? "An equally strong set fits week " + c.week() + "."
                    : held + " of the best pairings would go over the " + cap + "-meeting limit in week " + c.week()
                            + "; an equally strong set replaces " + (held == 1 ? "it." : "them."))
                    + tieEqual;
        } else {
            code = "BEST_UNDER_CAP";
            text = (held == 0
                    ? "This set scores " + num3(total) + " of a possible " + num3(bestTotal) + " in week " + c.week() + "."
                    : held + " of the best pairings would go over the " + cap + "-meeting limit in week " + c.week()
                            + ", so " + (held == 1 ? "it's" : "they're") + " swapped out. This set scores "
                            + num3(total) + " of a possible " + num3(bestTotal) + ".")
                    + tieEqual;
        }
        return new Reason(code, text, nums);
    }

    private static String overCapNote(List<RivalryWeekPicker.OverCap> over, Map<Long, String> nameOf, int cap) {
        if (over.isEmpty()) return null;
        List<String> bits = new ArrayList<>();
        for (RivalryWeekPicker.OverCap o : over) {
            bits.add(nameOf.get(o.a()) + " and " + nameOf.get(o.b()) + " meet " + o.meetings() + " times");
        }
        return "The published schedule already has " + String.join("; ", bits) + ", above the " + cap
                + "-meeting rule, so " + (over.size() == 1 ? "that pair isn't" : "those pairs aren't")
                + " offered in any week.";
    }
}

package com.ballknowers.draftsim.historical;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one display order for stat keys (review finding 11): the counting stats first (pts, reb, ast, stl, blk, to,
 * tpm), then the other box stats, then the derived keys. Postgres jsonb reorders object keys (by length, then
 * bytes) and {@code Map.copyOf} is hash-ordered, so anything the screen lists is passed through here.
 */
public final class StatOrder {

    public static final List<String> ORDER = List.of("pts", "reb", "ast", "stl", "blk", "to", "tpm", "fgm", "fga",
            "ftm", "fta", "dd", "td", "bonus_pt_40p", "bonus_pt_50p", "bonus_reb_20p", "bonus_ast_15p", "tf", "ff",
            // Football (spec 056 T008), appended so the NBA order above is unchanged.
            "pass_yd", "pass_td", "pass_int", "rush_yd", "rush_td", "rec", "rec_yd", "rec_td", "fum_lost",
            "pass_2pt", "rush_2pt", "rec_2pt", "st_td");

    private StatOrder() {}

    private static int rank(String k) {
        int i = ORDER.indexOf(k);
        return i < 0 ? ORDER.size() : i;
    }

    public static List<String> sorted(Collection<String> keys) {
        return keys.stream().sorted(Comparator.comparingInt(StatOrder::rank).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** A copy in canonical key order (a LinkedHashMap, never Map.of: values may be null). */
    public static <V> Map<String, V> sorted(Map<String, V> m) {
        Map<String, V> out = new LinkedHashMap<>();
        for (String k : sorted(m.keySet())) out.put(k, m.get(k));
        return out;
    }
}

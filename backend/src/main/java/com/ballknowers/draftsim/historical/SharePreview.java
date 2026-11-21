package com.ballknowers.draftsim.historical;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words and numbers behind a shared Redraft link's preview card (spec 079): the page title and description the
 * web server writes into the head, the picture's alt text, and what the PNG draws.
 *
 * <p><b>Privacy by construction (PR-09).</b> {@link #from} first projects the result through {@link #project}, a
 * whitelist of exactly the fields {@code HistoricalGameService.reduced(...)} keeps for today's game of the day, and
 * builds only from that projection. So the preview of a full result and of its reduced form are equal, and no player,
 * lineup, hindsight roster or hindsight total can reach a card, even after midnight. Change {@code reduced} and this
 * together.
 *
 * <p>The wording mirrors the web's shared-page voice ({@code outcomeHeadline(..., 'shared')}, {@code ordinal},
 * {@code dayLabel}, {@code shareChosenLine}); {@code SharePreviewTest} pins the same vectors as the web tests.
 */
public record SharePreview(String title, String description, String imageAlt, Card card) {

    /** What the picture draws. {@code record} and {@code draftScore} are null when the result has none. */
    public record Card(String brandLine, String headline, boolean champion, String record, Integer draftScore,
                       String seasonLine, String dayLine, boolean chosenSeason, List<String> marks) {
        public Card {
            marks = marks == null ? List.of() : List.copyOf(marks);
        }
    }

    static final String BRAND = "Ball Knowers Redraft";
    static final String CHOSEN_LINE = "Chosen season, not the game of the day";   // = web shareChosenLine
    private static final String TROPHY = "🏆 ";

    /** The part of a result a card may read. Mirrors what {@code reduced(...)} keeps. */
    record Projection(String userOutcome, int userFinish, boolean oldFormat, Integer wins, Integer losses,
                      Integer ties, Integer draftScore, List<String> marks) {}

    /**
     * @param dailyNumber the game of the day's number when the state carries one (spec 078), else null
     */
    public static SharePreview from(PublicGame pub, Integer dailyNumber) {
        HistoricalGameState s = pub.state();
        Projection p = project(s.result(), s.userSlot());
        String sport = s.sport() == null ? "" : s.sport();
        String label = s.season() == null ? "" : s.season().label();
        String seasonLine = (label + " " + sport).trim();
        String day = s.dailyDate() == null ? null : dayLabel(s.dailyDate());

        boolean champion = !p.oldFormat() && "CHAMPION".equals(p.userOutcome());
        String headline = headline(p, s.teams());
        String record = p.wins() == null ? null
                : p.wins() + "-" + p.losses() + (p.ties() != null && p.ties() > 0 ? "-" + p.ties() : "");

        String title;
        String headTitle = (champion ? TROPHY : "") + headline;
        if (s.dailyDate() != null) {
            String head = dailyNumber != null ? "Redraft daily #" + dailyNumber : "Redraft daily";
            title = head + " · " + day + " · " + headTitle;
        } else {
            title = BRAND + " · " + seasonLine + " · " + headTitle;
        }

        List<String> parts = new ArrayList<>();
        if (record != null) parts.add(record);
        if (p.draftScore() != null) parts.add("Draft score " + p.draftScore() + "%");
        parts.add(seasonLine);
        if (s.chosenSeason()) parts.add(CHOSEN_LINE);
        parts.add(s.dailyDate() != null ? "Play today's Redraft." : "Draft a past season.");
        String description = String.join(" · ", parts);

        int ahead = 0, same = 0, behind = 0;
        for (String m : p.marks()) {
            switch (m) {
                case "ahead" -> ahead++;
                case "behind" -> behind++;
                default -> same++;
            }
        }
        StringBuilder alt = new StringBuilder(headline);
        if (record != null) alt.append(", ").append(record);
        if (p.draftScore() != null) alt.append(", draft score ").append(p.draftScore()).append('%');
        alt.append(", ").append(seasonLine).append('.');
        if (!p.marks().isEmpty()) {
            alt.append(' ').append(ahead).append(ahead == 1 ? " pick" : " picks").append(" ahead of their draft spot, ")
                    .append(same).append(" even, ").append(behind).append(" behind.");
        }

        Card card = new Card(BRAND.toUpperCase(Locale.ROOT), headline, champion, record, p.draftScore(), seasonLine,
                day, s.chosenSeason(), p.marks());
        return new SharePreview(title, description, alt.toString(), card);
    }

    /** The whitelist. Reads nothing but the fields {@code reduced(...)} keeps. */
    static Projection project(JsonNode result, int userSlot) {
        if (result == null || !result.isObject()) {
            return new Projection(null, 0, true, null, null, null, null, List.of());
        }
        String outcome = result.path("userOutcome").isTextual() ? result.get("userOutcome").asText() : null;
        int finish = result.path("userFinish").asInt(0);
        boolean old = !result.has("format") || result.get("format").isNull();
        Integer wins = null, losses = null, ties = null;
        for (JsonNode row : result.path("standings")) {
            if (row.path("slot").asInt(-1) == userSlot) {
                wins = row.path("wins").asInt(0);
                losses = row.path("losses").asInt(0);
                ties = row.path("ties").asInt(0);
                break;
            }
        }
        JsonNode h = result.path("hindsight");
        // The total is used only to compute the score and never leaves this method (reduced() drops it too).
        Integer score = DraftScore.of(h.path("userPointsFor").isNumber() ? h.get("userPointsFor").asDouble() : null,
                h.path("pointsFor").isNumber() ? h.get("pointsFor").asDouble() : null);
        if (score == null && h.path("draftScore").isNumber()) score = h.get("draftScore").asInt();
        List<String> marks = new ArrayList<>();
        JsonNode rows = result.path("pickFinishes");
        if (rows.isArray()) {
            List<JsonNode> sorted = new ArrayList<>();
            rows.forEach(sorted::add);
            sorted.sort((a, b) -> Integer.compare(a.path("pickNo").asInt(), b.path("pickNo").asInt()));
            for (JsonNode f : sorted) {
                String m = f.path("mark").isTextual() ? f.get("mark").asText() : null;
                if (!"ahead".equals(m) && !"same".equals(m) && !"behind".equals(m)) {
                    m = HistoricalGameService.markOf(f.path("draftedPositionRank").asInt(),
                            f.path("finishPositionRank").asInt());
                }
                marks.add(m);
            }
        }
        return new Projection(outcome, finish, old, wins, losses, ties, score, marks);
    }

    /** Web {@code outcomeHeadline(..., 'shared')}, and {@code oldFinish} in the shared voice. */
    static String headline(Projection p, int teams) {
        if (p.oldFormat()) return "This team finished " + ordinal(p.userFinish()) + " of " + teams;
        String o = p.userOutcome() == null ? "" : p.userOutcome();
        return switch (o) {
            case "CHAMPION" -> "Champion";
            case "LOST_FINAL" -> "This team lost the final";
            case "LOST_SEMIFINAL" -> "This team lost in the semifinal";
            case "LOST_ROUND_ONE" -> "This team lost in round one";
            case "MISSED_PLAYOFFS" -> "This team missed the playoffs (" + ordinal(p.userFinish()) + " of " + teams + ")";
            default -> "This team finished " + ordinal(p.userFinish()) + " of " + teams;
        };
    }

    static String ordinal(int n) {
        int v = n % 100;
        if (v >= 11 && v <= 13) return n + "th";
        return n + switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** "Nov 21" from an ISO date (en-US month abbreviation, no year), as the web's {@code dayLabel}. */
    static String dayLabel(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
        } catch (RuntimeException e) {
            return iso;
        }
    }
}

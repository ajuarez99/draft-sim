package com.ballknowers.draftsim.historical;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a share-preview card (spec 079) as a 1200x630 PNG with Java2D, from a {@link SharePreview.Card} only. Colours
 * are the app's dark tokens as in {@code web/scripts/og-image/og-image-tm.svg}. All content sits inside x 120..1080 so a
 * centred square-ish crop keeps the essentials. No emoji glyphs (a server's fonts do not have them): the trophy is
 * drawn, the pick squares are filled rectangles.
 *
 * <p>{@link #VERSION} is the render version: the web puts it in the image URL ({@code ?v=}) so a changed drawing is
 * not hidden behind an unfurler's cache. Bump it whenever the picture changes (PR-19).
 *
 * <p>Fonts: the logical {@code Font.SANS_SERIF}. Whether the production image has a usable font is not verified
 * from this machine; a missing font configuration surfaces as an Error, which {@link #render} turns into an
 * {@link SharePreviewRenderException} (the service answers 503, the page itself is unaffected).
 */
public final class SharePreviewImage {

    /** Bump when the drawing changes. */
    public static final int VERSION = 1;

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;
    static final int LEFT = 120;
    static final int RIGHT = 1080;

    static final Color BG = new Color(0x04, 0x09, 0x13);
    static final Color TEXT = new Color(0xf1, 0xf3, 0xf6);
    static final Color MUTED = new Color(0x8d, 0x96, 0xa5);
    static final Color ACCENT = new Color(0x4f, 0xd1, 0xc5);
    static final Color GOLD = new Color(0xe0, 0xb3, 0x41);
    static final Color AHEAD = new Color(0x37, 0xa0, 0x5a);
    static final Color SAME = new Color(0x5b, 0x64, 0x72);
    static final Color BEHIND = new Color(0xd4, 0x50, 0x4c);

    static {
        // Spring Boot sets this itself; a bare unit test or a tool run would not. Never overrides an explicit choice.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    private SharePreviewImage() {}

    /** One pick square, in pixels. */
    public record Square(int x, int y, int size, String mark) {
        public int centreX() { return x + size / 2; }
        public int centreY() { return y + size / 2; }
    }

    static Color colourOf(String mark) {
        return switch (mark == null ? "" : mark) {
            case "ahead" -> AHEAD;
            case "behind" -> BEHIND;
            default -> SAME;
        };
    }

    /** Where each pick square goes: rows of at most 10, shrinking as rows are added, left-aligned at x 120. */
    public static List<Square> layout(List<String> marks) {
        List<Square> out = new ArrayList<>();
        int n = marks.size();
        if (n == 0) return out;
        int perRow = Math.min(10, n);
        int rows = (n + 9) / 10;
        int gap = rows > 2 ? 8 : 12;
        int maxH = 140;
        int size = Math.min(60, (maxH - (rows - 1) * gap) / rows);
        size = Math.min(size, (RIGHT - LEFT - (perRow - 1) * gap) / perRow);
        size = Math.max(size, 10);
        int top = 405;
        for (int i = 0; i < n; i++) {
            int r = i / 10;
            int c = i % 10;
            out.add(new Square(LEFT + c * (size + gap), top + r * (size + gap), size, marks.get(i)));
        }
        return out;
    }

    /** @throws SharePreviewRenderException when the drawing or the PNG encoding failed (a headless/font problem) */
    public static byte[] render(SharePreview.Card card) {
        Graphics2D g = null;
        try {
            BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            draw(g, card);
            ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
            if (!ImageIO.write(img, "png", out)) throw new SharePreviewRenderException("no PNG writer", null);
            return out.toByteArray();
        } catch (Exception | Error e) {
            if (e instanceof VirtualMachineError vme) throw vme;
            throw new SharePreviewRenderException("share preview render failed: " + e, e);
        } finally {
            if (g != null) g.dispose();
        }
    }

    private static void draw(Graphics2D g, SharePreview.Card card) {
        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // brand line, letter-spaced
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.setColor(MUTED);
        spaced(g, card.brandLine(), LEFT, 92, 5);

        // day / season line
        String sub = card.dayLine() == null ? card.seasonLine() : card.dayLine() + "  ·  " + card.seasonLine();
        if (card.chosenSeason()) sub += "  ·  chosen season";
        g.setColor(ACCENT);
        fit(g, sub, RIGHT - LEFT, 34, 20, Font.BOLD);
        g.drawString(sub, LEFT, 142);

        // headline, with a drawn trophy for a champion
        int hx = LEFT;
        if (card.champion()) {
            trophy(g, LEFT, 178, 64);
            hx = LEFT + 84;
        }
        g.setColor(card.champion() ? GOLD : TEXT);
        fit(g, card.headline(), RIGHT - hx, 76, 32, Font.BOLD);
        g.drawString(card.headline(), hx, 238);

        // record and draft score
        int x = LEFT;
        int base = 345;
        if (card.record() != null) {
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            g.drawString(card.record(), x, base);
            x += g.getFontMetrics().stringWidth(card.record()) + 56;
        }
        if (card.draftScore() != null) {
            String s = "Draft score " + card.draftScore() + "%";
            g.setColor(ACCENT);
            fit(g, s, RIGHT - x, 52, 24, Font.BOLD);
            g.drawString(s, x, base - 4);
        }

        // the pick squares
        for (Square sq : layout(card.marks())) {
            g.setColor(colourOf(sq.mark()));
            int arc = Math.max(4, sq.size() / 5);
            g.fillRoundRect(sq.x(), sq.y(), sq.size(), sq.size(), arc, arc);
        }

        // footer
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        g.setColor(MUTED);
        String footer = "ballknowers.co";
        g.drawString(footer, RIGHT - g.getFontMetrics().stringWidth(footer), 598);
    }

    /** Shrinks the graphics font from {@code start} until {@code text} fits {@code maxWidth}, never below {@code min}. */
    private static void fit(Graphics2D g, String text, int maxWidth, int start, int min, int style) {
        for (int size = start; size >= min; size -= 2) {
            Font f = new Font(Font.SANS_SERIF, style, size);
            g.setFont(f);
            if (g.getFontMetrics(f).stringWidth(text) <= maxWidth) return;
        }
        g.setFont(new Font(Font.SANS_SERIF, style, min));
    }

    private static void spaced(Graphics2D g, String text, int x, int y, int spacing) {
        FontMetrics fm = g.getFontMetrics();
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            g.drawString(ch, cx, y);
            cx += fm.stringWidth(ch) + spacing;
        }
    }

    /** A plain gold cup: bowl, two handles, stem and base. {@code s} is its height; it spans about 0.9 s. */
    private static void trophy(Graphics2D g, int x, int y, int s) {
        g.setColor(GOLD);
        int w = (int) (s * 0.9);
        int bowlW = (int) (w * 0.62);
        int bx = x + (w - bowlW) / 2;
        g.fillRoundRect(bx, y, bowlW, (int) (s * 0.52), 14, 14);
        g.setStroke(new java.awt.BasicStroke(Math.max(3f, s / 14f)));
        g.drawArc(x, y + 4, (int) (w * 0.34), (int) (s * 0.32), 90, 180);
        g.drawArc(x + w - (int) (w * 0.34), y + 4, (int) (w * 0.34), (int) (s * 0.32), 270, 180);
        g.fillRect(x + w / 2 - 4, y + (int) (s * 0.52), 8, (int) (s * 0.28));
        g.fillRoundRect(x + w / 2 - (int) (w * 0.26), y + (int) (s * 0.78), (int) (w * 0.52), (int) (s * 0.2), 6, 6);
        g.setStroke(new java.awt.BasicStroke(1f));
    }
}

package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 079: the picture. Prints render time and allocation (the numbers go in the build report). */
class SharePreviewImageTest {

    private static List<String> marks(int n) {
        List<String> m = new ArrayList<>();
        for (int i = 0; i < n; i++) m.add(i % 3 == 0 ? "ahead" : i % 3 == 1 ? "same" : "behind");
        return m;
    }

    private static SharePreview.Card champion(int picks) {
        return new SharePreview.Card("BALL KNOWERS REDRAFT", "Champion", true, "13-1", 87, "2003-04 NBA", "Nov 21",
                false, marks(picks));
    }

    private static SharePreview.Card missed(int picks) {
        return new SharePreview.Card("BALL KNOWERS REDRAFT", "This team missed the playoffs (10th of 12)", false, "5-9",
                61, "2003-04 NBA", null, true, marks(picks));
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersAValidPngOfTheShareSize() throws IOException {
        byte[] png = SharePreviewImage.render(champion(18));
        BufferedImage img = decode(png);
        assertNotNull(img);
        assertEquals(1200, img.getWidth());
        assertEquals(630, img.getHeight());
        assertEquals(0xFF040913, img.getRGB(5, 5), "background colour");
    }

    @Test
    void eachPickSquareIsDrawnInItsMarkColour() throws IOException {
        SharePreview.Card card = champion(18);
        BufferedImage img = decode(SharePreviewImage.render(card));
        List<SharePreviewImage.Square> squares = SharePreviewImage.layout(card.marks());
        assertEquals(18, squares.size());
        for (SharePreviewImage.Square sq : squares) {
            int expected = SharePreviewImage.colourOf(sq.mark()).getRGB();
            assertEquals(expected, img.getRGB(sq.centreX(), sq.centreY()), "square " + sq);
            assertTrue(sq.x() >= 120 && sq.x() + sq.size() <= 1080, "inside the safe crop: " + sq);
            assertTrue(sq.y() + sq.size() <= 560, "clear of the footer: " + sq);
        }
        assertEquals(3, squares.stream().map(s -> img.getRGB(s.centreX(), s.centreY())).distinct().count());
    }

    @Test
    void squaresWrapAtTenAndShrinkForManyPicks() {
        List<SharePreviewImage.Square> few = SharePreviewImage.layout(marks(10));
        assertEquals(1, few.stream().map(SharePreviewImage.Square::y).distinct().count(), "10 fit one row");
        List<SharePreviewImage.Square> many = SharePreviewImage.layout(marks(45));
        assertEquals(5, many.stream().map(SharePreviewImage.Square::y).distinct().count());
        assertTrue(many.get(0).size() < few.get(0).size(), "more rows, smaller squares");
        for (var sq : many) {
            assertTrue(sq.x() + sq.size() <= 1080 && sq.y() + sq.size() <= 560, "inside the card: " + sq);
        }
        assertTrue(SharePreviewImage.layout(List.of()).isEmpty());
    }

    @Test
    void aCardWithNoRecordScoreOrSquaresStillRenders() throws IOException {
        SharePreview.Card bare = new SharePreview.Card("BALL KNOWERS REDRAFT", "This team finished 3rd of 12", false, null,
                null, "2003-04 NBA", null, false, List.of());
        assertEquals(630, decode(SharePreviewImage.render(bare)).getHeight());
    }

    @Test
    void writesSamplesAndMeasuresRenderCost() throws Exception {
        String dir = System.getProperty("sharepreview.samples", System.getenv("SHAREPREVIEW_SAMPLES"));
        if (dir != null) {
            Files.createDirectories(Path.of(dir));
            Files.write(Path.of(dir, "champion.png"), SharePreviewImage.render(champion(14)));
            Files.write(Path.of(dir, "missed-playoffs.png"), SharePreviewImage.render(missed(14)));
        }
        for (int i = 0; i < 5; i++) SharePreviewImage.render(champion(18));
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long[] ms = new long[20];
        long[] bytes = new long[20];
        int pngBytes = 0;
        for (int i = 0; i < 20; i++) {
            long a0 = mx.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();
            pngBytes = SharePreviewImage.render(champion(18)).length;
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
            bytes[i] = mx.getThreadAllocatedBytes(tid) - a0;
        }
        Arrays.sort(ms);
        Arrays.sort(bytes);
        System.out.println("SHARE-PREVIEW-RENDER median_ms=" + ms[10] + " max_ms=" + ms[19]
                + " median_alloc_bytes=" + bytes[10] + " png_bytes=" + pngBytes);
        assertTrue(pngBytes > 1000 && pngBytes < 200_000, "png size: " + pngBytes);
    }
}

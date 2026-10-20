package com.forgeddetect.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Draws report panels from the coarse forensic grids stored at analysis time. Shapes and pixels
 * only (no text), so it runs headless without system fonts; labels live in the PDF.
 */
@Component
public class ForensicPanelRenderer {

    public enum Palette {
        /** Grad-CAM attention, matching the heatmap shown in the UI. */
        JET(new double[] {0, .125, .375, .625, .875, 1},
                new int[][] {{0, 0, 143}, {0, 0, 255}, {0, 255, 255}, {255, 255, 0}, {255, 0, 0}, {128, 0, 0}}),
        /** FFT magnitude. */
        INFERNO(new double[] {0, .25, .5, .75, 1},
                new int[][] {{0, 0, 4}, {87, 16, 110}, {188, 55, 84}, {249, 142, 9}, {252, 255, 164}}),
        /** Error level: dark = consistent compression, bright = residual. */
        ELA(new double[] {0, .5, 1}, new int[][] {{6, 8, 14}, {30, 120, 160}, {225, 250, 255}});

        private final double[] stops;
        private final int[][] colors;

        Palette(double[] stops, int[][] colors) {
            this.stops = stops;
            this.colors = colors;
        }

        int rgb(double t) {
            double v = Math.max(0, Math.min(1, t));
            for (int i = 1; i < stops.length; i++) {
                if (v <= stops[i]) {
                    double f = (v - stops[i - 1]) / (stops[i] - stops[i - 1]);
                    int[] a = colors[i - 1];
                    int[] b = colors[i];
                    int r = (int) Math.round(a[0] + f * (b[0] - a[0]));
                    int g = (int) Math.round(a[1] + f * (b[1] - a[1]));
                    int bl = (int) Math.round(a[2] + f * (b[2] - a[2]));
                    return (r << 16) | (g << 8) | bl;
                }
            }
            int[] last = colors[colors.length - 1];
            return (last[0] << 16) | (last[1] << 8) | last[2];
        }
    }

    /** Bilinearly upsampled, min-max normalised square panel as PNG bytes. */
    public byte[] grid(double[][] grid, Palette palette, int size) {
        int rows = grid.length;
        int cols = grid[0].length;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] row : grid) {
            for (double v : row) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        double range = max - min > 1e-9 ? max - min : 1;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            double gy = clamp((py + 0.5) * rows / size - 0.5, 0, rows - 1);
            int y0 = (int) Math.floor(gy);
            int y1 = Math.min(y0 + 1, rows - 1);
            double fy = gy - y0;
            for (int px = 0; px < size; px++) {
                double gx = clamp((px + 0.5) * cols / size - 0.5, 0, cols - 1);
                int x0 = (int) Math.floor(gx);
                int x1 = Math.min(x0 + 1, cols - 1);
                double fx = gx - x0;
                double top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx;
                double bottom = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx;
                double v = top * (1 - fy) + bottom * fy;
                img.setRGB(px, py, palette.rgb((v - min) / range));
            }
        }
        return png(img);
    }

    /** True when a grid carries no signal (e.g. Grad-CAM for a near-zero forgery score). */
    public static boolean isFlat(double[][] grid) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] row : grid) {
            for (double v : row) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return max - min < 1e-6;
    }

    /** Neutral hatched panel for a grid with no signal, so it doesn't read as a solid-colour result. */
    public byte[] empty(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF3F4F6));
            g.fillRect(0, 0, size, size);
            g.setColor(new Color(0xE5E7EB));
            g.setStroke(new BasicStroke(1.5f));
            for (int d = -size; d < size; d += 18) {
                g.drawLine(d, size, d + size, 0);
            }
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /**
     * 8×8 mosaic of the 64-bit perceptual hash. Depicts the stored fingerprint of the pixels without
     * reconstructing the image (used when no forensic signature grids were captured).
     */
    public byte[] phashMosaic(String phash, int size) {
        long bits = phashBits(phash);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x12151A));
            g.fillRect(0, 0, size, size);
            int pad = Math.max(8, size / 20);
            int cell = (size - 2 * pad) / 8;
            int origin = (size - cell * 8) / 2;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int bit = 63 - (row * 8 + col);
                    boolean on = ((bits >>> bit) & 1L) == 1L;
                    g.setColor(on ? new Color(0xE8E4DF) : new Color(0x2A3038));
                    g.fillRect(origin + col * cell, origin + row * cell, cell - 1, cell - 1);
                }
            }
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /**
     * Twin horizontal meters for forgery and explicit scores with review / auto-flag guides.
     * Drawn from the stored scores only — no media.
     */
    public byte[] scoreMeters(
            double forgery, double explicit, double forgeryReview, double forgeryAuto,
            double explicitReview, double explicitAuto, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x12151A));
            g.fillRect(0, 0, size, size);
            int pad = Math.max(16, size / 12);
            int trackH = Math.max(18, size / 10);
            int gap = Math.max(28, size / 5);
            int y0 = size / 2 - gap / 2 - trackH;
            drawMeter(g, pad, y0, size - 2 * pad, trackH, clamp(forgery, 0, 1), forgeryReview, forgeryAuto,
                    new Color(0xC53026));
            drawMeter(g, pad, y0 + gap, size - 2 * pad, trackH, clamp(explicit, 0, 1), explicitReview, explicitAuto,
                    new Color(0xA16207));
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /**
     * Deterministic pattern from the pixel content hash (SHA-256 hex). Binds the report to one file
     * without showing any pixels.
     */
    public byte[] hashPattern(String contentHash, int size) {
        byte[] digest = hashBytes(contentHash);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int n = 16;
        int cell = Math.max(1, size / n);
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int idx = (row * n + col) % digest.length;
                int v = digest[idx] & 0xFF;
                // Mix neighbour nibble so adjacent cells vary even with short inputs
                int mix = (digest[(idx + 7) % digest.length] & 0xFF);
                int tone = (v * 3 + mix) / 4;
                int r = 18 + (tone * 40) / 255;
                int gre = 22 + (tone * 48) / 255;
                int b = 28 + (tone * 70) / 255;
                int rgb = (r << 16) | (gre << 8) | b;
                for (int py = row * cell; py < Math.min(size, (row + 1) * cell); py++) {
                    for (int px = col * cell; px < Math.min(size, (col + 1) * cell); px++) {
                        img.setRGB(px, py, rgb);
                    }
                }
            }
        }
        return png(img);
    }

    private static void drawMeter(
            Graphics2D g, int x, int y, int width, int height, double value, double review, double auto, Color fill) {
        g.setColor(new Color(0x2A3038));
        g.fillRoundRect(x, y, width, height, height / 2, height / 2);
        int filled = Math.max(2, (int) Math.round(value * width));
        g.setColor(fill);
        g.fillRoundRect(x, y, filled, height, height / 2, height / 2);
        g.setStroke(new BasicStroke(Math.max(1.5f, height / 10f)));
        g.setColor(new Color(0xE8C27A));
        int rx = x + (int) Math.round(clamp(review, 0, 1) * width);
        g.drawLine(rx, y - 3, rx, y + height + 3);
        g.setColor(new Color(0xF07167));
        int ax = x + (int) Math.round(clamp(auto, 0, 1) * width);
        g.drawLine(ax, y - 3, ax, y + height + 3);
    }

    /** Prefer a real 16-hex pHash; otherwise fold the string into 64 bits so legacy rows still draw. */
    static long phashBits(String phash) {
        if (phash != null) {
            String hex = phash.trim().toLowerCase();
            if (hex.matches("[0-9a-f]{16}")) {
                return Long.parseUnsignedLong(hex, 16);
            }
        }
        long h = 0xcbf29ce484222325L;
        if (phash != null) {
            for (int i = 0; i < phash.length(); i++) {
                h ^= phash.charAt(i);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    private static byte[] hashBytes(String contentHash) {
        String raw = contentHash == null ? "" : contentHash.trim().toLowerCase();
        if (raw.startsWith("sha256:")) {
            raw = raw.substring("sha256:".length());
        }
        if (raw.matches("[0-9a-f]{64}")) {
            byte[] out = new byte[32];
            for (int i = 0; i < 32; i++) {
                out[i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        // Deterministic fallback for missing / non-hex hashes (pre-hash rows)
        long a = phashBits(raw.isEmpty() ? "missing-content-hash" : raw);
        long b = phashBits(raw + "#fmd");
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (a >>> (56 - i * 8));
            out[8 + i] = (byte) (b >>> (56 - i * 8));
        }
        return out;
    }

    /** Radial FFT profile as a filled line chart (low frequency on the left). */
    public byte[] profile(double[] values, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0xE5E7EB));
            for (int i = 1; i < 4; i++) {
                int y = height * i / 4;
                g.drawLine(0, y, width, y);
            }
            g.setColor(new Color(0xFDE2E4));
            g.fillRect(width / 2, 0, width / 2, height);

            double max = 1e-9;
            for (double v : values) {
                max = Math.max(max, v);
            }
            int pad = 6;
            Path2D.Double line = new Path2D.Double();
            Path2D.Double area = new Path2D.Double();
            area.moveTo(0, height);
            for (int i = 0; i < values.length; i++) {
                double x = (double) i / (values.length - 1) * width;
                double y = height - pad - values[i] / max * (height - 2 * pad);
                if (i == 0) {
                    line.moveTo(x, y);
                } else {
                    line.lineTo(x, y);
                }
                area.lineTo(x, y);
            }
            area.lineTo(width, height);
            area.closePath();
            g.setColor(new Color(99, 102, 241, 60));
            g.fill(area);
            g.setColor(new Color(0x4F46E5));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /**
     * Phase 23 video frame strip: one bar per sampled frame (forgery score, coloured by band), explicit
     * score as a dark line, dashed review / auto-flag guides, and the peak frame outlined.
     */
    public byte[] frameStrip(
            double[] forgery, double[] explicit, double review, double autoFlag, int peakIndex, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int pad = 6;
            int plot = height - 2 * pad;
            int n = Math.max(1, forgery.length);
            double slot = (double) width / n;
            int gap = slot > 6 ? 2 : 0;

            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] {6, 4}, 0));
            for (double[] guide : new double[][] {{review, 0xF59E0B}, {autoFlag, 0xE11D48}}) {
                int y = (int) Math.round(height - pad - guide[0] * plot);
                g.setColor(new Color((int) guide[1]));
                g.drawLine(0, y, width, y);
            }

            for (int i = 0; i < forgery.length; i++) {
                double v = clamp(forgery[i], 0, 1);
                int x = (int) Math.round(i * slot) + gap / 2;
                int w = Math.max(1, (int) Math.round(slot) - gap);
                int h = Math.max(1, (int) Math.round(v * plot));
                g.setColor(v >= autoFlag ? new Color(0xE11D48) : v >= review ? new Color(0xF59E0B) : new Color(0x10B981));
                g.fillRect(x, height - pad - h, w, h);
                if (i == peakIndex) {
                    g.setColor(new Color(0x111827));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRect(x, pad, w, plot);
                }
            }

            if (explicit != null && explicit.length > 1) {
                Path2D.Double line = new Path2D.Double();
                for (int i = 0; i < explicit.length; i++) {
                    double x = i * slot + slot / 2;
                    double y = height - pad - clamp(explicit[i], 0, 1) * plot;
                    if (i == 0) {
                        line.moveTo(x, y);
                    } else {
                        line.lineTo(x, y);
                    }
                }
                g.setColor(new Color(0x4F46E5));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(line);
            }
        } finally {
            g.dispose();
        }
        return png(img);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

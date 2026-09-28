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

package com.forgeddetect.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ForensicPanelRendererTest {

    private final ForensicPanelRenderer renderer = new ForensicPanelRenderer();

    @Test
    void flatGridsAreDetectedAndRenderedAsNeutralPanels() throws Exception {
        double[][] flat = new double[14][14];
        double[][] peaked = new double[14][14];
        peaked[3][10] = 0.9;

        assertThat(ForensicPanelRenderer.isFlat(flat)).isTrue();
        assertThat(ForensicPanelRenderer.isFlat(peaked)).isFalse();

        BufferedImage empty = ImageIO.read(new ByteArrayInputStream(renderer.empty(120)));
        assertThat(empty.getWidth()).isEqualTo(120);
        assertThat(empty.getRGB(1, 60) & 0xFFFFFF).isNotEqualTo(0x00008F);

        BufferedImage heat = ImageIO.read(new ByteArrayInputStream(
                renderer.grid(peaked, ForensicPanelRenderer.Palette.JET, 140)));
        int hot = heat.getRGB(10 * 10 + 5, 3 * 10 + 5);
        assertThat((hot >> 16) & 0xFF).as("peak cell renders red").isGreaterThan(100);
        assertThat(heat.getRGB(2, 130) & 0xFFFFFF).as("cold cell renders JET floor").isEqualTo(0x00008F);
    }

    @Test
    void fingerprintSurrogatesRenderFromStoredHashesAndScores() throws Exception {
        assertThat(ForensicPanelRenderer.phashBits("ffffffffffffffff")).isEqualTo(-1L);
        assertThat(ForensicPanelRenderer.phashBits("0000000000000000")).isEqualTo(0L);

        BufferedImage mosaic = ImageIO.read(new ByteArrayInputStream(renderer.phashMosaic("ffffffffffffffff", 160)));
        assertThat(mosaic.getWidth()).isEqualTo(160);
        // Centre of a light cell should be near the cream on-bit colour
        int on = mosaic.getRGB(40, 40) & 0xFFFFFF;
        assertThat((on >> 16) & 0xFF).isGreaterThan(180);

        BufferedImage meters = ImageIO.read(new ByteArrayInputStream(
                renderer.scoreMeters(0.9, 0.2, 0.5, 0.85, 0.5, 0.85, 200)));
        assertThat(meters.getWidth()).isEqualTo(200);

        BufferedImage patternA = ImageIO.read(new ByteArrayInputStream(
                renderer.hashPattern("sha256:" + "ab".repeat(32), 128)));
        BufferedImage patternB = ImageIO.read(new ByteArrayInputStream(
                renderer.hashPattern("sha256:" + "cd".repeat(32), 128)));
        assertThat(patternA.getWidth()).isEqualTo(128);
        assertThat(patternA.getRGB(0, 0) & 0xFFFFFF).isNotEqualTo(patternB.getRGB(0, 0) & 0xFFFFFF);
    }
}

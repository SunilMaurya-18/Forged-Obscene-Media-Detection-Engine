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
}

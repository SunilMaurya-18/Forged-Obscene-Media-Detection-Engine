package com.forgeddetect.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 23: how videos are sampled and which per-frame aggregate each score is routed on.
 * Defaults: forgery on the top-k mean (one noisy frame shouldn't take a clip down), explicit on the
 * max (one explicit frame is enough).
 */
@ConfigurationProperties(prefix = "video")
public class VideoProperties {

    public enum Aggregate { MAX, TOPK_MEAN }

    private double fps = 1.0;
    private int topK = 3;
    private Aggregate forgeryAggregate = Aggregate.TOPK_MEAN;
    private Aggregate explicitAggregate = Aggregate.MAX;

    /** Picks the configured aggregate from the ML service's {@code aggregate.<score>} block. */
    public static double pick(JsonNode aggregate, String score, Aggregate which, double fallback) {
        JsonNode block = aggregate == null ? null : aggregate.get(score);
        if (block == null) {
            return fallback;
        }
        JsonNode value = block.get(which == Aggregate.MAX ? "max" : "topk_mean");
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    public double getFps() {
        return fps;
    }

    /** Same bounds as the ML service's {@code /infer-video}; anything else would turn every video into a 400. */
    public void setFps(double fps) {
        if (!(fps > 0 && fps <= 10)) {
            throw new IllegalArgumentException("video.fps must be in (0, 10], got " + fps);
        }
        this.fps = fps;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        if (topK < 1 || topK > 32) {
            throw new IllegalArgumentException("video.top-k must be in [1, 32], got " + topK);
        }
        this.topK = topK;
    }

    public Aggregate getForgeryAggregate() {
        return forgeryAggregate;
    }

    public void setForgeryAggregate(Aggregate forgeryAggregate) {
        this.forgeryAggregate = forgeryAggregate;
    }

    public Aggregate getExplicitAggregate() {
        return explicitAggregate;
    }

    public void setExplicitAggregate(Aggregate explicitAggregate) {
        this.explicitAggregate = explicitAggregate;
    }
}

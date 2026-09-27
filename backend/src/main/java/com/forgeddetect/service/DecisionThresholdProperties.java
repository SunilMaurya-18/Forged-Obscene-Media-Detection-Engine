package com.forgeddetect.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable score thresholds for {@link DecisionEngineService}.
 * Bound from {@code decision.*} in application.yml — not hardcoded in routing logic.
 */
@ConfigurationProperties(prefix = "decision")
public class DecisionThresholdProperties {

    /**
     * Forgery score at/above which content is auto-flagged.
     */
    private double forgeryAutoFlag = 0.85;

    /**
     * Explicit score at/above which content is auto-flagged.
     */
    private double explicitAutoFlag = 0.85;

    /**
     * Forgery score at/above which content enters human review (below auto-flag).
     */
    private double forgeryReview = 0.50;

    /**
     * Explicit score at/above which content enters human review (below auto-flag).
     */
    private double explicitReview = 0.50;

    public double getForgeryAutoFlag() {
        return forgeryAutoFlag;
    }

    public void setForgeryAutoFlag(double forgeryAutoFlag) {
        this.forgeryAutoFlag = forgeryAutoFlag;
    }

    public double getExplicitAutoFlag() {
        return explicitAutoFlag;
    }

    public void setExplicitAutoFlag(double explicitAutoFlag) {
        this.explicitAutoFlag = explicitAutoFlag;
    }

    public double getForgeryReview() {
        return forgeryReview;
    }

    public void setForgeryReview(double forgeryReview) {
        this.forgeryReview = forgeryReview;
    }

    public double getExplicitReview() {
        return explicitReview;
    }

    public void setExplicitReview(double explicitReview) {
        this.explicitReview = explicitReview;
    }
}

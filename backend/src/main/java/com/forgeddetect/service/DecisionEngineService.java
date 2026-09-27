package com.forgeddetect.service;

import com.forgeddetect.model.Decision;
import org.springframework.stereotype.Service;

/**
 * Pure routing logic: map forgery + explicit scores to a moderation decision.
 * No HTTP or persistence dependencies.
 */
@Service
public class DecisionEngineService {

    private final DecisionThresholdProperties thresholds;

    public DecisionEngineService(DecisionThresholdProperties thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * Route scores to {@link Decision}.
     * <ul>
     *   <li>AUTO_FLAG — either score ≥ its auto-flag threshold</li>
     *   <li>REVIEW — either score ≥ its review threshold (and below auto-flag)</li>
     *   <li>ALLOW — both scores below review thresholds</li>
     * </ul>
     * Boundary rule: thresholds use {@code >=} (inclusive).
     */
    public Decision route(double forgeryScore, double explicitScore) {
        validateScore(forgeryScore, "forgeryScore");
        validateScore(explicitScore, "explicitScore");

        if (forgeryScore >= thresholds.getForgeryAutoFlag()
                || explicitScore >= thresholds.getExplicitAutoFlag()) {
            return Decision.AUTO_FLAG;
        }
        if (forgeryScore >= thresholds.getForgeryReview()
                || explicitScore >= thresholds.getExplicitReview()) {
            return Decision.REVIEW;
        }
        return Decision.ALLOW;
    }

    private static void validateScore(double score, String name) {
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1], got: " + score);
        }
    }
}

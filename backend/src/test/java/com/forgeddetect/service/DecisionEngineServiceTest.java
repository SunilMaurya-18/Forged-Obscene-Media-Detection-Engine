package com.forgeddetect.service;

import com.forgeddetect.model.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests — no Spring context, no HTTP.
 * Default thresholds: auto=0.85, review=0.50 (inclusive).
 */
class DecisionEngineServiceTest {

    private DecisionEngineService engine;

    @BeforeEach
    void setUp() {
        DecisionThresholdProperties thresholds = new DecisionThresholdProperties();
        thresholds.setForgeryAutoFlag(0.85);
        thresholds.setExplicitAutoFlag(0.85);
        thresholds.setForgeryReview(0.50);
        thresholds.setExplicitReview(0.50);
        engine = new DecisionEngineService(thresholds);
    }

    @Nested
    @DisplayName("ALLOW")
    class AllowCases {

        @Test
        void bothScoresWellBelowReview() {
            assertEquals(Decision.ALLOW, engine.route(0.1, 0.1));
        }

        @Test
        void bothJustBelowReviewBoundary() {
            assertEquals(Decision.ALLOW, engine.route(0.4999, 0.4999));
        }

        @Test
        void zerosAreAllowed() {
            assertEquals(Decision.ALLOW, engine.route(0.0, 0.0));
        }
    }

    @Nested
    @DisplayName("REVIEW")
    class ReviewCases {

        @Test
        void forgeryInReviewBand() {
            assertEquals(Decision.REVIEW, engine.route(0.6, 0.1));
        }

        @Test
        void explicitInReviewBand() {
            assertEquals(Decision.REVIEW, engine.route(0.1, 0.6));
        }

        @Test
        void bothInReviewBand() {
            assertEquals(Decision.REVIEW, engine.route(0.7, 0.7));
        }

        @Test
        void forgeryExactlyAtReviewThreshold() {
            assertEquals(Decision.REVIEW, engine.route(0.50, 0.0));
        }

        @Test
        void explicitExactlyAtReviewThreshold() {
            assertEquals(Decision.REVIEW, engine.route(0.0, 0.50));
        }

        @Test
        void justBelowAutoFlagIsStillReview() {
            assertEquals(Decision.REVIEW, engine.route(0.8499, 0.0));
            assertEquals(Decision.REVIEW, engine.route(0.0, 0.8499));
        }
    }

    @Nested
    @DisplayName("AUTO_FLAG")
    class AutoFlagCases {

        @Test
        void highForgeryAlone() {
            assertEquals(Decision.AUTO_FLAG, engine.route(0.95, 0.0));
        }

        @Test
        void highExplicitAlone() {
            assertEquals(Decision.AUTO_FLAG, engine.route(0.0, 0.95));
        }

        @Test
        void bothHigh() {
            assertEquals(Decision.AUTO_FLAG, engine.route(0.99, 0.99));
        }

        @Test
        void forgeryExactlyAtAutoFlagThreshold() {
            assertEquals(Decision.AUTO_FLAG, engine.route(0.85, 0.0));
        }

        @Test
        void explicitExactlyAtAutoFlagThreshold() {
            assertEquals(Decision.AUTO_FLAG, engine.route(0.0, 0.85));
        }

        @Test
        void onesAreAutoFlagged() {
            assertEquals(Decision.AUTO_FLAG, engine.route(1.0, 1.0));
        }
    }

    @ParameterizedTest(name = "forgery={0}, explicit={1} → {2}")
    @CsvSource({
            "0.0,  0.0,  ALLOW",
            "0.49, 0.49, ALLOW",
            "0.50, 0.0,  REVIEW",
            "0.0,  0.50, REVIEW",
            "0.84, 0.84, REVIEW",
            "0.85, 0.0,  AUTO_FLAG",
            "0.0,  0.85, AUTO_FLAG",
            "0.9,  0.1,  AUTO_FLAG",
            "0.1,  0.9,  AUTO_FLAG"
    })
    void boundaryMatrix(double forgery, double explicit, Decision expected) {
        assertEquals(expected, engine.route(forgery, explicit));
    }

    @Test
    void customThresholdsFromConfigAreHonored() {
        DecisionThresholdProperties custom = new DecisionThresholdProperties();
        custom.setForgeryAutoFlag(0.9);
        custom.setExplicitAutoFlag(0.9);
        custom.setForgeryReview(0.3);
        custom.setExplicitReview(0.3);
        DecisionEngineService customEngine = new DecisionEngineService(custom);

        assertEquals(Decision.ALLOW, customEngine.route(0.29, 0.29));
        assertEquals(Decision.REVIEW, customEngine.route(0.3, 0.0));
        assertEquals(Decision.AUTO_FLAG, customEngine.route(0.9, 0.0));
        // 0.85 is below custom auto-flag → REVIEW
        assertEquals(Decision.REVIEW, customEngine.route(0.85, 0.0));
    }

    @Test
    void rejectsOutOfRangeScores() {
        assertThrows(IllegalArgumentException.class, () -> engine.route(-0.01, 0.5));
        assertThrows(IllegalArgumentException.class, () -> engine.route(0.5, 1.01));
        assertThrows(IllegalArgumentException.class, () -> engine.route(Double.NaN, 0.5));
    }
}

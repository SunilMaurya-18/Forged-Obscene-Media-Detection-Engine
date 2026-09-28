package com.forgeddetect.model;

/**
 * Origin of a takedown request under the IT (Intermediary Guidelines) Amendment Rules, 2026.
 * Each type carries its own statutory response window (see {@code compliance.sla.*}).
 */
public enum ComplaintType {
    /** Non-consensual intimate / deepfake imagery reported by a user: 2-hour window. */
    USER_COMPLAINT,
    /** Takedown order from a government agency: 3-hour window. */
    GOVERNMENT_ORDER,
    /**
     * Phase 23: an uploader contesting a takedown. Only used to look up the appeal deadline in
     * {@code jurisdiction_rules}; never stored as an item's complaint type.
     */
    APPEAL
}

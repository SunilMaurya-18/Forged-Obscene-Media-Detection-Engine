package com.forgeddetect.investigation;

public enum SightingSource {
    /** Reported by a partner platform through {@code POST /api/v1/sightings}. */
    PARTNER_API,
    /** Recorded by a moderator from a report they received (e.g. a victim naming where they saw it). */
    MODERATOR
}

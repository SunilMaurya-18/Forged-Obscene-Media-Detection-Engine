package com.forgeddetect.model;

/** API plan; each tier maps to a per-minute request limit in {@code api.rate-limits}. */
public enum PlanTier {
    FREE,
    PRO,
    ENTERPRISE
}

package com.forgeddetect.investigation;

public enum LegalHoldStatus {
    /** Asked of the platform; not yet confirmed. */
    REQUESTED,
    /** The platform confirmed it is keeping the posts. */
    ACKNOWLEDGED,
    /** No longer needed; the platform may apply its normal retention again. */
    RELEASED;

    boolean active() {
        return this != RELEASED;
    }
}

package com.forgeddetect.investigation;

/** An approved request past its expiry is shown as EXPIRED but keeps APPROVED in the table. */
public enum LegalRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REVOKED
}

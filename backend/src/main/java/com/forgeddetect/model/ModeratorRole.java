package com.forgeddetect.model;

/**
 * A senior moderator also holds the MODERATOR role; only seniors may decide escalated items.
 * Phase 28: an INVESTIGATOR is an outside officer with a login but none of the moderator roles; they
 * read only the cases an approved legal request names.
 */
public enum ModeratorRole {
    MODERATOR,
    SENIOR_MODERATOR,
    INVESTIGATOR
}

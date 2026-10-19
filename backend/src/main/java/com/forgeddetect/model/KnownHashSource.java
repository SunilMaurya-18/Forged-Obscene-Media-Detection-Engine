package com.forgeddetect.model;

/** Where a known-abuse hash came from. */
public enum KnownHashSource {
    /** A moderator confirmed a takedown on this platform (or a startup seed hash). */
    INTERNAL_CONFIRMED,
    /** Imported from a StopNCII hash list (requires participating-organisation approval). */
    STOPNCII_IMPORT
}

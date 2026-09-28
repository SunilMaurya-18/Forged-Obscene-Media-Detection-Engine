package com.forgeddetect.investigation;

import java.util.regex.Pattern;

final class Hashes {

    private static final Pattern PHASH = Pattern.compile("^[0-9a-f]{16}$");

    private Hashes() {}

    static boolean isPhash(String h) {
        return h != null && PHASH.matcher(h).matches();
    }

    /** Hamming distance between two 64-bit pHashes, or -1 if either is missing or malformed. */
    static int hamming(String a, String b) {
        if (!isPhash(a) || !isPhash(b)) {
            return -1;
        }
        return Long.bitCount(Long.parseUnsignedLong(a, 16) ^ Long.parseUnsignedLong(b, 16));
    }
}

package com.forgeddetect.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** {@code PREFIX-XXXX-XXXX} reference numbers shared by complaints (FMD) and upload notices (UPL). */
public final class CaseNumbers {

    /** Crockford-style alphabet without 0/O, 1/I/L, so numbers survive being read out or handwritten. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final Pattern BODY = Pattern.compile("^[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$");
    private static final Pattern PIN = Pattern.compile("^[0-9]{6}$");

    private CaseNumbers() {}

    /** Accepts lower case, spaces and missing dashes ("upl 7k3q 9xpa"); null if it can't be a number with this prefix. */
    public static String normalize(String prefix, String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (s.startsWith(prefix)) {
            s = s.substring(prefix.length());
        }
        if (s.length() != 8) {
            return null;
        }
        String body = s.substring(0, 4) + "-" + s.substring(4);
        return BODY.matcher(body).matches() ? prefix + "-" + body : null;
    }

    public static String generate(String prefix, SecureRandom random, Predicate<String> taken) {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(prefix).append('-');
            for (int j = 0; j < 8; j++) {
                if (j == 4) {
                    sb.append('-');
                }
                sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            String n = sb.toString();
            if (!taken.test(n)) {
                return n;
            }
        }
        throw new IllegalStateException("Could not allocate a unique " + prefix + " number");
    }

    public static String newPin(SecureRandom random) {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    public static boolean isPinShaped(String pin) {
        return pin != null && PIN.matcher(pin).matches();
    }
}

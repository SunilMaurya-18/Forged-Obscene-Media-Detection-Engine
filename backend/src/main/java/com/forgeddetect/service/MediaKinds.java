package com.forgeddetect.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Phase 23: decides whether an upload goes to {@code /infer} or {@code /infer-video}. */
public final class MediaKinds {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "webm", "mkv", "avi");
    /** ISO-BMFF brands that are still images (HEIC/AVIF), not video. */
    private static final Set<String> IMAGE_BRANDS = Set.of("heic", "heix", "hevc", "mif1", "msf1", "avif", "avis");

    /** Images above this are refused with 413; videos may use the full multipart limit (50 MB). */
    public static final long IMAGE_MAX_BYTES = 25L * 1024 * 1024;

    private MediaKinds() {}

    public static boolean isVideo(String contentType, String filename, byte[] data) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            return true;
        }
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && VIDEO_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return sniffVideo(data);
    }

    static boolean sniffVideo(byte[] d) {
        if (d == null || d.length < 12) {
            return false;
        }
        if ((d[0] & 0xff) == 0x1a && (d[1] & 0xff) == 0x45 && (d[2] & 0xff) == 0xdf && (d[3] & 0xff) == 0xa3) {
            return true;
        }
        String riff = new String(d, 0, 4, StandardCharsets.ISO_8859_1);
        if (riff.equals("RIFF") && new String(d, 8, 4, StandardCharsets.ISO_8859_1).equals("AVI ")) {
            return true;
        }
        if (new String(d, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) {
            String brand = new String(d, 8, 4, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            return !IMAGE_BRANDS.contains(brand);
        }
        return false;
    }
}

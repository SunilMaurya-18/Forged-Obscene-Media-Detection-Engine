package com.forgeddetect.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** PEM, key-ID and raw-key helpers shared by the signing-key providers. */
public final class Ed25519Keys {

    public static final String ALGORITHM = "Ed25519";

    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519; the 32 raw key bytes follow. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private Ed25519Keys() {}

    public static String keyId(PublicKey key) {
        return HexFormat.of().formatHex(sha256(key.getEncoded())).substring(0, 16);
    }

    public static boolean isKeyId(String id) {
        return id != null && id.matches("^[0-9a-f]{16}$");
    }

    public static PublicKey fromRaw(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes, got " + raw.length);
        }
        byte[] der = new byte[X509_PREFIX.length + 32];
        System.arraycopy(X509_PREFIX, 0, der, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    public static byte[] unpem(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", ""));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

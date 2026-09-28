package com.forgeddetect.signing;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase 24: where evidentiary-report signing keys live. The report code only ever asks for a
 * signature and for public keys, so the private key can sit in a KMS/HSM and never enter this JVM.
 *
 * <p>Key IDs are the first 16 hex characters of the SHA-256 of the public key (DER), so anyone
 * holding a published public key can recompute its ID. Rotation makes a new key active; retired
 * public keys stay in the keyring so reports they signed still verify.
 */
public interface SigningKeyProvider {

    record SignatureResult(String keyId, byte[] signature) {}

    record KeyInfo(String keyId, Instant createdAt, boolean active, String publicKeyPem) {}

    /** Short label for logs, reports and the verify endpoint, e.g. {@code file} or {@code vault-transit}. */
    String kind();

    /** False for the development file keyring (private key on local disk). */
    boolean production();

    /** Ed25519 signature over {@code message} with the active key, and that key's ID. */
    SignatureResult sign(byte[] message);

    /** Any key in the keyring, active or retired. */
    Optional<PublicKey> publicKey(String keyId);

    String activeKeyId();

    /** Newest first. */
    List<KeyInfo> keys();

    /** Creates a new active key; the previous one is retired (verify-only). */
    KeyInfo rotate();
}

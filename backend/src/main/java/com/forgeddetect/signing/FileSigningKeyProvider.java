package com.forgeddetect.signing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development keyring on local disk: {@code keyring/<keyId>.pub}, the active key's
 * {@code keyring/<keyId>.key}, and {@code keyring/ACTIVE}. Rotation deletes the retired private key
 * and keeps its public key. The active pointer is re-read on every signature, so instances sharing
 * the directory pick up a rotation immediately.
 *
 * <p>This demonstrates the mechanism only: the private key is a file. Production uses a KMS/HSM
 * provider (e.g. {@link VaultTransitSigningKeyProvider}) where the key never leaves the device.
 */
public class FileSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(FileSigningKeyProvider.class);
    /** Phase 20 layout: one keypair directly in the key directory. Migrated into the keyring on first start. */
    private static final String LEGACY_PRIVATE = "report-signing-ed25519.key";
    private static final String LEGACY_PUBLIC = "report-signing-ed25519.pub";
    private static final String ACTIVE = "ACTIVE";

    private final Path dir;
    private final Path keyring;
    private final Map<String, PublicKey> publicKeys = new ConcurrentHashMap<>();
    private final Map<String, PrivateKey> privateKeys = new ConcurrentHashMap<>();

    public FileSigningKeyProvider(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        this.keyring = this.dir.resolve("keyring");
        try {
            initialise();
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Report signing keyring unavailable in " + this.dir + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public String kind() {
        return "file";
    }

    @Override
    public boolean production() {
        return false;
    }

    @Override
    public SignatureResult sign(byte[] message) {
        String id = activeKeyId();
        try {
            Signature signer = Signature.getInstance(Ed25519Keys.ALGORITHM);
            signer.initSign(privateKey(id));
            signer.update(message);
            return new SignatureResult(id, signer.sign());
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Could not sign with key " + id + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<PublicKey> publicKey(String keyId) {
        if (!Ed25519Keys.isKeyId(keyId)) {
            return Optional.empty();
        }
        PublicKey cached = publicKeys.get(keyId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = keyring.resolve(keyId + ".pub");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            PublicKey key = readPublic(file);
            publicKeys.put(keyId, key);
            return Optional.of(key);
        } catch (IOException | GeneralSecurityException ex) {
            log.warn("Unreadable public key {}: {}", file, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String activeKeyId() {
        try {
            return Files.readString(keyring.resolve(ACTIVE), StandardCharsets.US_ASCII).trim();
        } catch (IOException ex) {
            throw new IllegalStateException("No active report-signing key in " + keyring, ex);
        }
    }

    @Override
    public List<KeyInfo> keys() {
        String active = activeKeyId();
        List<KeyInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(keyring, "*.pub")) {
            for (Path f : files) {
                String id = f.getFileName().toString().replace(".pub", "");
                Optional<PublicKey> key = publicKey(id);
                if (key.isPresent()) {
                    out.add(new KeyInfo(id, Files.getLastModifiedTime(f).toInstant(), id.equals(active),
                            Ed25519Keys.pem("PUBLIC KEY", key.get().getEncoded())));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not list " + keyring, ex);
        }
        out.sort(Comparator.comparing(KeyInfo::active).thenComparing(KeyInfo::createdAt).reversed());
        return out;
    }

    @Override
    public synchronized KeyInfo rotate() {
        String previous = activeKeyId();
        try {
            KeyPair pair = KeyPairGenerator.getInstance(Ed25519Keys.ALGORITHM).generateKeyPair();
            String id = store(pair);
            writeActive(id);
            Files.deleteIfExists(keyring.resolve(previous + ".key"));
            privateKeys.remove(previous);
            log.warn("Report signing key rotated: {} -> {} (retired private key deleted; public key kept for verification)",
                    previous, id);
            return keys().stream().filter(k -> k.keyId().equals(id)).findFirst().orElseThrow();
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Key rotation failed: " + ex.getMessage(), ex);
        }
    }

    private void initialise() throws IOException, GeneralSecurityException {
        Files.createDirectories(keyring);
        if (Files.exists(keyring.resolve(ACTIVE))) {
            return;
        }
        Path legacyPriv = dir.resolve(LEGACY_PRIVATE);
        Path legacyPub = dir.resolve(LEGACY_PUBLIC);
        if (Files.exists(legacyPriv) && Files.exists(legacyPub)) {
            KeyFactory factory = KeyFactory.getInstance(Ed25519Keys.ALGORITHM);
            KeyPair pair = new KeyPair(readPublic(legacyPub),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Ed25519Keys.unpem(Files.readString(legacyPriv)))));
            String id = store(pair);
            writeActive(id);
            Files.delete(legacyPriv);
            Files.delete(legacyPub);
            log.info("Migrated the Phase 20 report-signing key {} into the keyring at {}", id, keyring);
            return;
        }
        String id = store(KeyPairGenerator.getInstance(Ed25519Keys.ALGORITHM).generateKeyPair());
        writeActive(id);
        log.warn("Generated a new DEV report-signing key {} in {}. Production must use a KMS/HSM provider.", id, keyring);
    }

    private String store(KeyPair pair) throws IOException {
        String id = Ed25519Keys.keyId(pair.getPublic());
        Path priv = keyring.resolve(id + ".key");
        Files.writeString(priv, Ed25519Keys.pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: rely on the directory's ACLs
        }
        Files.writeString(keyring.resolve(id + ".pub"), Ed25519Keys.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        publicKeys.put(id, pair.getPublic());
        privateKeys.put(id, pair.getPrivate());
        return id;
    }

    private void writeActive(String id) throws IOException {
        Path tmp = keyring.resolve(ACTIVE + ".tmp");
        Files.writeString(tmp, id, StandardCharsets.US_ASCII);
        try {
            Files.move(tmp, keyring.resolve(ACTIVE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, keyring.resolve(ACTIVE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private PrivateKey privateKey(String id) throws IOException, GeneralSecurityException {
        PrivateKey cached = privateKeys.get(id);
        if (cached != null) {
            return cached;
        }
        Path file = keyring.resolve(id + ".key");
        if (!Ed25519Keys.isKeyId(id) || !Files.exists(file)) {
            throw new GeneralSecurityException("private key for the active key " + id + " is missing");
        }
        PrivateKey key = KeyFactory.getInstance(Ed25519Keys.ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Ed25519Keys.unpem(Files.readString(file))));
        privateKeys.put(id, key);
        return key;
    }

    private static PublicKey readPublic(Path file) throws IOException, GeneralSecurityException {
        return KeyFactory.getInstance(Ed25519Keys.ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(Ed25519Keys.unpem(Files.readString(file))));
    }
}

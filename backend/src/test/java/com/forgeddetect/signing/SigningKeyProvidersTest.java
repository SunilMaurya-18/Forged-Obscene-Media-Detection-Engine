package com.forgeddetect.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.support.FakeVaultTransit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 24: the dev keyring and the Vault Transit (KMS) provider both sign, rotate and keep retired keys verifiable. */
class SigningKeyProvidersTest {

    private static final byte[] MESSAGE = "report digest".getBytes(StandardCharsets.UTF_8);

    @TempDir Path dir;

    @Test
    void fileKeyringSignsRotatesAndKeepsRetiredPublicKeys() throws Exception {
        FileSigningKeyProvider keys = new FileSigningKeyProvider(dir);
        SigningKeyProvider.SignatureResult first = keys.sign(MESSAGE);
        assertThat(first.keyId()).isEqualTo(keys.activeKeyId()).matches("[0-9a-f]{16}");
        assertThat(Ed25519Keys.verify(keys.publicKey(first.keyId()).orElseThrow(), MESSAGE, first.signature())).isTrue();

        SigningKeyProvider.KeyInfo rotated = keys.rotate();
        assertThat(rotated.keyId()).isNotEqualTo(first.keyId());
        assertThat(rotated.active()).isTrue();
        assertThat(keys.activeKeyId()).isEqualTo(rotated.keyId());
        // The retired private key is destroyed; its public key stays so old reports verify
        assertThat(dir.resolve("keyring/" + first.keyId() + ".key")).doesNotExist();
        assertThat(Ed25519Keys.verify(keys.publicKey(first.keyId()).orElseThrow(), MESSAGE, first.signature())).isTrue();
        assertThat(keys.keys()).extracting(SigningKeyProvider.KeyInfo::keyId).containsExactly(rotated.keyId(), first.keyId());

        SigningKeyProvider.SignatureResult second = keys.sign(MESSAGE);
        assertThat(second.keyId()).isEqualTo(rotated.keyId());

        // A second instance sharing the directory sees the same keyring and active key
        FileSigningKeyProvider other = new FileSigningKeyProvider(dir);
        assertThat(other.activeKeyId()).isEqualTo(rotated.keyId());
        assertThat(other.publicKey(first.keyId())).isPresent();
    }

    @Test
    void phase20KeyIsMigratedWithTheSameKeyId() throws Exception {
        KeyPair legacy = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(dir.resolve("report-signing-ed25519.key"), Ed25519Keys.pem("PRIVATE KEY", legacy.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("report-signing-ed25519.pub"), Ed25519Keys.pem("PUBLIC KEY", legacy.getPublic().getEncoded()));

        FileSigningKeyProvider keys = new FileSigningKeyProvider(dir);
        assertThat(keys.activeKeyId()).isEqualTo(Ed25519Keys.keyId(legacy.getPublic()));
        assertThat(dir.resolve("report-signing-ed25519.key")).doesNotExist();
    }

    @Test
    void keyIdsThatAreNotHexAreNeverLookedUpOnDisk() {
        FileSigningKeyProvider keys = new FileSigningKeyProvider(dir);
        assertThat(keys.publicKey("../../etc/passwd")).isEmpty();
        assertThat(keys.publicKey("ACTIVE")).isEmpty();
    }

    @Test
    void vaultTransitCreatesTheKeySignsAndRotates() throws Exception {
        try (FakeVaultTransit vault = new FakeVaultTransit("fmd-report-signing")) {
            VaultTransitSigningKeyProvider keys = new VaultTransitSigningKeyProvider(vault.address(), FakeVaultTransit.TOKEN,
                    "", "transit", "fmd-report-signing", Duration.ofSeconds(5), new ObjectMapper());
            assertThat(vault.latestVersion()).isEqualTo(1);
            assertThat(keys.production()).isTrue();

            SigningKeyProvider.SignatureResult first = keys.sign(MESSAGE);
            assertThat(first.keyId()).isEqualTo(keys.activeKeyId());
            assertThat(Ed25519Keys.verify(keys.publicKey(first.keyId()).orElseThrow(), MESSAGE, first.signature())).isTrue();

            SigningKeyProvider.KeyInfo rotated = keys.rotate();
            assertThat(vault.latestVersion()).isEqualTo(2);
            assertThat(rotated.keyId()).isNotEqualTo(first.keyId());
            SigningKeyProvider.SignatureResult second = keys.sign(MESSAGE);
            assertThat(second.keyId()).isEqualTo(rotated.keyId());
            assertThat(Ed25519Keys.verify(keys.publicKey(first.keyId()).orElseThrow(), MESSAGE, first.signature())).isTrue();
            assertThat(keys.keys()).hasSize(2).first().satisfies(k -> assertThat(k.active()).isTrue());
        }
    }
}

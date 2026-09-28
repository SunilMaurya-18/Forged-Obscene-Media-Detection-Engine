package com.forgeddetect.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.forgeddetect.support.FakeTsa;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 24: RFC 3161 request / response against a local TSA, plus the checks verification relies on. */
class ReportTimestampServiceTest {

    private static final byte[] SIGNATURE = "ed25519 signature bytes".getBytes(StandardCharsets.UTF_8);
    private static FakeTsa tsa;

    @TempDir Path dir;

    @BeforeAll
    static void start() throws Exception {
        tsa = new FakeTsa();
    }

    @AfterAll
    static void stop() {
        tsa.close();
    }

    @Test
    void stampsAndVerifies() {
        ReportTimestampService service = new ReportTimestampService(tsa.url(), Duration.ofSeconds(5), "");
        ReportTimestampService.Outcome out = service.stamp(SIGNATURE);
        assertThat(out.error()).isNull();
        assertThat(out.stamp().time()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(out.stamp().authority()).contains("FMD Test TSA");

        ReportTimestampService.Check check = service.verify(out.stamp().tokenBase64(), SIGNATURE);
        assertThat(check.status()).isEqualTo(ReportTimestampService.CheckStatus.VERIFIED);
        assertThat(check.trusted()).as("no trust list configured").isFalse();
    }

    @Test
    void trustedWhenTheTsaCertificateIsOnTheTrustList() throws Exception {
        Path pem = dir.resolve("tsa.pem");
        Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(tsa.certificate().getEncoded()) + "\n-----END CERTIFICATE-----\n");
        ReportTimestampService service = new ReportTimestampService(tsa.url(), Duration.ofSeconds(5), pem.toString());
        String token = service.stamp(SIGNATURE).stamp().tokenBase64();
        assertThat(service.verify(token, SIGNATURE).trusted()).isTrue();
    }

    @Test
    void aTokenForOtherDataOrGarbageFailsVerification() {
        ReportTimestampService service = new ReportTimestampService(tsa.url(), Duration.ofSeconds(5), "");
        String token = service.stamp(SIGNATURE).stamp().tokenBase64();
        assertThat(service.verify(token, "another signature".getBytes(StandardCharsets.UTF_8)).status())
                .isEqualTo(ReportTimestampService.CheckStatus.MISMATCH);
        assertThat(service.verify(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), SIGNATURE).status())
                .isEqualTo(ReportTimestampService.CheckStatus.INVALID);
    }

    @Test
    void anUnavailableTsaNeverThrows() {
        ReportTimestampService service = new ReportTimestampService(tsa.url(), Duration.ofSeconds(5), "");
        tsa.setFailing(true);
        try {
            ReportTimestampService.Outcome out = service.stamp(SIGNATURE);
            assertThat(out.stamp()).isNull();
            assertThat(out.error()).contains("503");
        } finally {
            tsa.setFailing(false);
        }
        assertThat(new ReportTimestampService("", Duration.ofSeconds(1), "").stamp(SIGNATURE).error()).isEqualTo("disabled");
    }
}

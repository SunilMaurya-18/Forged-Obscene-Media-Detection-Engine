package com.forgeddetect.signing;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 24: RFC 3161 timestamps from a third-party Time-Stamping Authority. The TSA signs
 * (SHA-256 of the report signature, its own clock), which proves the signature existed at that time
 * independently of this server's clock. Disabled while {@code report.timestamp.url} is blank.
 *
 * <p>A token is VERIFIED when its message imprint matches the signature and the TSA's signature on
 * it is valid. It is additionally {@code trusted} when the TSA certificate is, or is issued by, one
 * of the certificates in {@code report.timestamp.trusted-certs}.
 */
@Service
public class ReportTimestampService {

    private static final Logger log = LoggerFactory.getLogger(ReportTimestampService.class);

    public enum CheckStatus { VERIFIED, MISMATCH, INVALID }

    public record Stamp(String tokenBase64, Instant time, String authority) {}

    /** {@code stamp} is null when the TSA was unreachable or refused; {@code error} says why. */
    public record Outcome(Stamp stamp, String error) {}

    public record Check(CheckStatus status, Instant time, String authority, boolean trusted, String message) {}

    private final String url;
    private final Duration timeout;
    private final List<X509Certificate> anchors;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public ReportTimestampService(
            @Value("${report.timestamp.url:}") String url,
            @Value("${report.timestamp.timeout:PT10S}") Duration timeout,
            @Value("${report.timestamp.trusted-certs:}") String trustedCerts) {
        this.url = url == null ? "" : url.trim();
        this.timeout = timeout;
        this.anchors = loadAnchors(trustedCerts);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        if (enabled()) {
            log.info("RFC 3161 timestamps from {} ({} trusted TSA certificate(s))", this.url, anchors.size());
        }
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    public String url() {
        return url;
    }

    /** Never throws: a TSA outage must not block issuing a report, so failures come back in {@link Outcome#error()}. */
    public Outcome stamp(byte[] data) {
        if (!enabled()) {
            return new Outcome(null, "disabled");
        }
        try {
            TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
            gen.setCertReq(true);
            TimeStampRequest req = gen.generate(TSPAlgorithms.SHA256, Ed25519Keys.sha256(data), new BigInteger(64, random));
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/timestamp-query")
                    .header("Accept", "application/timestamp-reply")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(req.getEncoded()))
                    .build();
            HttpResponse<byte[]> res = http.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                return failed("TSA returned HTTP " + res.statusCode());
            }
            TimeStampResponse tsr = new TimeStampResponse(res.body());
            tsr.validate(req);
            TimeStampToken token = tsr.getTimeStampToken();
            if (token == null) {
                return failed("TSA refused the request: " + tsr.getStatusString());
            }
            return new Outcome(new Stamp(Base64.getEncoder().encodeToString(token.getEncoded()),
                    token.getTimeStampInfo().getGenTime().toInstant(), authority(token)), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed("interrupted");
        } catch (Exception ex) {
            return failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public Check verify(String tokenBase64, byte[] data) {
        TimeStampToken token;
        try {
            token = new TimeStampToken(new CMSSignedData(Base64.getDecoder().decode(tokenBase64)));
        } catch (Exception ex) {
            return new Check(CheckStatus.INVALID, null, null, false, "The timestamp token is not a readable RFC 3161 token.");
        }
        TimeStampTokenInfo info = token.getTimeStampInfo();
        Instant time = info.getGenTime().toInstant();
        String authority = authority(token);
        if (!NISTObjectIdentifiers.id_sha256.equals(info.getMessageImprintAlgOID())
                || !Arrays.equals(info.getMessageImprintDigest(), Ed25519Keys.sha256(data))) {
            return new Check(CheckStatus.MISMATCH, time, authority, false,
                    "The timestamp token was issued for different data than this report's signature.");
        }
        X509CertificateHolder cert = signerCert(token);
        if (cert == null) {
            return new Check(CheckStatus.INVALID, time, authority, false, "The timestamp token carries no TSA certificate.");
        }
        try {
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(cert));
        } catch (Exception ex) {
            return new Check(CheckStatus.INVALID, time, authority, false, "The TSA signature on the timestamp token is not valid.");
        }
        boolean trusted = trusted(cert);
        return new Check(CheckStatus.VERIFIED, time, authority, trusted, trusted
                ? "Timestamp verified; the TSA certificate is on this server's trust list."
                : "Timestamp verified against the certificate it carries; that TSA is not on this server's trust list.");
    }

    private boolean trusted(X509CertificateHolder cert) {
        for (X509Certificate anchor : anchors) {
            try {
                X509CertificateHolder a = new JcaX509CertificateHolder(anchor);
                if (a.equals(cert)) {
                    return true;
                }
                if (a.getSubject().equals(cert.getIssuer())
                        && cert.isSignatureValid(new JcaContentVerifierProviderBuilder().build(anchor.getPublicKey()))) {
                    return true;
                }
            } catch (Exception ignored) {
                // not this anchor
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static X509CertificateHolder signerCert(TimeStampToken token) {
        Collection<X509CertificateHolder> matches = token.getCertificates().getMatches(token.getSID());
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    private static String authority(TimeStampToken token) {
        X509CertificateHolder cert = signerCert(token);
        if (cert != null) {
            return cert.getSubject().toString();
        }
        return token.getTimeStampInfo().getTsa() != null ? token.getTimeStampInfo().getTsa().toString() : "unknown TSA";
    }

    private Outcome failed(String reason) {
        log.warn("RFC 3161 timestamp from {} failed: {}", url, reason);
        return new Outcome(null, reason.length() > 240 ? reason.substring(0, 240) : reason);
    }

    private static List<X509Certificate> loadAnchors(String path) {
        List<X509Certificate> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        try (var in = Files.newInputStream(Path.of(path))) {
            for (var c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                out.add((X509Certificate) c);
            }
        } catch (IOException | java.security.cert.CertificateException ex) {
            throw new IllegalStateException("Could not read report.timestamp.trusted-certs " + path + ": " + ex.getMessage(), ex);
        }
        return out;
    }
}

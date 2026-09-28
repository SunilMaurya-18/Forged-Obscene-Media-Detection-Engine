package com.forgeddetect.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

/** A minimal RFC 3161 Time-Stamping Authority on localhost with a throwaway self-signed certificate. */
public final class FakeTsa implements AutoCloseable {

    private final HttpServer server;
    private final X509Certificate certificate;
    private final AtomicLong serial = new AtomicLong(1);
    private volatile boolean failing;

    public FakeTsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair kp = kpg.generateKeyPair();
        X500Name name = new X500Name("CN=FMD Test TSA,O=Forged Media Detection tests");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))), name, kp.getPublic());
        builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate()));
        certificate = new JcaX509CertificateConverter().getCertificate(holder);

        TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withECDSA", kp.getPrivate(), certificate),
                new JcaDigestCalculatorProviderBuilder().build().get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                new ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"));
        tokens.addCertificates(new JcaCertStore(List.of(certificate)));
        TimeStampResponseGenerator responses = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tsa", exchange -> {
            try (exchange) {
                if (failing) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body;
                try {
                    TimeStampRequest req = new TimeStampRequest(exchange.getRequestBody().readAllBytes());
                    body = responses.generate(req, BigInteger.valueOf(serial.getAndIncrement()), new Date()).getEncoded();
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tsa";
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

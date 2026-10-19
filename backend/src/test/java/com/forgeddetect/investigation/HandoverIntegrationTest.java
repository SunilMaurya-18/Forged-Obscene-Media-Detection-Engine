package com.forgeddetect.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.signing.Ed25519Keys;
import com.forgeddetect.support.FakeTsa;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 28 DoD: an investigator without an approved, unexpired request gets 403 on every case; a
 * downloaded package verifies offline and changing any byte inside it fails; a requester cannot
 * approve their own request. Also the logged reads, the narrowed account graph and the download
 * window. Writes target/phase28 for tools/test_verify_evidence_package.py.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase28;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "app.security.extra-moderators=senior:senior:SENIOR_MODERATOR:core,senior2:senior2:SENIOR_MODERATOR:core,"
                    + "investigator:investigator:INVESTIGATOR:cyber-cell,investigator2:investigator2:INVESTIGATOR:cyber-cell,"
                    + "investigator3:investigator3:INVESTIGATOR:cyber-cell",
            "api.bootstrap-key=" + HandoverIntegrationTest.PARTNER_KEY,
            "api.rate-limits.FREE=1000",
            "prescreen.phash-max-distance=8",
            "compliance.seed-demo=false"
        })
class HandoverIntegrationTest {

    static final String PARTNER_KEY = "fmd_live_test_demo_key_0123456789";
    /** The access test's investigator; no other test gives it a request, so its case list starts empty. */
    private static final String OWN = "investigator3";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final FakeTsa TSA = startTsa();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("report.timestamp.url", TSA::url);
        registry.add("report.signing.key-dir", () -> System.getProperty("java.io.tmpdir") + "/fmd-phase28-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopTsa() {
        TSA.close();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EvidencePackageService packageService;

    @MockBean private MlClientService mlClientService;

    private final AtomicReference<String> nextPhash = new AtomicReference<>();

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.91);
            ml.setExplicitScore(0.02);
            ml.setPhash(nextPhash.get());
            ml.setHeatmap("");
            ml.setMediaType("image");
            ml.setProvenanceId(UUID.randomUUID().toString());
            ml.setContentHash("sha256:" + HexFormat.of().formatHex(Ed25519Keys.sha256(nextPhash.get().getBytes(StandardCharsets.UTF_8))));
            return ml;
        });
    }

    // ------------------------------------------------------------------ access

    @Test
    void anInvestigatorWithoutAnApprovedUnexpiredRequestGets403OnEveryCase() throws Exception {
        String caseA = open(upload(randomHash()).get("id").asLong());
        String caseB = open(upload(randomHash()).get("id").asLong());
        preserve(caseA);

        // No request at all: every case, and a case number that doesn't exist, look the same
        assertNoAccess(OWN, caseA, caseB, "INV-ZZZZ-ZZZZ", "not-a-case");
        assertThat(investigator(get("/api/investigator/cases"), OWN).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("[]");

        // Investigators can't use the moderator console or its API
        for (MockHttpServletRequestBuilder r : List.of(get("/api/investigations"), get("/api/investigations/" + caseA),
                get("/api/investigations/" + caseA + "/custody"), get("/api/legal-requests"), get("/api/queue"))) {
            investigator(r, OWN).andExpect(status().isForbidden());
        }
        investigator(post("/api/legal-requests/LRQ-AAAA-AAAA/approve").contentType(MediaType.APPLICATION_JSON).content("{}"), OWN)
                .andExpect(status().isForbidden());
        // ... and moderators can't use the investigator API
        moderator(get("/api/investigator/cases")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/investigator/cases")).andExpect(status().isUnauthorized());

        // Pending: still nothing
        String lrq = file("moderator", OWN, List.of(caseA), 30).get("requestNumber").asText();
        assertNoAccess(OWN, caseA, caseB);

        // Approved by a senior who didn't file it: the named case only, for the named investigator only
        decide(lrq, "approve", "senior", "Order checked against the court record").andExpect(status().isOk());
        JsonNode view = json(investigator(get("/api/investigator/cases/" + caseA), OWN).andExpect(status().isOk()));
        assertThat(view.get("caseNumber").asText()).isEqualTo(caseA);
        assertThat(view.at("/authorisation/requestNumber").asText()).isEqualTo(lrq);
        assertThat(view.at("/custodyVerification/status").asText()).isEqualTo("VALID");
        assertNoAccess(OWN, caseB);
        assertNoAccess("investigator2", caseA, caseB);
        JsonNode list = json(investigator(get("/api/investigator/cases"), OWN));
        assertThat(texts(list, "caseNumber")).containsExactly(caseA);

        // Expired (the clock passed expires_at): 403 again, and it says EXPIRED to moderators
        Timestamp expires = jdbc.queryForObject("select expires_at from investigation_legal_requests where request_number = ?", Timestamp.class, lrq);
        jdbc.update("update investigation_legal_requests set expires_at = ? where request_number = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), lrq);
        assertNoAccess(OWN, caseA);
        assertThat(json(investigator(get("/api/investigator/cases"), OWN))).isEmpty();
        assertThat(requestView(lrq, "senior").get("status").asText()).isEqualTo("EXPIRED");
        assertError(revoke(lrq, "senior", "Too late"), 409, "request_not_active");
        jdbc.update("update investigation_legal_requests set expires_at = ? where request_number = ?", expires, lrq);
        investigator(get("/api/investigator/cases/" + caseA), OWN).andExpect(status().isOk());

        // Revoked: 403 from then on, whoever revokes (any senior may)
        assertError(revoke(lrq, "moderator", "No"), 403, "senior_required");
        assertError(revoke(lrq, "senior2", " "), 400, "reason_required");
        revoke(lrq, "senior2", "The order was set aside on appeal").andExpect(status().isOk());
        assertNoAccess(OWN, caseA, caseB);

        // Rejected requests grant nothing
        String rejected = file("moderator", OWN, List.of(caseB), 30).get("requestNumber").asText();
        assertError(decide(rejected, "reject", "senior", ""), 400, "reason_required");
        decide(rejected, "reject", "senior", "The order names a different platform").andExpect(status().isOk());
        assertNoAccess(OWN, caseA, caseB);

        // Every step is in the custody log of the case, and the log still verifies
        JsonNode custody = custody(caseA);
        assertThat(custody.at("/verification/status").asText()).isEqualTo("VALID");
        assertThat(texts(custody.get("events"), "type")).containsSubsequence(
                "LEGAL_REQUEST_FILED", "LEGAL_REQUEST_APPROVED", "INVESTIGATOR_VIEWED", "INVESTIGATOR_VIEWED", "LEGAL_REQUEST_REVOKED");
        JsonNode viewed = events(custody, "INVESTIGATOR_VIEWED").get(0);
        assertThat(viewed.get("actor").asText()).isEqualTo(OWN);
        assertThat(viewed.get("detail").asText()).contains(lrq);
        assertThat(texts(custody(caseB).get("events"), "type")).contains("LEGAL_REQUEST_FILED", "LEGAL_REQUEST_REJECTED")
                .doesNotContain("INVESTIGATOR_VIEWED");
    }

    @Test
    void aRequesterCannotApproveTheirOwnRequest() throws Exception {
        String caseNumber = open(upload(randomHash()).get("id").asLong());
        String lrq = file("senior", "investigator", List.of(caseNumber), 14).get("requestNumber").asText();
        assertThat(lrq).matches("LRQ-[A-Z0-9]{4}-[A-Z0-9]{4}");

        JsonNode own = requestView(lrq, "senior");
        assertThat(own.get("status").asText()).isEqualTo("PENDING");
        assertThat(own.get("canDecide").asBoolean()).isFalse();
        assertThat(requestView(lrq, "senior2").get("canDecide").asBoolean()).isTrue();
        assertThat(requestView(lrq, "moderator").get("canDecide").asBoolean()).isFalse();

        assertError(decide(lrq, "approve", "senior", "Approving my own"), 403, "conflict_of_interest");
        assertError(decide(lrq, "reject", "senior", "Rejecting my own"), 403, "conflict_of_interest");
        assertError(decide(lrq, "approve", "moderator", null), 403, "senior_required");
        assertNoAccess("investigator", caseNumber);

        JsonNode approved = json(decide(lrq, "approve", "senior2", null).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("filedBy").asText()).isEqualTo("senior");
        assertThat(approved.get("decidedBy").asText()).isEqualTo("senior2");
        assertError(decide(lrq, "approve", "senior2", null), 409, "request_not_pending");
        investigator(get("/api/investigator/cases/" + caseNumber), "investigator").andExpect(status().isOk());

        // Filing is checked too
        assertError(fileRequest("moderator", Map.of("authority", "Cyber Cell", "orderReference", "X/1", "investigator", "moderator",
                "caseNumbers", List.of(caseNumber), "expiresAt", Instant.now().plus(10, ChronoUnit.DAYS).toString())), 400, "unknown_investigator");
        assertError(fileRequest("moderator", Map.of("authority", "Cyber Cell", "orderReference", "X/1", "investigator", "investigator",
                "caseNumbers", List.of(caseNumber), "expiresAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString())), 400, "invalid_expiry");
        assertError(fileRequest("moderator", Map.of("authority", "Cyber Cell", "orderReference", "X/1", "investigator", "investigator",
                "caseNumbers", List.of(caseNumber), "expiresAt", Instant.now().plus(400, ChronoUnit.DAYS).toString())), 400, "invalid_expiry");
        assertError(fileRequest("moderator", Map.of("authority", " ", "orderReference", "X/1", "investigator", "investigator",
                "caseNumbers", List.of(caseNumber), "expiresAt", Instant.now().plus(10, ChronoUnit.DAYS).toString())), 400, "invalid_request");
        assertError(fileRequest("moderator", Map.of("authority", "Cyber Cell", "orderReference", "X/1", "investigator", "investigator",
                "caseNumbers", List.of(), "expiresAt", Instant.now().plus(10, ChronoUnit.DAYS).toString())), 400, "invalid_request");
        assertError(fileRequest("moderator", Map.of("authority", "Cyber Cell", "orderReference", "X/1", "investigator", "investigator",
                "caseNumbers", List.of("INV-ZZZZ-ZZZZ"), "expiresAt", Instant.now().plus(10, ChronoUnit.DAYS).toString())), 404, "case_not_found");
    }

    // ------------------------------------------------------------------ account graph

    @Test
    void theInvestigatorViewShowsOnlyTheAuthorisedPartOfTheAccountGraph() throws Exception {
        String hashA = randomHash();
        String hashB = randomHash();
        String caseA = open(upload(hashA).get("id").asLong());
        String caseB = open(upload(hashB).get("id").asLong());
        partner(sighting(hashA, "vidshare", "vs-h1", "shared-uploader")).andExpect(status().isCreated());
        partner(sighting(hashB, "vidshare", "vs-h2", "shared-uploader")).andExpect(status().isCreated());

        JsonNode full = json(moderator(get("/api/investigations/" + caseA)));
        assertThat(texts(full.at("/accounts/0/otherCases"), "caseNumber")).containsExactly(caseB);

        String lrq = file("moderator", "investigator", List.of(caseA), 30).get("requestNumber").asText();
        decide(lrq, "approve", "senior", null).andExpect(status().isOk());
        JsonNode narrow = json(investigator(get("/api/investigator/cases/" + caseA), "investigator").andExpect(status().isOk()));
        assertThat(narrow.get("accounts")).hasSize(1);
        assertThat(narrow.at("/accounts/0/otherCases")).as("case B is not in the order").isEmpty();
        assertThat(narrow.at("/accounts/0").has("totalSightings")).isFalse();
        assertThat(narrow.has("appearances")).isFalse();
        assertThat(narrow.toString()).doesNotContain(caseB).doesNotContain("shared-uploader");

        String wider = file("moderator", "investigator", List.of(caseA, caseB), 30).get("requestNumber").asText();
        decide(wider, "approve", "senior", null).andExpect(status().isOk());
        JsonNode both = json(investigator(get("/api/investigator/cases/" + caseA), "investigator"));
        assertThat(texts(both.at("/accounts/0/otherCases"), "caseNumber")).containsExactly(caseB);
    }

    // ------------------------------------------------------------------ packages

    @Test
    void aDownloadedPackageVerifiesOfflineAndAnyChangedByteFails() throws Exception {
        String hash = randomHash();
        long id = upload(hash).get("id").asLong();
        moderator(multipart("/api/queue/" + id + "/report")).andExpect(status().isOk());
        String caseNumber = open(id);
        partner(sighting(flip(hash, 2), "pixelgram", "pg-h1", "acct-h")).andExpect(status().isCreated());
        String lrq = file("moderator", "investigator", List.of(caseNumber), 30).get("requestNumber").asText();
        decide(lrq, "approve", "senior", null).andExpect(status().isOk());

        assertError(investigator(post("/api/investigator/cases/" + caseNumber + "/packages"), "investigator"), 409, "not_preserved");
        assertError(investigator(post("/api/investigator/cases/" + caseNumber + "/packages"), "investigator2"), 403, "no_legal_authority");
        String pid = preserve(caseNumber);

        JsonNode issued = json(investigator(post("/api/investigator/cases/" + caseNumber + "/packages"), "investigator")
                .andExpect(status().isCreated()));
        String packageId = issued.get("packageId").asText();
        assertThat(packageId).matches("PKG-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(issued.get("preservationId").asText()).isEqualTo(pid);
        assertThat(issued.get("downloadable").asBoolean()).isTrue();

        // Only the investigator it was issued to can download it
        assertError(investigator(get("/api/investigator/packages/" + packageId + "/download"), "investigator2"), 404, "package_not_found");
        MvcResult download = investigator(get("/api/investigator/packages/" + packageId + "/download"), "investigator")
                .andExpect(status().isOk()).andReturn();
        byte[] zip = download.getResponse().getContentAsByteArray();
        assertThat(download.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(download.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(download.getResponse().getHeader("X-Package-SHA256")).isEqualTo(PackageFormat.sha256Hex(zip));

        // The package: every file listed and signed; no media, no raw account IDs
        Map<String, byte[]> files = PackageFormat.unzip(zip);
        assertThat(files.keySet()).containsExactly(PackageFormat.MANIFEST, PackageFormat.SIGNATURE, "README.txt", "case/report.json",
                "case/report.pdf", "case/sightings.json", "custody/log.json", "preservation/" + pid + ".manifest.txt",
                "preservation/" + pid + ".json", "keys/" + issued.get("signingKeyId").asText() + ".pem");
        assertThat(new String(files.get("case/report.pdf"), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (!f.getKey().endsWith(".pdf")) {
                assertThat(new String(f.getValue(), StandardCharsets.UTF_8)).as(f.getKey()).doesNotContain("acct-h");
            }
        }
        String manifest = new String(files.get(PackageFormat.MANIFEST), StandardCharsets.UTF_8);
        assertThat(manifest).startsWith(PackageFormat.VERSION + "\n").contains("package_id=" + packageId + "\n",
                "case_number=" + caseNumber + "\n", "legal_request=" + lrq + "\n", "investigator=investigator\n",
                "approved_by=senior\n", "preservation_id=" + pid + "\n", "file_count=8\n");
        JsonNode report = objectMapper.readTree(files.get("case/report.json"));
        assertThat(report.at("/authorisation/requestNumber").asText()).isEqualTo(lrq);
        assertThat(report.at("/package/packageId").asText()).isEqualTo(packageId);
        assertThat(report.has("appearances")).isFalse();

        // Verifies with the published key alone
        JsonNode published = json(mockMvc.perform(get("/api/verify-report/keys")));
        Function<String, Optional<PublicKey>> keys = keyId -> {
            for (JsonNode k : published.get("keys")) {
                if (k.get("keyId").asText().equals(keyId)) {
                    return Optional.of(publicKey(k.get("publicKey").asText()));
                }
            }
            return Optional.empty();
        };
        PackageVerifier.Result valid = PackageVerifier.verify(zip, keys);
        assertThat(valid.status()).as(valid.message()).isEqualTo(PackageVerifier.Status.VALID);
        assertThat(valid.checks()).extracting(PackageVerifier.Check::name)
                .containsExactly("structure", "signature", "files", "preservation", "custody");
        assertThat(PackageVerifier.verify(zip, k -> Optional.empty()).status()).isEqualTo(PackageVerifier.Status.TAMPERED);

        // ... and at /api/verify, which also checks the timestamp and that these are the bytes issued
        JsonNode online = json(mockMvc.perform(multipart("/api/verify").file(new MockMultipartFile("file", packageId + ".zip", "application/zip", zip)))
                .andExpect(status().isOk()));
        assertThat(online.get("kind").asText()).isEqualTo("evidence_package");
        assertThat(online.get("status").asText()).as(online.toString()).isEqualTo("VALID");
        assertThat(online.get("packageId").asText()).isEqualTo(packageId);
        assertThat(online.get("legalRequest").asText()).isEqualTo(lrq);
        assertThat(texts(online.get("checks"), "name")).contains("structure", "signature", "files", "preservation", "custody", "timestamp", "issued");
        assertThat(online.get("checks").findValues("ok")).allMatch(JsonNode::asBoolean);

        // Changing any byte of any file, or the signature block, fails; so does adding or removing a file
        Map<String, byte[]> tampered = tamperedCopies(files, Instant.parse(manifest.lines()
                .filter(l -> l.startsWith("issued_at=")).findFirst().orElseThrow().substring("issued_at=".length())));
        for (Map.Entry<String, byte[]> t : tampered.entrySet()) {
            PackageVerifier.Result r = PackageVerifier.verify(t.getValue(), keys);
            assertThat(r.status()).as(t.getKey() + ": " + r.message()).isNotEqualTo(PackageVerifier.Status.VALID);
            assertThat(packageService.verifyUpload(t.getValue()).get("status")).as(t.getKey()).isNotEqualTo("VALID");
        }
        // ... and so does changing any raw byte of the ZIP (sampled), against the server's record of what it issued
        int step = Math.max(1, zip.length / 400);
        for (int i = 0; i < zip.length; i += step) {
            byte[] copy = zip.clone();
            copy[i] ^= 0x01;
            assertThat(packageService.verifyUpload(copy).get("status")).as("byte " + i).isNotEqualTo("VALID");
        }

        // Every read is in the custody log, and the log still verifies
        JsonNode custody = custody(caseNumber);
        assertThat(custody.at("/verification/status").asText()).isEqualTo("VALID");
        JsonNode issuedEvent = events(custody, "PACKAGE_ISSUED").get(0);
        assertThat(issuedEvent.get("actor").asText()).isEqualTo("investigator");
        assertThat(issuedEvent.get("detail").asText()).contains(packageId, lrq, pid);
        JsonNode downloadedEvent = events(custody, "PACKAGE_DOWNLOADED").get(0);
        assertThat(downloadedEvent.get("detail").asText()).contains(packageId, PackageFormat.sha256Hex(zip));
        JsonNode listed = json(investigator(get("/api/investigator/packages"), "investigator"));
        assertThat(listed.get(0).get("downloads").asInt()).isEqualTo(1);
        assertThat(json(moderator(get("/api/investigations/" + caseNumber))).at("/custody/packages/0/packageId").asText()).isEqualTo(packageId);

        writeFixture(zip, published, tampered);

        // The download window closes; revocation closes it early
        jdbc.update("update investigation_evidence_packages set download_expires_at = ? where package_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), packageId);
        assertError(investigator(get("/api/investigator/packages/" + packageId + "/download"), "investigator"), 410, "package_expired");
        jdbc.update("update investigation_evidence_packages set download_expires_at = ? where package_id = ?",
                Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), packageId);
        revoke(lrq, "senior2", "Handover complete").andExpect(status().isOk());
        assertError(investigator(get("/api/investigator/packages/" + packageId + "/download"), "investigator"), 403, "no_legal_authority");
        assertError(investigator(post("/api/investigator/cases/" + caseNumber + "/packages"), "investigator"), 403, "no_legal_authority");
        assertThat(events(custody(caseNumber), "PACKAGE_DOWNLOADED")).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    /** Named copies that each change one thing; none may verify. */
    private Map<String, byte[]> tamperedCopies(Map<String, byte[]> files, Instant time) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String name : files.keySet()) {
            byte[] data = files.get(name).clone();
            data[data.length / 2] ^= 0x01;
            out.put("flip-" + name.replaceAll("[^A-Za-z0-9.]+", "-"), rezip(files, name, data, time));
        }
        String sig = new String(files.get(PackageFormat.SIGNATURE), StandardCharsets.UTF_8);
        for (String line : List.of("timestamp_time=", "timestamp_authority=", "signing_key_id=")) {
            String forged = sig.replaceFirst("(?m)^(" + line + ".*?)(.)$", "$1" + "0");
            if (!forged.equals(sig)) {
                out.put("signature-" + line.replace("=", ""), rezip(files, PackageFormat.SIGNATURE, forged.getBytes(StandardCharsets.UTF_8), time));
            }
        }
        out.put("signature-extra-line", rezip(files, PackageFormat.SIGNATURE, (sig + "note=added\n").getBytes(StandardCharsets.UTF_8), time));

        // A consistent forgery: edit a custody event and update the manifest to match; the signature fails
        String original = new String(files.get("custody/log.json"), StandardCharsets.UTF_8);
        String log = original.replace("Evidence package", "Evidence pakage");
        assertThat(log).isNotEqualTo(original);
        byte[] forgedLog = log.getBytes(StandardCharsets.UTF_8);
        String manifest = new String(files.get(PackageFormat.MANIFEST), StandardCharsets.UTF_8)
                .replace(PackageFormat.sha256Hex(files.get("custody/log.json")), PackageFormat.sha256Hex(forgedLog));
        Map<String, byte[]> forged = new LinkedHashMap<>(files);
        forged.put("custody/log.json", forgedLog);
        forged.put(PackageFormat.MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
        out.put("forged-manifest", PackageFormat.zip(forged, time));

        Map<String, byte[]> extra = new LinkedHashMap<>(files);
        extra.put("case/extra.txt", "planted".getBytes(StandardCharsets.UTF_8));
        out.put("added-file", PackageFormat.zip(extra, time));
        Map<String, byte[]> missing = new LinkedHashMap<>(files);
        missing.remove("case/sightings.json");
        out.put("removed-file", PackageFormat.zip(missing, time));
        return out;
    }

    private static byte[] rezip(Map<String, byte[]> files, String name, byte[] data, Instant time) {
        Map<String, byte[]> copy = new LinkedHashMap<>(files);
        copy.put(name, data);
        return PackageFormat.zip(copy, time);
    }

    private void writeFixture(byte[] zip, JsonNode published, Map<String, byte[]> tampered) throws Exception {
        Path dir = Path.of("target", "phase28");
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) {
            for (Path p : old.toList()) {
                Files.delete(p);
            }
        }
        Files.write(dir.resolve("package.zip"), zip);
        Files.writeString(dir.resolve("package.sha256"), PackageFormat.sha256Hex(zip) + "\n");
        Files.writeString(dir.resolve("keys.json"), objectMapper.writeValueAsString(published));
        String active = published.get("activeKeyId").asText();
        for (JsonNode k : published.get("keys")) {
            if (k.get("keyId").asText().equals(active)) {
                Files.writeString(dir.resolve("public-key.pem"), k.get("publicKey").asText());
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        Files.writeString(dir.resolve("other-key.pem"), Ed25519Keys.pem("PUBLIC KEY", gen.generateKeyPair().getPublic().getEncoded()));
        byte[] corrupt = zip.clone();
        corrupt[corrupt.length / 3] ^= 0x01;
        tampered = new LinkedHashMap<>(tampered);
        tampered.put("raw-byte", corrupt);
        for (Map.Entry<String, byte[]> t : tampered.entrySet()) {
            Files.write(dir.resolve("tampered-" + t.getKey() + ".zip"), t.getValue());
        }
    }

    private void assertNoAccess(String investigator, String... caseNumbers) throws Exception {
        String message = null;
        for (String n : caseNumbers) {
            for (MockHttpServletRequestBuilder r : List.of(get("/api/investigator/cases/" + n), post("/api/investigator/cases/" + n + "/packages"))) {
                JsonNode body = json(investigator(r, investigator).andExpect(status().isForbidden()));
                assertThat(body.get("error").asText()).isEqualTo("no_legal_authority");
                String generic = body.get("message").asText().replace(n, "#");
                assertThat(message == null || message.equals(generic)).as("same refusal for " + n).isTrue();
                message = generic;
            }
        }
    }

    private JsonNode file(String actor, String investigator, List<String> caseNumbers, int days) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authority", "Cyber Crime Cell, Test City Police (fictional)");
        m.put("orderReference", "TEST/CCC/" + RANDOM.nextInt(10_000));
        m.put("investigator", investigator);
        m.put("caseNumbers", caseNumbers);
        m.put("expiresAt", Instant.now().plus(days, ChronoUnit.DAYS).toString());
        m.put("note", "Synthetic order for the test");
        JsonNode r = json(fileRequest(actor, m).andExpect(status().isCreated()));
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        return r;
    }

    private ResultActions fileRequest(String actor, Map<String, Object> body) throws Exception {
        return as(post("/api/legal-requests").contentType(MediaType.APPLICATION_JSON).content(body(body)), actor);
    }

    private ResultActions decide(String lrq, String action, String actor, String note) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("note", note);
        return as(post("/api/legal-requests/" + lrq + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body.toString()), actor);
    }

    private ResultActions revoke(String lrq, String actor, String reason) throws Exception {
        return decide(lrq, "revoke", actor, reason);
    }

    private JsonNode requestView(String lrq, String viewer) throws Exception {
        for (JsonNode r : json(as(get("/api/legal-requests"), viewer).andExpect(status().isOk()))) {
            if (r.get("requestNumber").asText().equals(lrq)) {
                return r;
            }
        }
        throw new AssertionError(lrq + " not listed");
    }

    private String preserve(String caseNumber) throws Exception {
        return json(moderator(post("/api/investigations/" + caseNumber + "/preservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", "Preserved for the handover")))).andExpect(status().isCreated())).get("preservationId").asText();
    }

    private JsonNode custody(String caseNumber) throws Exception {
        return json(moderator(get("/api/investigations/" + caseNumber + "/custody")).andExpect(status().isOk()));
    }

    private static List<JsonNode> events(JsonNode custody, String type) {
        List<JsonNode> out = new ArrayList<>();
        custody.get("events").forEach(e -> {
            if (e.get("type").asText().equals(type)) {
                out.add(e);
            }
        });
        return out;
    }

    private JsonNode upload(String phash) throws Exception {
        nextPhash.set(phash);
        MockMultipartFile file = new MockMultipartFile("file", "synthetic.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
        return json(mockMvc.perform(multipart("/api/analyze").file(file)).andExpect(status().isOk()));
    }

    private String open(long analysisId) throws Exception {
        return json(moderator(post("/api/investigations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", analysisId)))).andExpect(status().isCreated())).get("caseNumber").asText();
    }

    private MockHttpServletRequestBuilder sighting(String phash, String platform, String postRef, String accountId) throws Exception {
        return post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phash", phash, "platform", platform, "postRef", postRef, "accountId", accountId)));
    }

    private ResultActions partner(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-API-Key", PARTNER_KEY));
    }

    private ResultActions moderator(MockHttpServletRequestBuilder request) throws Exception {
        return as(request, "moderator");
    }

    private ResultActions investigator(MockHttpServletRequestBuilder request, String username) throws Exception {
        return as(request, username);
    }

    private ResultActions as(MockHttpServletRequestBuilder request, String username) throws Exception {
        return mockMvc.perform(request.with(httpBasic(username, username)));
    }

    private void assertError(ResultActions actions, int status, String code) throws Exception {
        JsonNode body = json(actions.andExpect(status().is(status)));
        assertThat(body.get("error").asText()).as(body.toString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Ed25519Keys.unpem(pem)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private static String randomHash() {
        return String.format("%016x", RANDOM.nextLong());
    }

    private static String flip(String hash, int bits) {
        return String.format("%016x", Long.parseUnsignedLong(hash, 16) ^ ((1L << bits) - 1));
    }

    private static FakeTsa startTsa() {
        try {
            return new FakeTsa();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

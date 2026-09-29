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
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.signing.Ed25519Keys;
import com.forgeddetect.support.FakeTsa;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 27 DoD: editing or deleting any custody event is caught by verification; a preserved
 * manifest verifies VALID, and TAMPERED once anything it covers changes; preservation writes no
 * media bytes. Also legal holds as custody events, sealing of pre-Phase-27 logs and access control.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase27;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "api.demo-key=" + CustodyIntegrationTest.PARTNER_KEY,
            "api.rate-limits.FREE=1000",
            "prescreen.phash-max-distance=8",
            "compliance.seed-demo=false"
        })
class CustodyIntegrationTest {

    static final String PARTNER_KEY = "fmd_live_test_demo_key_0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final FakeTsa TSA = startTsa();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("report.timestamp.url", TSA::url);
        registry.add("report.signing.key-dir", () -> System.getProperty("java.io.tmpdir") + "/fmd-phase27-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopTsa() {
        TSA.close();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CustodyLog custodyLog;

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

    // ------------------------------------------------------------------ custody chain

    @Test
    void editingOrDeletingAnyCustodyEventIsDetected() throws Exception {
        String hash = randomHash();
        String caseNumber = open(upload(hash).get("id").asLong());
        note(caseNumber, "First look");
        partner(sighting(hash, "pixelgram", "pg-c1")).andExpect(status().isCreated());
        note(caseNumber, "Second look");

        JsonNode custody = custody(caseNumber);
        assertThat(custody.at("/verification/status").asText()).isEqualTo("VALID");
        JsonNode events = custody.get("events");
        assertThat(events).hasSize(5);
        assertThat(texts(events, "type")).containsExactly("OPENED", "ITEM_ADDED", "NOTE_ADDED", "SIGHTING_LINKED", "NOTE_ADDED");
        String prev = CustodyChain.GENESIS;
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).get("seq").asInt()).isEqualTo(i + 1);
            assertThat(events.get(i).get("prevHash").asText()).isEqualTo(prev);
            assertThat(events.get(i).get("hash").asText()).matches("[0-9a-f]{64}");
            prev = events.get(i).get("hash").asText();
        }
        JsonNode detail = json(moderator(get("/api/investigations/" + caseNumber)));
        assertThat(detail.at("/custody/length").asInt()).isEqualTo(5);
        assertThat(detail.at("/custody/head").asText()).isEqualTo(prev);
        long caseId = caseId(caseNumber);

        // Edit any stored field of an event: named by position
        for (String column : List.of("detail", "actor", "occurred_at", "type", "analysis_id")) {
            Object original = jdbc.queryForObject("select " + column + " from investigation_events where investigation_id = ? and seq = 2", Object.class, caseId);
            Object forged = switch (column) {
                case "occurred_at" -> Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
                case "type" -> "NOTE_ADDED";
                case "analysis_id" -> 999_999L;
                default -> "someone else";
            };
            jdbc.update("update investigation_events set " + column + " = ? where investigation_id = ? and seq = 2", forged, caseId);
            JsonNode broken = custody(caseNumber).get("verification");
            assertThat(broken.get("status").asText()).as(column).isEqualTo("BROKEN");
            assertThat(broken.get("brokenAt").asInt()).as(column).isEqualTo(2);
            assertThat(broken.get("message").asText()).contains("changed after it was recorded");
            jdbc.update("update investigation_events set " + column + " = ? where investigation_id = ? and seq = 2", original, caseId);
            assertThat(custody(caseNumber).at("/verification/status").asText()).as(column + " restored").isEqualTo("VALID");
        }

        // Re-hashing the edited event doesn't help: the next one no longer points to it
        jdbc.update("update investigation_events set hash = ? where investigation_id = ? and seq = 3", "ab".repeat(32), caseId);
        assertThat(custody(caseNumber).at("/verification/brokenAt").asInt()).isEqualTo(3);
        jdbc.update("update investigation_events set hash = ? where investigation_id = ? and seq = 3", events.get(2).get("hash").asText(), caseId);

        // Delete one from the middle, then the newest
        jdbc.update("delete from investigation_events where investigation_id = ? and seq = 3", caseId);
        JsonNode gap = custody(caseNumber).get("verification");
        assertThat(gap.get("status").asText()).isEqualTo("BROKEN");
        assertThat(gap.get("brokenAt").asInt()).isEqualTo(3);
        assertThat(gap.get("message").asText()).contains("Event 3 is missing");

        String other = open(upload(randomHash()).get("id").asLong());
        note(other, "Will be deleted");
        jdbc.update("delete from investigation_events where investigation_id = ? and seq = 3", caseId(other));
        JsonNode tail = custody(other).get("verification");
        assertThat(tail.get("status").asText()).isEqualTo("BROKEN");
        assertThat(tail.get("message").asText()).contains("newest event was deleted");

        // A row slipped in by hand has no place in the chain
        String third = open(upload(randomHash()).get("id").asLong());
        jdbc.update("insert into investigation_events (investigation_id, occurred_at, actor, type, detail) values (?, ?, 'intruder', 'NOTE_ADDED', 'backdated')",
                caseId(third), Timestamp.from(Instant.now()));
        assertThat(custody(third).at("/verification/status").asText()).isEqualTo("BROKEN");
    }

    @Test
    void casesFromBeforeTheChainAreSealedOnceAndOnlyOnce() throws Exception {
        String caseNumber = open(upload(randomHash()).get("id").asLong());
        long caseId = caseId(caseNumber);
        jdbc.update("update investigation_events set seq = null, prev_hash = null, hash = null where investigation_id = ?", caseId);
        jdbc.update("update investigations set custody_length = null, custody_head = null where id = ?", caseId);

        custodyLog.sealLegacyCases();
        JsonNode sealed = custody(caseNumber);
        assertThat(sealed.at("/verification/status").asText()).isEqualTo("VALID");
        assertThat(sealed.at("/verification/length").asInt()).isEqualTo(2);

        // An unsealed event on a sealed case is tampering, not a legacy log: startup must not absorb it
        jdbc.update("insert into investigation_events (investigation_id, occurred_at, actor, type, detail) values (?, ?, 'intruder', 'NOTE_ADDED', 'late')",
                caseId, Timestamp.from(Instant.now()));
        custodyLog.sealLegacyCases();
        assertThat(custody(caseNumber).at("/verification/status").asText()).isEqualTo("BROKEN");
    }

    // ------------------------------------------------------------------ preservation

    @Test
    void aPreservedManifestVerifiesAndCatchesAnyChangeItCovers() throws Exception {
        String hash = randomHash();
        long id = upload(hash).get("id").asLong();
        String reportId = signReport(id);
        String caseNumber = open(id);
        partner(sighting(flip(hash, 2), "pixelgram", "pg-p1").content(body(Map.of(
                "phash", flip(hash, 2), "platform", "pixelgram", "postRef", "pg-p1", "accountId", "acct-p")))).andExpect(status().isCreated());

        JsonNode preserved = json(moderator(post("/api/investigations/" + caseNumber + "/preservations")
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("reason", "Before the hold request"))))
                .andExpect(status().isCreated()));
        String pid = preserved.get("preservationId").asText();
        assertThat(pid).matches("PRS-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(preserved.get("timestampStatus").asText()).isEqualTo("STAMPED");
        assertThat(preserved.get("analyses").asInt()).isEqualTo(1);
        assertThat(preserved.get("sightings").asInt()).isEqualTo(1);
        assertThat(preserved.get("custodyLength").asInt()).isEqualTo(3);
        String manifest = preserved.get("manifest").asText();
        assertThat(manifest).startsWith(PreservationService.VERSION + "\n")
                .contains("preservation_id=" + pid + "\n", "case_number=" + caseNumber + "\n", "custody_length=3\n",
                        "analysis.1.id=" + id + "\n", "analysis.1.phash=" + hash + "\n", "analysis.1.report_id=" + reportId + "\n",
                        "analysis.1.content_sha256=sha256:", "analysis.1.forgery_score=0.910000\n",
                        "sighting.1.platform=pixelgram\n", "sighting.1.post_ref=pg-p1\n", "sighting.1.via=PHASH\n", "sighting.1.distance=2\n")
                .doesNotContain("acct-p");
        assertThat(manifest).containsPattern("analysis\\.1\\.report_sha256=[0-9a-f]{64}\n");

        // Checkable without this server: SHA-256 of the manifest, Ed25519 with the published key
        byte[] digest = Ed25519Keys.sha256(manifest.getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(preserved.get("manifestSha256").asText());
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Ed25519Keys.unpem(preserved.get("publicKeyPem").asText())));
        assertThat(Ed25519Keys.verify(key, digest, Base64.getDecoder().decode(preserved.get("signature").asText()))).isTrue();

        JsonNode valid = verify(caseNumber, pid);
        assertThat(valid.get("status").asText()).as(valid.toString()).isEqualTo("VALID");
        assertThat(valid.at("/timestamp/status").asText()).isEqualTo("VERIFIED");

        // The preservation itself is a custody event, and the chain stays valid
        JsonNode custody = custody(caseNumber);
        assertThat(custody.at("/verification/status").asText()).isEqualTo("VALID");
        JsonNode last = custody.get("events").get(custody.get("events").size() - 1);
        assertThat(last.get("type").asText()).isEqualTo("PRESERVED");
        assertThat(last.get("detail").asText()).contains(pid, preserved.get("manifestSha256").asText());

        // Later additions to the case don't disturb an earlier preservation
        partner(sighting(hash, "chirp", "ch-after")).andExpect(status().isCreated());
        note(caseNumber, "After preservation");
        assertThat(verify(caseNumber, pid).get("status").asText()).isEqualTo("VALID");

        long caseId = caseId(caseNumber);
        long sightingId = jdbc.queryForObject("select id from sightings where post_ref = 'pg-p1'", Long.class);
        assertTamperedThenRestored(caseNumber, pid, "analysis.1.forgery_score",
                "update analysis_results set forgery_score = ? where id = " + id, 0.12, "select forgery_score from analysis_results where id = " + id);
        assertTamperedThenRestored(caseNumber, pid, "analysis.1.content_sha256",
                "update analysis_results set content_hash = ? where id = " + id, "sha256:" + "00".repeat(32), "select content_hash from analysis_results where id = " + id);
        assertTamperedThenRestored(caseNumber, pid, "analysis.1.report_sha256",
                "update analysis_results set report_content_hash = ? where id = " + id, "ff".repeat(32), "select report_content_hash from analysis_results where id = " + id);
        assertTamperedThenRestored(caseNumber, pid, "sighting.1.post_ref",
                "update sightings set post_ref = ? where id = " + sightingId, "pg-other", "select post_ref from sightings where id = " + sightingId);
        assertTamperedThenRestored(caseNumber, pid, "sighting.1.distance",
                "update sighting_links set distance = ? where sighting_id = " + sightingId, 0, "select distance from sighting_links where sighting_id = " + sightingId);
        assertTamperedThenRestored(caseNumber, pid, "custody_head",
                "update investigation_events set detail = ? where investigation_id = " + caseId + " and seq = 1", "Opened quietly",
                "select detail from investigation_events where investigation_id = " + caseId + " and seq = 1");
        assertTamperedThenRestored(caseNumber, pid, "preserved_by",
                "update investigation_preservations set preserved_by = ? where preservation_id = '" + pid + "'", "someone-else",
                "select preserved_by from investigation_preservations where preservation_id = '" + pid + "'");
        assertTamperedThenRestored(caseNumber, pid, "signature",
                "update investigation_preservations set manifest = ? where preservation_id = '" + pid + "'", manifest.replace("0.910000", "0.110000"),
                "select manifest from investigation_preservations where preservation_id = '" + pid + "'");

        // Deleting a covered record is a change too
        jdbc.update("delete from sighting_links where sighting_id = ?", sightingId);
        JsonNode gone = verify(caseNumber, pid);
        assertThat(gone.get("status").asText()).isEqualTo("TAMPERED");
        assertThat(texts(gone.get("changedFields"))).contains("sighting.1.via", "sighting.1.matched_analysis_id");
    }

    @Test
    void preservationWritesNoMediaBytes() throws Exception {
        byte[] media = new byte[4096];
        RANDOM.nextBytes(media);
        media[0] = (byte) 0xFF;
        media[1] = (byte) 0xD8;
        String hash = randomHash();
        nextPhash.set(hash);
        long id = json(mockMvc.perform(multipart("/api/analyze").file(new MockMultipartFile("file", "synthetic.jpg", "image/jpeg", media)))
                .andExpect(status().isOk())).get("id").asLong();
        String caseNumber = open(id);
        partner(sighting(hash, "vidshare", "vs-n1")).andExpect(status().isCreated());
        JsonNode preserved = json(moderator(post("/api/investigations/" + caseNumber + "/preservations")).andExpect(status().isCreated()));

        // Only known key=value fields, and small: a manifest of commitments, not a container
        String manifest = preserved.get("manifest").asText();
        Pattern allowed = Pattern.compile("^(preservation_id|case_(number|title|opened_at|opened_by)|preserved_(at|by)|reason|custody_(length|head)"
                + "|analysis_count|sighting_count|analysis\\.\\d+\\.[a-z0-9_]+|sighting\\.\\d+\\.[a-z0-9_]+)=.*$");
        manifest.lines().skip(1).forEach(line -> assertThat(line).matches(allowed));
        assertThat(manifest.length()).isLessThan(media.length);

        // Nothing in the whole database carries the upload's bytes, in hex or base64
        String dump = String.join("\n", jdbc.queryForList("SCRIPT", String.class));
        String hex = HexFormat.of().formatHex(media, 64, 128);
        String b64 = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(media, 63, 111));
        assertThat(dump).containsIgnoringCase(caseNumber).doesNotContainIgnoringCase(hex).doesNotContain(b64);
    }

    @Test
    void aBrokenLogCannotBePreservedAndUnknownPreservationsAre404() throws Exception {
        String caseNumber = open(upload(randomHash()).get("id").asLong());
        jdbc.update("update investigation_events set actor = 'rewritten' where investigation_id = ? and seq = 1", caseId(caseNumber));
        assertError(moderator(post("/api/investigations/" + caseNumber + "/preservations")), 409, "custody_broken");
        assertError(moderator(get("/api/investigations/" + caseNumber + "/preservations/PRS-2345-6789/verify")), 404, "preservation_not_found");
    }

    // ------------------------------------------------------------------ legal holds

    @Test
    void legalHoldsAreRecordedAsCustodyEvents() throws Exception {
        String hash = randomHash();
        String caseNumber = open(upload(hash).get("id").asLong());
        partner(sighting(hash, "pixelgram", "pg-h2")).andExpect(status().isCreated());
        partner(sighting(flip(hash, 1), "pixelgram", "pg-h1")).andExpect(status().isCreated());
        partner(sighting(hash, "chirp", "ch-h1")).andExpect(status().isCreated());

        JsonNode requested = json(hold(caseNumber, Map.of("platform", "PixelGram", "note", "Police request 42")).andExpect(status().isCreated()));
        JsonNode holds = requested.at("/custody/legalHolds");
        assertThat(holds).hasSize(1);
        JsonNode h = holds.get(0);
        String number = h.get("holdNumber").asText();
        assertThat(number).matches("HLD-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(h.get("platform").asText()).isEqualTo("pixelgram");
        assertThat(texts(h.get("postRefs"))).containsExactly("pg-h1", "pg-h2");
        assertThat(h.get("status").asText()).isEqualTo("REQUESTED");

        assertError(hold(caseNumber, Map.of("platform", "pixelgram", "postRefs", List.of("pg-h1"))), 409, "already_held");
        assertError(hold(caseNumber, Map.of("platform", "chirp", "postRefs", List.of("ch-unknown"))), 400, "not_in_case");
        assertError(hold(caseNumber, Map.of("platform", "vidshare")), 400, "no_sightings");
        assertError(hold(caseNumber, Map.of("platform", "chirp", "postRefs", List.of("https://chirp.example/p/1"))), 400, "invalid_hold");

        String base = "/api/investigations/" + caseNumber + "/legal-holds/" + number;
        JsonNode acked = json(moderator(post(base + "/acknowledge").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("partnerReference", "PG-LE-7781")))).andExpect(status().isOk()));
        assertThat(acked.at("/custody/legalHolds/0/status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(acked.at("/custody/legalHolds/0/partnerReference").asText()).isEqualTo("PG-LE-7781");
        assertError(moderator(post(base + "/acknowledge")), 409, "hold_not_pending");

        // Closed cases still take custody actions: the hold outlives the case
        moderator(post("/api/investigations/" + caseNumber + "/close")).andExpect(status().isOk());
        JsonNode released = json(moderator(post(base + "/release").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", "Proceedings concluded")))).andExpect(status().isOk()));
        assertThat(released.at("/custody/legalHolds/0/status").asText()).isEqualTo("RELEASED");
        assertError(moderator(post(base + "/release")), 409, "hold_released");
        moderator(post("/api/investigations/" + caseNumber + "/preservations")).andExpect(status().isCreated());

        JsonNode custody = custody(caseNumber);
        assertThat(custody.at("/verification/status").asText()).isEqualTo("VALID");
        List<String> types = texts(custody.get("events"), "type");
        assertThat(types).containsSubsequence("LEGAL_HOLD_REQUESTED", "LEGAL_HOLD_ACKNOWLEDGED", "CLOSED", "LEGAL_HOLD_RELEASED", "PRESERVED");
        String requestDetail = custody.get("events").get(types.indexOf("LEGAL_HOLD_REQUESTED")).get("detail").asText();
        assertThat(requestDetail).contains(number, "pixelgram", "pg-h1, pg-h2", LegalHoldService.postsHash(List.of("pg-h1", "pg-h2")));
        assertThat(json(moderator(get("/api/investigations/" + caseNumber))).at("/custody/legalHolds/0/status").asText()).isEqualTo("RELEASED");
    }

    @Test
    void custodyIsModeratorOnly() throws Exception {
        String caseNumber = open(upload(randomHash()).get("id").asLong());
        for (MockHttpServletRequestBuilder r : List.of(
                get("/api/investigations/" + caseNumber + "/custody"),
                get("/api/investigations/" + caseNumber + "/preservations"),
                post("/api/investigations/" + caseNumber + "/preservations"),
                post("/api/investigations/" + caseNumber + "/legal-holds").contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"x\"}"))) {
            mockMvc.perform(r).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/investigations/" + caseNumber + "/custody").header("X-API-Key", PARTNER_KEY)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private void assertTamperedThenRestored(String caseNumber, String pid, String field, String update, Object forged, String select) throws Exception {
        Object original = jdbc.queryForObject(select, Object.class);
        jdbc.update(update, forged);
        JsonNode v = verify(caseNumber, pid);
        assertThat(v.get("status").asText()).as(field).isEqualTo("TAMPERED");
        assertThat(texts(v.get("changedFields"))).as(field).contains(field);
        jdbc.update(update, original);
        assertThat(verify(caseNumber, pid).get("status").asText()).as(field + " restored").isEqualTo("VALID");
    }

    private JsonNode verify(String caseNumber, String pid) throws Exception {
        return json(moderator(get("/api/investigations/" + caseNumber + "/preservations/" + pid + "/verify")).andExpect(status().isOk()));
    }

    private JsonNode custody(String caseNumber) throws Exception {
        return json(moderator(get("/api/investigations/" + caseNumber + "/custody")).andExpect(status().isOk()));
    }

    private ResultActions hold(String caseNumber, Map<String, Object> request) throws Exception {
        return moderator(post("/api/investigations/" + caseNumber + "/legal-holds").contentType(MediaType.APPLICATION_JSON).content(body(request)));
    }

    private void note(String caseNumber, String text) throws Exception {
        moderator(post("/api/investigations/" + caseNumber + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("text", text)))).andExpect(status().isOk());
    }

    private long caseId(String caseNumber) {
        return jdbc.queryForObject("select id from investigations where case_number = ?", Long.class, caseNumber);
    }

    private String signReport(long id) throws Exception {
        moderator(multipart("/api/queue/" + id + "/report")).andExpect(status().isOk());
        return jdbc.queryForObject("select report_id from analysis_results where id = ?", String.class, id);
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

    private MockHttpServletRequestBuilder sighting(String phash, String platform, String postRef) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phash", phash);
        m.put("platform", platform);
        m.put("postRef", postRef);
        return post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON).content(body(m));
    }

    private ResultActions partner(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-API-Key", PARTNER_KEY));
    }

    private ResultActions moderator(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("moderator", "moderator")));
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

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
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

package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.Appeal;
import com.forgeddetect.model.KnownHashSource;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AppealRepository;
import com.forgeddetect.repository.KnownHashRepository;
import com.forgeddetect.service.AppealService;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 23 DoD (backend): video routed once on the frame aggregate with a per-frame timeline; C2PA
 * details surfaced; appeals with their own SLA, blocked for the original moderator, outcome in the
 * audit trail; senior escalation and second review; new jurisdictions; transparency aggregates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase23;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "app.security.extra-moderators=reviewer:reviewer:MODERATOR:core,senior:senior:SENIOR_MODERATOR:core,"
                    + "senior2:senior2:SENIOR_MODERATOR:core,apac:apac:MODERATOR:apac",
            "transparency.min-cell=1",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false",
            "compliance.default-jurisdiction=IN",
            "compliance.generic-appeal-hours=72",
            "decision.forgery-review=0.5",
            "decision.forgery-auto-flag=0.85",
            "decision.explicit-review=0.5",
            "decision.explicit-auto-flag=0.85",
            "video.fps=2.0",
            "video.top-k=3",
            "video.forgery-aggregate=TOPK_MEAN",
            "video.explicit-aggregate=MAX",
            "report.signing.key-dir=${java.io.tmpdir}/fmd-phase23-keys"
        })
class TrustSafetyIntegrationTest {

    /** Summary shape produced by ml-service content_credentials.summarize for the synthetic sample. */
    private static final String C2PA = """
            {"present":true,"status":"VALID_UNTRUSTED","validation_state":"Valid","failures":[],
             "signer":{"common_name":"SyntheticStudio Test Signer","issuer":"SyntheticStudio Test CA","algorithm":"Es256",
                       "signed_at":"2026-09-01T10:00:00+00:00","trusted":false},
             "claim_generator":"SyntheticStudio/2.1","title":"c2pa-sample.jpg","format":"image/jpeg",
             "actions":[{"action":"c2pa.created","software_agent":"SyntheticStudio 2.1",
                         "digital_source_type":"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"},
                        {"action":"c2pa.color_adjustments","software_agent":"SyntheticStudio 2.1"}],
             "ai_generated":true,"ingredients":[],"manifest_count":1}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppealRepository appealRepository;
    @Autowired private AppealService appealService;
    @Autowired private KnownHashRepository knownHashes;

    @MockBean private MlClientService mlClientService;

    private double nextForgery;
    private String nextC2pa;

    @BeforeEach
    void stubMl() {
        nextForgery = 0.95;
        nextC2pa = null;
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(nextForgery);
            ml.setExplicitScore(0.05);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setMediaType("image");
            ml.setC2pa(nextC2pa != null ? objectMapper.readTree(nextC2pa) : null);
            return ml;
        });
    }

    // ------------------------------------------------------------------ video

    @Test
    void videoIsRoutedOnceOnTheAggregateWithAPerFrameTimeline() throws Exception {
        // One spiky frame (0.97) must not auto-flag a clip whose top-3 mean is 0.71: REVIEW, not AUTO_FLAG
        double[] forgery = {0.10, 0.20, 0.55, 0.61, 0.97, 0.40};
        double[] explicit = {0.01, 0.02, 0.03, 0.02, 0.04, 0.01};
        when(mlClientService.inferVideo(any(), anyString(), any(), anyDouble(), anyInt()))
                .thenReturn(videoResponse(forgery, explicit, 3));

        JsonNode body = json(mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'})))
                .andExpect(status().isOk()));

        assertThat(body.get("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(body.get("decision").asText()).isEqualTo("REVIEW");
        assertThat(body.get("forgeryScore").asDouble()).isCloseTo((0.97 + 0.61 + 0.55) / 3, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(body.get("explicitScore").asDouble()).isEqualTo(0.04);
        JsonNode video = body.get("video");
        assertThat(video.get("frames")).hasSize(6);
        assertThat(video.get("frames").get(4).get(1).asDouble()).isEqualTo(0.97);
        assertThat(video.get("peak").get("index").asInt()).isEqualTo(4);
        assertThat(video.get("routing").get("forgery").asText()).isEqualTo("TOPK_MEAN");
        assertThat(video.get("sampled").asInt()).isEqualTo(6);
        assertThat(body.get("labelledImage").isNull()).isTrue();
        verify(mlClientService, never()).infer(any(), any(), any());

        long id = body.get("id").asLong();
        String text = reportText(id);
        assertThat(text).contains("Video(scoredframebyframe;noframeretained)", "Frame-scorestrip", "meanoftop-3frames");
    }

    private MlInferResponse videoResponse(double[] forgery, double[] explicit, int k) throws Exception {
        StringBuilder tl = new StringBuilder("[");
        for (int i = 0; i < forgery.length; i++) {
            tl.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT,
                    "{\"index\":%d,\"t\":%.1f,\"forgery_score\":%.2f,\"explicit_score\":%.2f}", i, i * 0.5, forgery[i], explicit[i]));
        }
        tl.append(']');
        double[] sorted = forgery.clone();
        java.util.Arrays.sort(sorted);
        double topk = (sorted[sorted.length - 1] + sorted[sorted.length - 2] + sorted[sorted.length - 3]) / 3;
        double explicitMax = java.util.Arrays.stream(explicit).max().orElse(0);
        MlInferResponse ml = new MlInferResponse();
        ml.setMediaType("video");
        ml.setForgeryScore(topk);
        ml.setExplicitScore(explicitMax);
        ml.setPhash("0123456789abcdef");
        ml.setHeatmap("");
        ml.setContentHash("sha256:" + "cd".repeat(32));
        ml.setTimeline(objectMapper.readTree(tl.toString()));
        ml.setAggregate(objectMapper.readTree(String.format(java.util.Locale.ROOT,
                "{\"forgery\":{\"max\":%.2f,\"topk_mean\":%.6f,\"k\":%d},\"explicit\":{\"max\":%.2f,\"topk_mean\":0.03,\"k\":%d},\"k\":%d}",
                sorted[sorted.length - 1], topk, k, explicitMax, k, k)));
        ml.setFrames(objectMapper.readTree("{\"sampled\":6,\"fps\":2.0,\"truncated\":false,\"max_frames\":64,"
                + "\"duration_seconds\":3.0,\"width\":64,\"height\":48,\"codec\":\"mpeg4\",\"source_fps\":10.0}"));
        ml.setPeak(objectMapper.readTree("{\"index\":4,\"t\":2.0}"));
        return ml;
    }

    // ------------------------------------------------------------------ C2PA

    @Test
    void c2paManifestDetailsAppearInTheAnalysisAndTheReport() throws Exception {
        nextForgery = 0.2;
        nextC2pa = C2PA;
        JsonNode body = analyze();
        JsonNode cc = body.get("contentCredentials");
        assertThat(cc.get("status").asText()).isEqualTo("VALID_UNTRUSTED");
        assertThat(cc.get("signer").get("common_name").asText()).isEqualTo("SyntheticStudio Test Signer");
        assertThat(cc.get("claim_generator").asText()).isEqualTo("SyntheticStudio/2.1");
        assertThat(cc.get("actions").get(0).get("action").asText()).isEqualTo("c2pa.created");
        assertThat(cc.get("ai_generated").asBoolean()).isTrue();
        assertThat(body.get("mediaType").asText()).isEqualTo("IMAGE");

        String text = reportText(body.get("id").asLong());
        assertThat(text).contains("5.Contentcredentials(C2PA)", "SyntheticStudioTestSigner", "SyntheticStudio/2.1",
                "created(SyntheticStudio2.1)", "VALID(untrustedsigner)");

        nextC2pa = null;
        assertThat(analyze().get("contentCredentials").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ appeals

    @Test
    void appealIsFiledBlockedForTheOriginalModeratorAndRecordedInTheTrail() throws Exception {
        JsonNode created = analyze();
        long id = created.get("id").asLong();
        String pid = created.get("provenanceId").asText();
        JsonNode notice = created.get("notice");
        String number = notice.get("noticeNumber").asText();
        String pin = notice.get("pin").asText();
        assertThat(number).matches("^UPL-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$");
        assertThat(pin).matches("^\\d{6}$");
        assertThat(notice.get("appealable").asBoolean()).isTrue();

        // "moderator" confirms the takedown, so they own the contested decision
        decide(id, "AUTO_FLAG", "moderator").andExpect(status().isOk()).andExpect(jsonPath("$.decidedBy").value("moderator"));

        // Statement of reasons for the uploader: grounds, how it was decided, and redress (no moderator names)
        JsonNode sor = json(mockMvc.perform(get("/api/appeals/" + number + "/status").header("X-Case-Pin", pin))
                .andExpect(status().isOk())).get("statementOfReasons");
        assertThat(sor.get("grounds").toString()).isEqualTo("[\"MANIPULATED_MEDIA\"]");
        assertThat(sor.get("basis").asText()).isEqualTo("PLATFORM_POLICY");
        assertThat(sor.get("detectedAutomatically").asBoolean()).isTrue();
        assertThat(sor.get("decidedAutomatically").asBoolean()).isFalse();
        assertThat(sor.get("redress").toString()).contains("INTERNAL_APPEAL", "OUT_OF_COURT_SETTLEMENT", "JUDICIAL_REDRESS");
        assertThat(sor.toString()).doesNotContain("moderator");

        fileAppeal(number, "000000", "wrong pin").andExpect(status().isNotFound());
        Instant before = Instant.now();
        JsonNode appeal = json(fileAppeal(number.toLowerCase().replace("-", " "), pin,
                "This is my own synthetic test render and I hold the rights.").andExpect(status().isCreated()));
        assertThat(appeal.get("status").asText()).isEqualTo("OPEN");
        assertThat(appeal.get("caseNumber").asText()).isEqualTo(number);
        assertThat(appeal.get("rule").asText()).isEqualTo("India IT Rules 2026 (pending legal review)");
        Duration window = Duration.between(before, Instant.parse(appeal.get("decisionDueBy").asText()));
        assertThat(window).isBetween(Duration.ofHours(72).minusMinutes(1), Duration.ofHours(72).plusMinutes(1));
        fileAppeal(number, pin, "again").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("already_appealed"));

        // Queue decisions are frozen while the appeal is open
        decide(id, "ALLOW", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("appeal_open"));

        JsonNode queue = json(mockMvc.perform(get("/api/appeals").with(httpBasic("reviewer", "reviewer"))).andExpect(status().isOk()));
        JsonNode row = find(queue, "caseNumber", number);
        long appealId = row.get("id").asLong();
        assertThat(row.get("contestedBy").asText()).isEqualTo("moderator");
        assertThat(row.get("conflicted").toString()).contains("moderator");
        assertThat(row.get("canDecide").asBoolean()).isTrue();

        // The moderator whose decision is contested can't decide the appeal
        decideAppeal(appealId, "GRANT", "moderator").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("conflict_of_interest"));

        // A different moderator grants it; reinstatement still needs a second review
        decideAppeal(appealId, "GRANT", "reviewer").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRANTED"))
                .andExpect(jsonPath("$.decidedBy").value("reviewer"))
                .andExpect(jsonPath("$.item.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.item.pendingDecision").value("ALLOW"));
        secondReview(id, true, "reviewer").andExpect(status().isForbidden());
        secondReview(id, true, "moderator").andExpect(status().isForbidden());
        secondReview(id, true, "senior").andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("ALLOW"));

        JsonNode st = json(mockMvc.perform(get("/api/appeals/" + number + "/status").header("X-Case-Pin", pin))
                .andExpect(status().isOk()));
        assertThat(st.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(st.get("appeal").get("status").asText()).isEqualTo("GRANTED");
        assertThat(st.get("appeal").get("reinstated").asBoolean()).isTrue();
        assertThat(st.get("appeal").get("reason").asText()).contains("synthetic");
        assertThat(st.toString()).doesNotContain("reviewer", "senior", "\"moderator\"");

        // Public trail: every step, no moderator names
        JsonNode trail = json(mockMvc.perform(get("/api/verify/" + pid)).andExpect(status().isOk()));
        List<String> types = new ArrayList<>();
        trail.get("events").forEach(e -> types.add(e.get("type").asText()));
        assertThat(types).contains("APPEAL_FILED", "APPEAL_DECIDED", "SECOND_REVIEW_REQUESTED", "SECOND_REVIEW_COMPLETED");
        assertThat(trail.toString()).doesNotContain("reviewer", "senior");

        // Internal log names who did what
        JsonNode log = json(mockMvc.perform(get("/api/queue/" + id + "/audit").with(httpBasic("senior", "senior"))).andExpect(status().isOk()));
        assertThat(log.toString()).contains("\"actor\":\"reviewer\",\"action\":\"APPEAL_DECIDED\"", "\"actor\":\"senior\",\"action\":\"SECOND_REVIEW_CONFIRMED\"");

        String text = reportText(id);
        assertThat(text).contains("4.Moderationandappeal", number, "GRANTED", "APPEALDECIDED", "9.Digitalsignature");
    }

    @Test
    void appealsOnlyForTakedownsAndPinLockout() throws Exception {
        nextForgery = 0.2;
        JsonNode allowed = analyze();
        assertThat(allowed.get("notice").get("appealable").asBoolean()).isFalse();
        fileAppeal(allowed.get("notice").get("noticeNumber").asText(), allowed.get("notice").get("pin").asText(), "why")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("not_appealable"));

        nextForgery = 0.95;
        JsonNode flagged = analyze();
        String number = flagged.get("notice").get("noticeNumber").asText();
        for (int i = 0; i < 5; i++) {
            fileAppeal(number, "111111", "guess").andExpect(status().isNotFound());
        }
        fileAppeal(number, flagged.get("notice").get("pin").asText(), "right pin, but locked")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void secondReviewRejectKeepsTheTakedownAndUpholdsAGrantedAppeal() throws Exception {
        long id = analyze().get("id").asLong();
        decide(id, "ALLOW", "apac").andExpect(status().isAccepted()).andExpect(jsonPath("$.pendingDecision").value("ALLOW"));
        JsonNode pending = json(mockMvc.perform(get("/api/queue").param("status", "second-review").with(httpBasic("reviewer", "reviewer"))));
        assertThat(find(pending, "id", String.valueOf(id)).get("canConfirm").asBoolean()).isTrue();
        decide(id, "REVIEW", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("second_review_pending"));
        secondReview(id, false, "reviewer").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.pendingDecision").value(Matchers.nullValue()));
    }

    @Test
    void grantedAppealCanOnlyBeOverturnedByAnUnconflictedReviewerWithReasons() throws Exception {
        JsonNode created = analyze();
        long id = created.get("id").asLong();
        String number = created.get("notice").get("noticeNumber").asText();
        String pin = created.get("notice").get("pin").asText();
        decide(id, "AUTO_FLAG", "moderator").andExpect(status().isOk());
        fileAppeal(number, pin, "This is my own synthetic render.").andExpect(status().isCreated());
        long appealId = find(appeals("open", "reviewer"), "caseNumber", number).get("id").asLong();
        decideAppeal(appealId, "GRANT", "reviewer").andExpect(status().isOk());

        // Neither the moderator who owned the takedown nor the one who granted the appeal can veto the grant
        secondReview(id, false, "moderator").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("conflict_of_interest"));
        secondReview(id, false, "reviewer").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("conflict_of_interest"));
        JsonNode pending = json(mockMvc.perform(get("/api/queue").param("status", "second-review").with(httpBasic("moderator", "moderator"))));
        assertThat(find(pending, "id", String.valueOf(id)).get("canConfirm").asBoolean()).isFalse();

        // Overturning a grant changes the uploader's outcome, so it needs a statement of reasons
        secondReview(id, false, "senior").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("reason_required"));
        secondReview(id, false, "senior", "The subject did not consent to this render.").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.decidedBy").value("senior"));

        JsonNode row = find(appeals("decided", "apac"), "caseNumber", number);
        assertThat(row.get("status").asText()).isEqualTo("UPHELD");
        assertThat(row.get("decidedBy").asText()).isEqualTo("senior");
        JsonNode st = json(mockMvc.perform(get("/api/appeals/" + number + "/status").header("X-Case-Pin", pin)));
        assertThat(st.get("appeal").get("status").asText()).isEqualTo("UPHELD");
        assertThat(st.get("appeal").get("reason").asText()).contains("did not consent");
        assertThat(st.get("appeal").get("reinstatementPending").asBoolean()).isFalse();
        assertThat(st.get("statementOfReasons").get("decidedAutomatically").asBoolean()).isFalse();
    }

    @Test
    void reinstatementDropsTheTakedownFromTheKnownHashList() throws Exception {
        nextForgery = 0.6;
        JsonNode created = analyze();
        long id = created.get("id").asLong();
        decide(id, "AUTO_FLAG", "moderator").andExpect(status().isOk());
        String phash = created.get("phash").asText().toLowerCase(java.util.Locale.ROOT);
        String note = "Moderator-confirmed takedown of analysis #" + id;
        assertThat(knownHashes.findByAlgorithmAndHashAndSourceAndNote("phash", phash, KnownHashSource.INTERNAL_CONFIRMED, note)).hasSize(1);

        decide(id, "ALLOW", "reviewer").andExpect(status().isAccepted());
        secondReview(id, true, "apac").andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("ALLOW"));
        assertThat(knownHashes.findByAlgorithmAndHashAndSourceAndNote("phash", phash, KnownHashSource.INTERNAL_CONFIRMED, note)).isEmpty();
    }

    @Test
    void appealClockEscalatesAndShowsOnTheComplianceSummary() throws Exception {
        JsonNode created = analyze();
        long id = created.get("id").asLong();
        String number = created.get("notice").get("noticeNumber").asText();
        decide(id, "AUTO_FLAG", "moderator").andExpect(status().isOk());
        fileAppeal(number, created.get("notice").get("pin").asText(), "Please look again.").andExpect(status().isCreated());

        Appeal appeal = appealRepository.findByAnalysisId(id).orElseThrow();
        appeal.setSlaDeadline(Instant.now().minusSeconds(60));
        appealRepository.save(appeal);
        assertThat(appealService.recalculate()).isGreaterThanOrEqualTo(1);

        Appeal after = appealRepository.findByAnalysisId(id).orElseThrow();
        assertThat(after.getSlaStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(after.getEscalatedAt()).isNotNull();
        JsonNode summary = json(mockMvc.perform(get("/api/compliance/summary").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())).get("appeals");
        assertThat(summary.get("open").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("breached").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode trail = json(mockMvc.perform(get("/api/verify/" + created.get("provenanceId").asText())));
        assertThat(trail.toString()).contains("SLA_ESCALATED");
    }

    // ------------------------------------------------------------------ teams & escalation

    @Test
    void escalatedTakedownsNeedSeniorsForTheDecisionAndItsSecondReview() throws Exception {
        long id = analyze().get("id").asLong();
        mockMvc.perform(post("/api/queue/" + id + "/escalate").with(httpBasic("reviewer", "reviewer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Public figure; needs a policy call\"}"))
                .andExpect(status().isOk());
        JsonNode escalated = json(mockMvc.perform(get("/api/queue").param("status", "escalated").with(httpBasic("senior", "senior"))));
        assertThat(find(escalated, "id", String.valueOf(id))).isNotNull();
        JsonNode flagged = json(mockMvc.perform(get("/api/queue").param("status", "flagged").with(httpBasic("reviewer", "reviewer"))));
        assertThat(find(flagged, "id", String.valueOf(id))).isNull();

        // Confirming the takedown is still a decision on an escalated item
        decide(id, "AUTO_FLAG", "reviewer").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("senior_required"));
        decide(id, "ALLOW", "senior").andExpect(status().isAccepted());
        secondReview(id, true, "reviewer").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("senior_required"));
        secondReview(id, true, "senior").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("conflict_of_interest"));
        secondReview(id, true, "senior2").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.seniorEscalated").value(false))
                .andExpect(jsonPath("$.seniorResolvedBy").value("senior2"));
        JsonNode after = json(mockMvc.perform(get("/api/queue").param("status", "escalated").with(httpBasic("senior", "senior"))));
        assertThat(find(after, "id", String.valueOf(id))).isNull();
    }

    @Test
    void seniorsRouteItemsToATeamPoolThatOnlyThatTeamCanWork() throws Exception {
        nextForgery = 0.6;
        long id = analyze().get("id").asLong();
        assign(id, "{\"team\":\"apac\"}", "apac").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("senior_required"));
        assign(id, "{\"team\":\"nowhere\"}", "senior").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unknown_team"));
        assign(id, "{\"team\":\"apac\"}", "senior").andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.routedTeam").value("apac"));

        JsonNode pool = json(mockMvc.perform(get("/api/queue").param("scope", "team").with(httpBasic("apac", "apac"))));
        assertThat(find(pool, "id", String.valueOf(id))).isNotNull();
        JsonNode unassigned = json(mockMvc.perform(get("/api/queue").param("scope", "unassigned").with(httpBasic("reviewer", "reviewer"))));
        assertThat(find(unassigned, "id", String.valueOf(id))).isNull();
        decide(id, "ALLOW", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("team_queue"));
        assign(id, "", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("team_queue"));

        // A team member claims it, releases it, and it goes back to the team pool rather than to everyone
        assign(id, "", "apac").andExpect(status().isOk()).andExpect(jsonPath("$.assignedTo").value("apac"));
        mockMvc.perform(post("/api/queue/" + id + "/unassign").with(httpBasic("apac", "apac"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.assignedTeam").value("apac"));
        decide(id, "ALLOW", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("team_queue"));
        decide(id, "ALLOW", "apac").andExpect(status().isOk());
    }

    @Test
    void escalatedItemsNeedASeniorAndAssignedItemsBelongToTheAssignee() throws Exception {
        nextForgery = 0.6;
        long escalated = analyze().get("id").asLong();
        mockMvc.perform(post("/api/queue/" + escalated + "/escalate").with(httpBasic("apac", "apac"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Possible public-figure satire; policy call\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seniorEscalated").value(true));
        JsonNode senQueue = json(mockMvc.perform(get("/api/queue").param("status", "escalated").with(httpBasic("senior", "senior"))));
        assertThat(find(senQueue, "id", String.valueOf(escalated))).isNotNull();
        JsonNode review = json(mockMvc.perform(get("/api/queue").with(httpBasic("reviewer", "reviewer"))));
        assertThat(find(review, "id", String.valueOf(escalated))).isNull();
        decide(escalated, "ALLOW", "reviewer").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("senior_required"));
        decide(escalated, "ALLOW", "senior").andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("ALLOW"));

        long assigned = analyze().get("id").asLong();
        mockMvc.perform(post("/api/queue/" + assigned + "/assign").with(httpBasic("apac", "apac")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value("apac"))
                .andExpect(jsonPath("$.assignedTeam").value("apac"));
        JsonNode mine = json(mockMvc.perform(get("/api/queue").param("scope", "mine").with(httpBasic("apac", "apac"))));
        assertThat(find(mine, "id", String.valueOf(assigned))).isNotNull();
        mockMvc.perform(post("/api/queue/" + assigned + "/assign").with(httpBasic("reviewer", "reviewer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assignee\":\"reviewer\"}"))
                .andExpect(status().isConflict());
        decide(assigned, "ALLOW", "reviewer").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("assigned_elsewhere"));
        decide(assigned, "ALLOW", "apac").andExpect(status().isOk());

        JsonNode me = json(mockMvc.perform(get("/api/moderation/me").with(httpBasic("senior", "senior"))));
        assertThat(me.get("moderator").get("role").asText()).isEqualTo("SENIOR_MODERATOR");
        JsonNode teams = json(mockMvc.perform(get("/api/moderation/teams").with(httpBasic("apac", "apac"))));
        assertThat(teams.has("core")).isTrue();
        assertThat(teams.get("apac").get(0).get("username").asText()).isEqualTo("apac");
        mockMvc.perform(post("/api/auth/login").with(httpBasic("senior", "senior")))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_MODERATOR"))
                .andExpect(jsonPath("$.roles[1]").value("ROLE_SENIOR_MODERATOR"));
    }

    // ------------------------------------------------------------------ jurisdictions & transparency

    @Test
    void newJurisdictionsAreSeededAsPendingLegalReview() throws Exception {
        JsonNode rules = json(mockMvc.perform(get("/api/compliance/rules").with(httpBasic("moderator", "moderator")))).get("rules");
        for (String code : List.of("EU", "GB", "AU")) {
            JsonNode row = null;
            for (JsonNode r : rules) {
                if (code.equals(r.get("jurisdiction").asText()) && "USER_COMPLAINT".equals(r.get("complaintType").asText())) {
                    row = r;
                }
            }
            assertThat(row).as(code).isNotNull();
            assertThat(row.get("label").asText()).contains("pending legal review");
        }
        mockMvc.perform(multipart("/api/compliance/complaints")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1}))
                        .param("complaintType", "APPEAL"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void euMemberStatesFollowTheEuAppealRuleAndBadCodesAreRejected() throws Exception {
        JsonNode created = json(mockMvc.perform(multipart("/api/analyze").file(image()).param("jurisdiction", "de"))
                .andExpect(status().isOk()));
        assertThat(created.get("jurisdiction").asText()).isEqualTo("DE");
        decide(created.get("id").asLong(), "AUTO_FLAG", "moderator").andExpect(status().isOk());
        JsonNode appeal = json(fileAppeal(created.get("notice").get("noticeNumber").asText(), created.get("notice").get("pin").asText(),
                "Mine, and synthetic.").andExpect(status().isCreated()));
        assertThat(appeal.get("rule").asText()).isEqualTo("EU DSA Art. 20 (pending legal review)");

        mockMvc.perform(multipart("/api/analyze").file(image()).param("jurisdiction", "not a code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedImagesAndVideoOriginalsAreRejected() throws Exception {
        mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[25 * 1024 * 1024 + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("too_large"));

        when(mlClientService.inferVideo(any(), anyString(), any(), anyDouble(), anyInt()))
                .thenReturn(videoResponse(new double[] {0.1, 0.2, 0.3}, new double[] {0.01, 0.01, 0.01}, 3));
        byte[] clip = {0, 0, 0, 24, 'f', 't', 'y', 'p'};
        long id = json(mockMvc.perform(multipart("/api/analyze").file(new MockMultipartFile("file", "clip.mp4", "video/mp4", clip)))
                .andExpect(status().isOk())).get("id").asLong();
        mockMvc.perform(multipart("/api/queue/" + id + "/report").file(new MockMultipartFile("file", "clip.mp4", "video/mp4", clip))
                        .with(httpBasic("moderator", "moderator")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transparencyReportIsBuiltFromLiveAggregates() throws Exception {
        nextForgery = 0.95;
        long id = analyze().get("id").asLong();
        decide(id, "ALLOW", "moderator").andExpect(status().isAccepted());
        secondReview(id, true, "reviewer").andExpect(status().isOk());

        JsonNode r = json(mockMvc.perform(get("/api/transparency").param("period", "last-30d")).andExpect(status().isOk()));
        assertThat(r.get("period").get("label").asText()).isEqualTo("last-30d");
        assertThat(r.get("volumes").get("uploads").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("decisions").get("automated").get("AUTO_FLAG").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("moderation").get("autoFlagOverturned").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("moderation").get("autoFlagOverturnRate").isNumber()).isTrue();
        assertThat(r.has("sla")).isTrue();
        assertThat(r.get("appeals").has("grantRate")).isTrue();
        assertThat(r.toString()).doesNotContain("reviewer", "UPL-", "\"id\"");

        mockMvc.perform(get("/api/transparency").param("period", String.valueOf(java.time.Year.now().getValue()))).andExpect(status().isOk());
        mockMvc.perform(get("/api/transparency").param("period", "2026-Q3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-07-01T00:00:00Z"));
        mockMvc.perform(get("/api/transparency").param("period", "soon")).andExpect(status().isBadRequest());
        // Windows shorter than a week would let small cells single out individual uploads
        mockMvc.perform(get("/api/transparency").param("period", "last-3d")).andExpect(status().isBadRequest());
        assertThat(r.get("suppression").get("minCell").asInt()).isEqualTo(1);
        assertThat(r.get("moderation").has("moderatorOverturnRate")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode analyze() throws Exception {
        return json(mockMvc.perform(multipart("/api/analyze").file(image())).andExpect(status().isOk()));
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private ResultActions assign(long id, String body, String user) throws Exception {
        return mockMvc.perform(post("/api/queue/" + id + "/assign").with(httpBasic(user, user))
                .contentType(MediaType.APPLICATION_JSON).content(body.isEmpty() ? "{}" : body));
    }

    private JsonNode appeals(String status, String user) throws Exception {
        return json(mockMvc.perform(get("/api/appeals").param("status", status).with(httpBasic(user, user))).andExpect(status().isOk()));
    }

    private ResultActions decide(long id, String decision, String user) throws Exception {
        return mockMvc.perform(post("/api/queue/" + id + "/decision").with(httpBasic(user, user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"" + decision + "\"}"));
    }

    private ResultActions secondReview(long id, boolean confirm, String user) throws Exception {
        return secondReview(id, confirm, user, null);
    }

    private ResultActions secondReview(long id, boolean confirm, String user, String note) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("confirm", confirm);
        body.put("note", note);
        return mockMvc.perform(post("/api/queue/" + id + "/second-review").with(httpBasic(user, user))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions fileAppeal(String number, String pin, String statement) throws Exception {
        return mockMvc.perform(post("/api/appeals").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("noticeNumber", number, "pin", pin, "statement", statement))));
    }

    private ResultActions decideAppeal(long appealId, String outcome, String user) throws Exception {
        return mockMvc.perform(post("/api/appeals/" + appealId + "/decision").with(httpBasic(user, user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\",\"reason\":\"Uploader showed this is their own synthetic render.\"}"));
    }

    private String reportText(long id) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/queue/" + id + "/report").with(httpBasic("moderator", "moderator")))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(res.getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
        }
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asText())) {
                return n;
            }
        }
        return null;
    }
}

package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.service.MlLabelResponse;
import com.forgeddetect.service.MlProvenanceCheck;
import com.forgeddetect.service.MlReadResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 14 DoD: embedded manifests are traced on re-upload; /api/verify returns the audit trail or 404. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase14;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "decision.forgery-review=0.50",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false"
        })
class ProvenanceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private MlClientService mlClientService;

    private static final MockMultipartFile UPLOAD =
            new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01});

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private static MlInferResponse inference(String provenanceId, String contentHash, String phash, MlProvenanceCheck embedded) {
        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(0.62);
        ml.setExplicitScore(0.04);
        ml.setPhash(phash);
        ml.setHeatmap("");
        ml.setContentHash(contentHash);
        ml.setProvenanceId(provenanceId);
        ml.setProvenance(embedded);
        return ml;
    }

    private static MlProvenanceCheck manifest(String provenanceId, String status) {
        MlProvenanceCheck m = new MlProvenanceCheck();
        m.setSpec("fmd.provenance/1");
        m.setProvenanceId(provenanceId);
        m.setIssuer("forged-media-detection");
        m.setAnalyzedAt("2026-09-27T09:00:00Z");
        m.setForgeryScore(0.62);
        m.setExplicitScore(0.04);
        m.setDecision("REVIEW");
        m.setStatus(status);
        m.setSignatureValid(!"INVALID_SIGNATURE".equals(status));
        m.setContentMatch("VERIFIED".equals(status));
        return m;
    }

    private JsonNode analyze(MlInferResponse ml) throws Exception {
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);
        String body = mockMvc.perform(multipart("/api/analyze").file(UPLOAD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void reuploadedManifestIsTracedAndAppearsInTheAuditTrail() throws Exception {
        String original = uid();
        String reupload = uid();
        when(mlClientService.label(any(), anyString(), any(), anyMap()))
                .thenReturn(new MlLabelResponse("bGFiZWxsZWQ=", "image/jpeg", null));

        JsonNode first = analyze(inference(original, "sha256:" + uid(), "1111aaaa2222bbbb", null));
        assertThat(first.get("provenanceId").asText()).isEqualTo(original);
        assertThat(first.get("labelledImage").asText()).isEqualTo("bGFiZWxsZWQ=");
        assertThat(first.get("labelledMimeType").asText()).isEqualTo("image/jpeg");
        assertThat(first.get("embeddedSource").isNull()).isTrue();

        JsonNode second = analyze(inference(reupload, "sha256:" + uid(), "1111aaaa2222bbbb", manifest(original, "VERIFIED")));
        assertThat(second.at("/embeddedSource/via").asText()).isEqualTo("MANIFEST");
        assertThat(second.at("/embeddedSource/known").asBoolean()).isTrue();
        assertThat(second.at("/embeddedSource/provenanceId").asText()).isEqualTo(original);
        assertThat(second.at("/embeddedSource/analysisId").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(second.at("/embeddedSource/manifest/status").asText()).isEqualTo("VERIFIED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(mlClientService, org.mockito.Mockito.times(2)).label(any(), anyString(), any(), fields.capture());
        assertThat(fields.getValue())
                .containsEntry("provenance_id", reupload)
                .containsEntry("derived_from", original)
                .containsEntry("decision", "REVIEW")
                .containsKey("analyzed_at");

        mockMvc.perform(get("/api/verify/" + original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceId").value(original))
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.reuploads").value(1))
                .andExpect(jsonPath("$.derivedFrom").value(nullValue()))
                .andExpect(jsonPath("$.events[*].type").value(contains("ANALYZED", "REUPLOAD_DETECTED")))
                .andExpect(jsonPath("$.events[1].analysisId").value(second.get("id").asLong()));

        mockMvc.perform(get("/api/verify/" + reupload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedFrom").value(original));
    }

    @Test
    void unknownOrMalformedIdIs404() throws Exception {
        mockMvc.perform(get("/api/verify/" + uid()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Unknown provenance ID"));
        mockMvc.perform(get("/api/verify/" + "x".repeat(65))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/verify/not_valid")).andExpect(status().isNotFound());
    }

    @Test
    void moderatorDecisionIsAppendedToThePublicTrail() throws Exception {
        String pid = uid();
        JsonNode created = analyze(inference(pid, "sha256:" + uid(), "3333cccc4444dddd", null));

        mockMvc.perform(post("/api/queue/" + created.get("id").asLong() + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"AUTO_FLAG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceId").value(pid));

        mockMvc.perform(get("/api/verify/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.events[*].type").value(contains("ANALYZED", "DECISION_CHANGED")))
                .andExpect(jsonPath("$.events[1].actor").value("moderator"))
                .andExpect(jsonPath("$.events[1].detail").value("Decision changed from REVIEW to AUTO_FLAG"));
    }

    @Test
    void complaintTrailNeverExposesTheComplaintReference() throws Exception {
        String pid = uid();
        when(mlClientService.infer(any(), anyString(), any()))
                .thenReturn(inference(pid, "sha256:" + uid(), "5555eeee6666ffff", null));

        mockMvc.perform(multipart("/api/compliance/complaints")
                        .file(UPLOAD)
                        .param("complaintType", "USER_COMPLAINT")
                        .param("reference", "NCII-CONFIDENTIAL-77"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceId").value(pid));

        String trail = mockMvc.perform(get("/api/verify/" + pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complaint.type").value("USER_COMPLAINT"))
                .andExpect(jsonPath("$.complaint.slaStatus").value("ON_TRACK"))
                .andExpect(jsonPath("$.events[*].type").value(contains("ANALYZED", "COMPLAINT_OPENED")))
                .andReturn().getResponse().getContentAsString();
        assertThat(trail).doesNotContain("NCII-CONFIDENTIAL-77");
    }

    @Test
    void uploadVerificationResolvesManifestPixelsOrNothing() throws Exception {
        String pid = uid();
        String contentHash = "sha256:" + uid();
        analyze(inference(pid, contentHash, "7777aaaa8888bbbb", null));

        when(mlClientService.readProvenance(any(), anyString(), any()))
                .thenReturn(new MlReadResponse("7777aaaa8888bbbb", contentHash, manifest(pid, "VERIFIED")));
        mockMvc.perform(multipart("/api/verify").file(UPLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("MANIFEST"))
                .andExpect(jsonPath("$.manifest.status").value("VERIFIED"))
                .andExpect(jsonPath("$.record.provenanceId").value(pid));

        when(mlClientService.readProvenance(any(), anyString(), any()))
                .thenReturn(new MlReadResponse("0000000011111111", contentHash, null));
        mockMvc.perform(multipart("/api/verify").file(UPLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("CONTENT_HASH"))
                .andExpect(jsonPath("$.manifest").value(nullValue()))
                .andExpect(jsonPath("$.record.provenanceId").value(pid));

        when(mlClientService.readProvenance(any(), anyString(), any()))
                .thenReturn(new MlReadResponse("fedcba9876543210", "sha256:" + uid(), null));
        mockMvc.perform(multipart("/api/verify").file(UPLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("NONE"))
                .andExpect(jsonPath("$.record").value(nullValue()));
    }

    @Test
    void labellingFailureDoesNotFailTheAnalysis() throws Exception {
        when(mlClientService.label(any(), anyString(), any(), anyMap()))
                .thenThrow(new IllegalStateException("ML service /provenance/embed failed: HTTP 422"));
        String pid = uid();
        JsonNode result = analyze(inference(pid, "sha256:" + uid(), "9999cccc0000dddd", null));
        assertThat(result.get("provenanceId").asText()).isEqualTo(pid);
        assertThat(result.get("labelledImage").isNull()).isTrue();
        mockMvc.perform(get("/api/verify/" + pid)).andExpect(status().isOk());
    }
}

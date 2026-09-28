package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.signing.SigningKeyProvider;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 20 DoD: a fresh report verifies VALID; editing a field of the analysis afterwards makes it TAMPERED.
 * Also covers the Phase 17 generation-family passthrough, which the signed report includes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase20;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false",
            "report.signing.key-dir=${java.io.tmpdir}/fmd-phase20-keys"
        })
class ReportSigningIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnalysisResultRepository repository;
    @Autowired private SigningKeyProvider keys;

    @MockBean private MlClientService mlClientService;

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.912345);
            ml.setExplicitScore(0.12);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setProvenanceId(UUID.randomUUID().toString());
            ml.setContentHash("sha256:" + "ab".repeat(32));
            ml.setGenerationFamily("diffusion");
            ml.setFamilyConfidence(0.93);
            return ml;
        });
    }

    private long analyze() throws Exception {
        String body = mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generationFamily").value("diffusion"))
                .andExpect(jsonPath("$.familyConfidence").value(0.93))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private MockHttpServletResponse report(long id) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/queue/" + id + "/report").with(httpBasic("moderator", "moderator")))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return res;
    }

    private JsonNode verify(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/verify-report/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void freshReportIsValidAndCarriesItsSignature() throws Exception {
        long id = analyze();
        MockHttpServletResponse res = report(id);
        String reportId = res.getHeader("X-Report-Id");

        JsonNode v = verify(reportId);
        assertThat(v.get("status").asText()).isEqualTo("VALID");
        assertThat(v.get("analysisId").asLong()).isEqualTo(id);
        assertThat(v.get("algorithm").asText()).isEqualTo("Ed25519");
        assertThat(v.get("changedFields")).isEmpty();
        assertThat(verify(String.valueOf(id)).get("status").asText()).isEqualTo("VALID");

        AnalysisResult item = repository.findById(id).orElseThrow();
        byte[] pdf = res.getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            assertThat(info.getCustomMetadataValue("FMD-Signature")).isEqualTo(item.getReportSignature());
            assertThat(info.getCustomMetadataValue("FMD-Signed-Content-SHA256")).isEqualTo(item.getReportContentHash());
            assertThat(info.getCustomMetadataValue("FMD-Report-Id")).isEqualTo(reportId);
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            assertThat(text).contains("9.Digitalsignature", item.getReportSignature(), item.getReportContentHash(),
                    "/api/verify-report/" + reportId, "Diffusionmodel");
        }

        // Anyone can check the signature offline with the published public key
        String pem = mockMvc.perform(get("/api/verify-report/public-key")).andReturn().getResponse().getContentAsString();
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", ""));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        verifier.update(MessageDigest.getInstance("SHA-256").digest(item.getReportCanonical().getBytes(StandardCharsets.UTF_8)));
        assertThat(verifier.verify(Base64.getDecoder().decode(item.getReportSignature()))).isTrue();
    }

    @Test
    void editingTheRecordAfterSigningIsTampered() throws Exception {
        long id = analyze();
        String reportId = report(id).getHeader("X-Report-Id");
        assertThat(verify(reportId).get("status").asText()).isEqualTo("VALID");

        AnalysisResult item = repository.findById(id).orElseThrow();
        item.setForgeryScore(0.10);
        item.setDecision(Decision.ALLOW);
        repository.save(item);

        JsonNode v = verify(reportId);
        assertThat(v.get("status").asText()).isEqualTo("TAMPERED");
        assertThat(v.get("changedFields").toString()).contains("forgery_score", "decision");
        assertThat(v.get("message").asText()).contains("forgery_score");
        // Field values are never echoed on this public endpoint
        assertThat(v.toString()).doesNotContain("0.912345", "0.100000");

        // A newly issued report reflects the corrected record and verifies again
        String reissued = report(id).getHeader("X-Report-Id");
        assertThat(verify(reissued).get("status").asText()).isEqualTo("VALID");
    }

    @Test
    void forgedSignatureRecordIsTampered() throws Exception {
        long id = analyze();
        String reportId = report(id).getHeader("X-Report-Id");
        AnalysisResult item = repository.findById(id).orElseThrow();
        item.setReportCanonical(item.getReportCanonical().replace("decision=AUTO_FLAG", "decision=ALLOW"));
        item.setDecision(Decision.ALLOW);
        repository.save(item);

        JsonNode v = verify(reportId);
        assertThat(v.get("status").asText()).isEqualTo("TAMPERED");
        assertThat(v.get("changedFields").toString()).contains("signature");
    }

    @Test
    void v2SnapshotCoversTheContentCredentialsAndEscalation() throws Exception {
        long id = analyze();
        String reportId = report(id).getHeader("X-Report-Id");
        AnalysisResult item = repository.findById(id).orElseThrow();
        assertThat(item.getReportCanonical()).startsWith("fmd-evidentiary-report/v2\n");

        // c2pa_status alone (v1) wouldn't notice a swapped manifest that keeps the same status
        item.setC2paManifest("{\"signer\":{\"common_name\":\"Someone Else\"}}");
        item.setSeniorEscalatedAt(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        repository.save(item);
        JsonNode v = verify(reportId);
        assertThat(v.get("status").asText()).isEqualTo("TAMPERED");
        assertThat(v.get("changedFields").toString()).contains("c2pa_manifest_sha256", "senior_escalated_at");
    }

    @Test
    void reportsSignedWithTheV1SnapshotStillVerify() throws Exception {
        long id = analyze();
        report(id);
        AnalysisResult item = repository.findById(id).orElseThrow();
        // No video, C2PA or escalation on this item, so its v1 snapshot is the v2 one under the old header
        String v1 = item.getReportCanonical().replaceFirst("^fmd-evidentiary-report/v2\n", "fmd-evidentiary-report/v1\n");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(v1.getBytes(StandardCharsets.UTF_8));
        SigningKeyProvider.SignatureResult signed = keys.sign(digest);
        item.setReportCanonical(v1);
        item.setReportContentHash(java.util.HexFormat.of().formatHex(digest));
        item.setReportSignature(Base64.getEncoder().encodeToString(signed.signature()));
        item.setReportSigningKeyId(signed.keyId());
        item.setReportTimestampToken(null);
        // A field v1 never covered changes: still VALID
        item.setC2paManifest("{\"signer\":{\"common_name\":\"Added later\"}}");
        repository.save(item);

        assertThat(verify(item.getReportId()).get("status").asText()).isEqualTo("VALID");
        item.setDecision(Decision.ALLOW);
        repository.save(item);
        assertThat(verify(item.getReportId()).get("changedFields").toString()).contains("decision");
    }

    @Test
    void unknownOrUnsignedReportsAreNotFound() throws Exception {
        assertThat(verify("RPT-999999-20260101000000").get("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(verify("not-a-report").get("status").asText()).isEqualTo("NOT_FOUND");
        long unsigned = analyze();
        assertThat(verify(String.valueOf(unsigned)).get("status").asText()).isEqualTo("NOT_FOUND");
        long id = analyze();
        String first = report(id).getHeader("X-Report-Id");
        Thread.sleep(1100);
        String second = report(id).getHeader("X-Report-Id");
        assertThat(second).isNotEqualTo(first);
        assertThat(verify(first).get("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(verify(first).get("message").asText()).contains("superseded");
        assertThat(verify(second).get("status").asText()).isEqualTo("VALID");
    }
}

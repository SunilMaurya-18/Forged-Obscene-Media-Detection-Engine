package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.Decision;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.service.MlPanelsResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 15 DoD: "Download Report" on a flagged item yields a readable PDF with every field populated. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase15;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "decision.forgery-review=0.50",
            "decision.forgery-auto-flag=0.85",
            "decision.explicit-review=0.50",
            "decision.explicit-auto-flag=0.85",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false"
        })
class ReportIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnalysisResultRepository repository;

    @MockitoBean private MlClientService mlClientService;

    private static final MockMultipartFile UPLOAD =
            new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01});

    private static String grid(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < n; r++) {
            sb.append(r > 0 ? "," : "").append('[');
            for (int c = 0; c < n; c++) {
                sb.append(c > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.2f", ((r * 7 + c * 3) % 11) / 10.0));
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private JsonNode signature() throws Exception {
        StringBuilder profile = new StringBuilder("[");
        for (int i = 0; i < 32; i++) {
            profile.append(i > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.3f", 1.0 / (i + 1)));
        }
        profile.append(']');
        return objectMapper.readTree("""
                {"version":1,
                 "cam":{"grid":%s,"peak":{"x":0.71,"y":0.28}},
                 "ela":{"grid":%s,"mean":1.84,"p95":4.2,"max":37.0,"quality":90},
                 "fft":{"grid":%s,"profile":%s,"high_freq_ratio":0.0312}}
                """.formatted(grid(14), grid(16), grid(32), profile));
    }

    private MlInferResponse inference(double forgery, double explicit, String contentHash) throws Exception {
        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(forgery);
        ml.setExplicitScore(explicit);
        ml.setPhash("a1b2c3d4e5f60718");
        ml.setHeatmap("");
        ml.setContentHash(contentHash);
        ml.setProvenanceId(UUID.randomUUID().toString());
        ml.setForensics(signature());
        return ml;
    }

    private JsonNode fileComplaint(MlInferResponse ml, String reference) throws Exception {
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);
        String body = mockMvc.perform(multipart("/api/compliance/complaints")
                        .file(UPLOAD)
                        .param("complaintType", "GOVERNMENT_ORDER")
                        .param("reference", reference))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private MockHttpServletResponse report(long id, MockMultipartFile original) throws Exception {
        var request = multipart("/api/queue/" + id + "/report");
        if (original != null) {
            request.file(original);
        }
        return mockMvc.perform(request.with(httpBasic("moderator", "moderator"))).andReturn().getResponse();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int imageCount(byte[] pdf) throws Exception {
        int n = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    if (page.getResources().isImageXObject(name)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static String pngBase64() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void flaggedComplaintReportHasEveryFieldPopulated() throws Exception {
        String contentHash = "sha256:" + "ab".repeat(32);
        JsonNode item = fileComplaint(inference(0.91, 0.12, contentHash), "MeitY/69A/2026/777");
        long id = item.get("id").asLong();
        String pid = item.get("provenanceId").asText();
        assertThat(item.get("decision").asText()).isEqualTo("AUTO_FLAG");

        mockMvc.perform(post("/api/queue/" + id + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"AUTO_FLAG\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/queue").param("status", "flagged").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].hasForensics").value(true));

        MockHttpServletResponse res = report(id, null);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).isEqualTo("application/pdf");
        assertThat(res.getHeader("Content-Disposition")).contains("decision-record-" + id + ".pdf");
        assertThat(res.getHeader("X-Report-Panels")).isEqualTo("SIGNATURE");
        byte[] pdf = res.getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String reportId = res.getHeader("X-Report-Id");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        assertThat(res.getHeader("X-Report-Sha256")).isEqualTo(sha);

        String text = text(pdf);
        assertThat(text).contains(
                "Decision record", "Evidentiary Analysis Report", reportId, "moderator (moderator)",
                "AUTO-FLAGGED", "REMOVED AUTOMATICALLY", "How to verify this report",
                "Important limits", "What this report is", "Decision record v2",
                "#" + id, pid, "a1b2c3d4e5f60718",
                "0.9100", "0.1200", "Auto-flag band",
                "Government takedown order", "MeitY/69A/2026/777", "3h",
                "Received", "Deadline", "Resolved at", "before the deadline", "IST",
                "Grad-CAM attention", "Peak at x 71%, y 28%",
                "FFT magnitude spectrum", "High-frequency energy 3.12%",
                "Error level analysis", "Mean 1.84",
                "Radial spectrum profile", "Panel source: signature",
                "ANALYZED", "COMPLAINT OPENED", "DECISION CHANGED", "SLA RESOLVED",
                "/verify/" + pid, "Glossary", "Page 2");
        assertThat(text.replaceAll("\\s+", "")).contains(contentHash);
        assertThat(text).doesNotContain("Not available", "Not yet resolved");
        assertThat(imageCount(pdf)).isGreaterThanOrEqualTo(4);

        mockMvc.perform(get("/api/verify/" + pid))
                .andExpect(jsonPath("$.events[-1].type").value("REPORT_GENERATED"))
                .andExpect(jsonPath("$.events[-1].detail").value(
                        "Evidentiary report " + reportId + " generated and signed (Ed25519 key "
                                + res.getHeader("X-Report-Signing-Key") + "); PDF SHA-256 " + sha));
    }

    @Test
    void reportRequiresModeratorAndKnownItem() throws Exception {
        mockMvc.perform(multipart("/api/queue/1/report")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/queue/999999/report").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachedOriginalIsVerifiedAgainstThePixelHash() throws Exception {
        String contentHash = "sha256:" + "cd".repeat(32);
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(inference(0.62, 0.05, contentHash));
        long id = objectMapper.readTree(mockMvc.perform(multipart("/api/analyze").file(UPLOAD))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        String png = pngBase64();
        when(mlClientService.forensicPanels(any(), anyString(), any()))
                .thenReturn(new MlPanelsResponse(png, png, png, "a1b2c3d4e5f60718", contentHash));
        MockHttpServletResponse ok = report(id, UPLOAD);
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("X-Report-Panels")).isEqualTo("ORIGINAL");
        assertThat(text(ok.getContentAsByteArray()).replaceAll("\\s+", " "))
                .contains("Panel source: original", "The file was not retained");

        when(mlClientService.forensicPanels(any(), anyString(), any()))
                .thenReturn(new MlPanelsResponse(png, png, png, "a1b2c3d4e5f60718", "sha256:" + "ee".repeat(32)));
        mockMvc.perform(multipart("/api/queue/" + id + "/report").file(UPLOAD).with(httpBasic("moderator", "moderator")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("pixel hash does not match")));
    }

    @Test
    void explicitItemsNeverEmbedTheImageEvenWithTheOriginal() throws Exception {
        String contentHash = "sha256:" + "ef".repeat(32);
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(inference(0.20, 0.91, contentHash));
        long id = objectMapper.readTree(mockMvc.perform(multipart("/api/analyze").file(UPLOAD))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        String png = pngBase64();
        when(mlClientService.forensicPanels(any(), anyString(), any()))
                .thenReturn(new MlPanelsResponse(png, png, png, "a1b2c3d4e5f60718", contentHash));
        MockHttpServletResponse res = report(id, UPLOAD);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(text(res.getContentAsByteArray()))
                .contains("never reproduces the image", "Explicit content (separate model)", "0.9100");
    }

    @Test
    void legacyItemWithoutSignatureStillProducesAReport() throws Exception {
        AnalysisResult legacy = new AnalysisResult();
        legacy.setPhash("legacy0000000001");
        legacy.setForgeryScore(0.55);
        legacy.setExplicitScore(0.01);
        legacy.setDecision(Decision.REVIEW);
        legacy.setProvenanceId(UUID.randomUUID().toString());
        long id = repository.save(legacy).getId();

        MockHttpServletResponse res = report(id, null);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Report-Panels")).isEqualTo("NONE");
        assertThat(text(res.getContentAsByteArray()).replaceAll("\\s+", " "))
                .contains(
                        "NEEDS HUMAN REVIEW",
                        "None filed",
                        "Stored fingerprints",
                        "Perceptual hash mosaic",
                        "Score meters",
                        "Content-hash pattern",
                        "legacy0000000001",
                        "fingerprints and scores",
                        "No media was retained")
                .doesNotContain("Not available");
        verify(mlClientService, never()).forensicPanels(any(), anyString(), any());
    }
}

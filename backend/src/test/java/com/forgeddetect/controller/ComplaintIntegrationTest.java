package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.forgeddetect.model.Complaint;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ComplaintRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Phase 21 DoD: wizard filing without an account, PIN-protected status, notification hook, queue ordering. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase21;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            "compliance.default-jurisdiction=IN",
            "decision.forgery-review=0.5",
            "decision.forgery-auto-flag=0.85",
            "complaints.max-pin-attempts=5",
            "complaints.lockout=PT15M"
        })
class ComplaintIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AnalysisResultRepository results;

    @MockBean private MlClientService mlClientService;

    private double nextForgery = 0.9;

    @BeforeEach
    void stubMl() {
        clock.set(T0);
        nextForgery = 0.9;
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(nextForgery);
            ml.setExplicitScore(0.1);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setProvenanceId(UUID.randomUUID().toString());
            return ml;
        });
    }

    private MockHttpServletRequestBuilder wizard() {
        return multipart("/api/complaints")
                .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                .param("consent", "true")
                .param("relationship", "SELF");
    }

    private JsonNode fileCase(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions lookup(String caseNumber, String pin) throws Exception {
        var request = get("/api/complaints/{n}/status", caseNumber);
        if (pin != null) {
            request.header("X-Case-Pin", pin);
        }
        return mockMvc.perform(request);
    }

    @Test
    void firstTimeUserGetsCaseNumberAndPinWithoutAnAccount() throws Exception {
        JsonNode created = fileCase(wizard().param("contact", "reporter@example.com").param("details", "Seen on a public forum"));

        String caseNumber = created.get("caseNumber").asText();
        String pin = created.get("pin").asText();
        assertThat(caseNumber).matches("FMD-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}");
        assertThat(pin).matches("[0-9]{6}");
        assertThat(created.get("stage").asText()).isEqualTo("IN_REVIEW");
        assertThat(created.get("verdict").asText()).isEqualTo("LIKELY_FORGED");
        assertThat(created.get("notifyChannel").asText()).isEqualTo("EMAIL");
        assertThat(created.has("forgeryScore")).isFalse();
        assertThat(created.has("id")).isFalse();

        Complaint stored = complaints.findByCaseNumber(caseNumber).orElseThrow();
        assertThat(stored.getPinHash()).isNotEqualTo(pin).startsWith("$2");
        assertThat(stored.getLastNotifiedStage()).isEqualTo("IN_REVIEW");
        AnalysisResult item = results.findById(stored.getAnalysisId()).orElseThrow();
        assertThat(item.getComplaintType()).isEqualTo(ComplaintType.USER_COMPLAINT);
        assertThat(item.getComplaintReference()).isEqualTo(caseNumber);
        assertThat(item.getSlaDeadline()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void statusNeedsTheMatchingPinAndSpeaksPlainLanguage() throws Exception {
        JsonNode created = fileCase(wizard());
        String caseNumber = created.get("caseNumber").asText();
        String pin = created.get("pin").asText();

        lookup(caseNumber, pin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"))
                .andExpect(jsonPath("$.verdict").value("LIKELY_FORGED"))
                .andExpect(jsonPath("$.deadlineHours").value(2.0))
                .andExpect(jsonPath("$.timeline[0].stage").value("RECEIVED"))
                .andExpect(jsonPath("$.forgeryScore").doesNotExist())
                .andExpect(jsonPath("$.pin").doesNotExist());

        String sloppy = caseNumber.toLowerCase().replace("-", " ");
        lookup(sloppy, pin).andExpect(status().isOk()).andExpect(jsonPath("$.caseNumber").value(caseNumber));

        String wrong = pin.equals("000000") ? "111111" : "000000";
        lookup(caseNumber, wrong).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("case_not_found"));
        lookup(caseNumber, null).andExpect(status().isNotFound());
        lookup("FMD-2222-2222", pin).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("case_not_found"));
    }

    @Test
    void repeatedWrongPinsLockTheCaseForAWhile() throws Exception {
        JsonNode created = fileCase(wizard());
        String caseNumber = created.get("caseNumber").asText();
        String pin = created.get("pin").asText();
        String wrong = pin.equals("999999") ? "888888" : "999999";

        for (int i = 0; i < 5; i++) {
            lookup(caseNumber, wrong).andExpect(status().isNotFound());
        }
        lookup(caseNumber, pin)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("too_many_attempts"));

        clock.advance(Duration.ofMinutes(16));
        lookup(caseNumber, pin).andExpect(status().isOk());
    }

    @Test
    void moderatorDecisionChangesStatusAndFiresTheNotificationHook() throws Exception {
        JsonNode created = fileCase(wizard().param("contact", "+91 98765 43210"));
        String caseNumber = created.get("caseNumber").asText();
        String pin = created.get("pin").asText();
        assertThat(created.get("notifyChannel").asText()).isEqualTo("SMS");
        Long analysisId = complaints.findByCaseNumber(caseNumber).orElseThrow().getAnalysisId();

        clock.advance(Duration.ofMinutes(40));
        mockMvc.perform(post("/api/queue/{id}/decision", analysisId)
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"AUTO_FLAG\"}"))
                .andExpect(status().isOk());

        lookup(caseNumber, pin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("REMOVED"))
                .andExpect(jsonPath("$.withinDeadline").value(true))
                .andExpect(jsonPath("$.timeline[1].stage").value("REMOVED"));
        assertThat(complaints.findByCaseNumber(caseNumber).orElseThrow().getLastNotifiedStage()).isEqualTo("REMOVED");
    }

    @Test
    void invalidFormsAreRejectedBeforeAnyAnalysis() throws Exception {
        clearInvocations(mlClientService);
        mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", new byte[] {1}))
                        .param("relationship", "SELF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mockMvc.perform(wizard().param("contact", "not a contact"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_contact"));
        mockMvc.perform(multipart("/api/complaints")
                        .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", new byte[] {1}))
                        .param("consent", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("relationship_required"));
        mockMvc.perform(multipart("/api/complaints").param("consent", "true").param("relationship", "SELF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file_required"));
        verifyNoInteractions(mlClientService);
    }

    @Test
    void reviewQueueDefaultsToNearestDeadlineFirst() throws Exception {
        nextForgery = 0.6;
        JsonNode upload = objectMapper.readTree(mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "u.jpg", "image/jpeg", new byte[] {4})))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String usCase = fileCase(wizard().param("jurisdiction", "US")).get("caseNumber").asText();
        String inCase = fileCase(wizard().param("jurisdiction", "IN")).get("caseNumber").asText();
        long usId = complaints.findByCaseNumber(usCase).orElseThrow().getAnalysisId();
        long inId = complaints.findByCaseNumber(inCase).orElseThrow().getAnalysisId();

        JsonNode queue = objectMapper.readTree(mockMvc.perform(get("/api/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<Long> ids = new java.util.ArrayList<>();
        queue.forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).containsSubsequence(inId, usId, upload.get("id").asLong());
    }
}

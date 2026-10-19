package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.model.ComplaintType;
import com.forgeddetect.model.Decision;
import com.forgeddetect.model.SlaStatus;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.ComplianceService;
import com.forgeddetect.service.EscalationService;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.service.SlaClockService;
import com.forgeddetect.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 13 DoD: simulated-time SLA transitions and urgency-ordered compliance queue. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase13;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false"
        })
class ComplianceSlaIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-02-20T10:00:00Z");

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MutableClock clock;
    @Autowired private SlaClockService slaClock;
    @Autowired private ComplianceService complianceService;
    @Autowired private AnalysisResultRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private MlClientService mlClientService;
    @MockitoSpyBean private EscalationService escalationService;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        clock.set(T0);
        clearInvocations(escalationService);

        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(0.62);
        ml.setExplicitScore(0.10);
        ml.setPhash("abcd1234abcd1234");
        ml.setHeatmap("");
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);
    }

    private AnalysisResult seedUserComplaint() {
        AnalysisResult item = new AnalysisResult();
        item.setPhash("seed000000000001");
        item.setForgeryScore(0.9);
        item.setExplicitScore(0.1);
        item.setDecision(Decision.AUTO_FLAG);
        return complianceService.openComplaint(item, ComplaintType.USER_COMPLAINT, "NCII-TEST-1", clock.instant());
    }

    private SlaStatus statusOf(Long id) {
        return repository.findById(id).orElseThrow().getSlaStatus();
    }

    @Test
    void seededUserComplaintTransitionsOnTrackToAtRiskToBreached() {
        AnalysisResult item = seedUserComplaint();
        assertThat(item.getSlaDeadline()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(item.getSlaStatus()).isEqualTo(SlaStatus.ON_TRACK);

        clock.advance(Duration.ofMinutes(89));
        assertThat(slaClock.recalculate()).isZero();
        assertThat(statusOf(item.getId())).isEqualTo(SlaStatus.ON_TRACK);
        verify(escalationService, never()).escalate(any(), any());

        clock.advance(Duration.ofMinutes(2)); // 29 min remaining
        assertThat(slaClock.recalculate()).isEqualTo(1);
        assertThat(statusOf(item.getId())).isEqualTo(SlaStatus.AT_RISK);
        verify(escalationService, times(1)).escalate(any(), eq(SlaStatus.AT_RISK));
        assertThat(repository.findById(item.getId()).orElseThrow().getEscalatedAt()).isEqualTo(clock.instant());

        clock.advance(Duration.ofMinutes(29)); // exactly at deadline
        assertThat(slaClock.recalculate()).isEqualTo(1);
        assertThat(statusOf(item.getId())).isEqualTo(SlaStatus.BREACHED);
        verify(escalationService, times(1)).escalate(any(), eq(SlaStatus.BREACHED));

        clock.advance(Duration.ofHours(1));
        assertThat(slaClock.recalculate()).isZero();
        verify(escalationService, times(2)).escalate(any(), any());
    }

    private long fileComplaint(ComplaintType type, String reference) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3});
        String json = mockMvc.perform(multipart("/api/compliance/complaints")
                        .file(file)
                        .param("complaintType", type.name())
                        .param("reference", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complaintType").value(type.name()))
                .andExpect(jsonPath("$.slaStatus").value("ON_TRACK"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private List<Long> ids(String path) throws Exception {
        String json = mockMvc.perform(get(path).with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(json)) {
            ids.add(node.get("id").asLong());
        }
        return ids;
    }

    @Test
    void complianceQueueIsOrderedByUrgency() throws Exception {
        long gov = fileComplaint(ComplaintType.GOVERNMENT_ORDER, "MeitY/69A/1"); // deadline T0+3h
        clock.advance(Duration.ofMinutes(10));
        long userLate = fileComplaint(ComplaintType.USER_COMPLAINT, "NCII-2"); // T0+2h10m
        clock.set(T0);
        long userEarly = fileComplaint(ComplaintType.USER_COMPLAINT, "NCII-1"); // T0+2h

        assertThat(ids("/api/compliance/queue")).containsExactly(userEarly, userLate, gov);

        mockMvc.perform(get("/api/compliance/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$[0].remainingSeconds").value(7200))
                .andExpect(jsonPath("$[2].complaintReference").value("MeitY/69A/1"));
    }

    @Test
    void breachesAndResolutionStopTheClock() throws Exception {
        long early = fileComplaint(ComplaintType.USER_COMPLAINT, "NCII-1");
        long gov = fileComplaint(ComplaintType.GOVERNMENT_ORDER, "MeitY/69A/2");

        clock.advance(Duration.ofMinutes(125));
        slaClock.recalculate();
        assertThat(ids("/api/compliance/breaches")).containsExactly(early);

        mockMvc.perform(post("/api/queue/" + gov + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"AUTO_FLAG\"}"))
                .andExpect(status().isOk());

        assertThat(ids("/api/compliance/queue")).containsExactly(early);
        AnalysisResult resolved = repository.findById(gov).orElseThrow();
        assertThat(resolved.getResolvedAt()).isEqualTo(clock.instant());
        assertThat(resolved.getSlaStatus()).isEqualTo(SlaStatus.ON_TRACK);

        clock.advance(Duration.ofHours(2));
        slaClock.recalculate();
        assertThat(statusOf(gov)).as("resolved items are no longer recalculated").isEqualTo(SlaStatus.ON_TRACK);

        mockMvc.perform(get("/api/compliance/summary").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.byStatus.BREACHED").value(1))
                .andExpect(jsonPath("$.slaMinutes.USER_COMPLAINT").value(120))
                .andExpect(jsonPath("$.slaMinutes.GOVERNMENT_ORDER").value(180));
    }

    @Test
    void complianceReadsRequireModeratorButFilingIsPublic() throws Exception {
        mockMvc.perform(get("/api/compliance/queue")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/compliance/breaches")).andExpect(status().isUnauthorized());

        MockMultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1});
        mockMvc.perform(multipart("/api/compliance/complaints").file(file).param("complaintType", "SOMETHING"))
                .andExpect(status().isBadRequest());
    }
}

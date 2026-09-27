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
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 19 DoD: IN and US get different, correct deadlines; a rule inserted in the DB applies with no code change. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase19;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            "compliance.generic-user-complaint-hours=24",
            "compliance.generic-government-order-hours=24"
        })
class JurisdictionRulesIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @MockBean private MlClientService mlClientService;

    @BeforeEach
    void stubMl() {
        clock.set(T0);
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.9);
            ml.setExplicitScore(0.2);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setProvenanceId(UUID.randomUUID().toString());
            return ml;
        });
    }

    private JsonNode file(String type, String jurisdiction) throws Exception {
        var request = multipart("/api/compliance/complaints")
                .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                .param("complaintType", type)
                .param("reference", "REF-" + UUID.randomUUID());
        if (jurisdiction != null) {
            request.param("jurisdiction", jurisdiction);
        }
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static Instant deadline(JsonNode item) {
        return Instant.parse(item.get("slaDeadline").asText());
    }

    @Test
    void indiaAndUsComplaintsGetTheirOwnStatutoryDeadlines() throws Exception {
        JsonNode in = file("USER_COMPLAINT", "IN");
        assertThat(deadline(in)).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(in.get("jurisdiction").asText()).isEqualTo("IN");
        assertThat(in.get("slaBadge").asText()).isEqualTo("India IT Rules 2026 \u2014 2h");

        JsonNode us = file("USER_COMPLAINT", "us");
        assertThat(deadline(us)).isEqualTo(T0.plus(Duration.ofHours(48)));
        assertThat(us.get("jurisdiction").asText()).isEqualTo("US");
        assertThat(us.get("slaBadge").asText()).isEqualTo("US Take It Down Act \u2014 48h");

        JsonNode inGov = file("GOVERNMENT_ORDER", "IN");
        assertThat(deadline(inGov)).isEqualTo(T0.plus(Duration.ofHours(3)));

        // No US rule for government orders: falls back to GENERIC
        JsonNode usGov = file("GOVERNMENT_ORDER", "US");
        assertThat(deadline(usGov)).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(usGov.get("slaBadge").asText()).isEqualTo("Platform policy (generic) \u2014 24h");

        // No parameter: platform default (IN), never inferred from the caller
        JsonNode dflt = file("USER_COMPLAINT", null);
        assertThat(dflt.get("jurisdiction").asText()).isEqualTo("IN");
        assertThat(deadline(dflt)).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void ruleInsertedDirectlyInTheDatabaseTakesEffect() throws Exception {
        JsonNode before = file("USER_COMPLAINT", "BR");
        assertThat(deadline(before)).isEqualTo(T0.plus(Duration.ofHours(24)));

        jdbc.update("INSERT INTO jurisdiction_rules (jurisdiction, complaint_type, deadline_hours, label, legal_reference) "
                + "VALUES ('BR', 'USER_COMPLAINT', 12, 'Brazil demo rule', 'Inserted by SQL in a test')");

        JsonNode after = file("USER_COMPLAINT", "BR");
        assertThat(deadline(after)).isEqualTo(T0.plus(Duration.ofHours(12)));
        assertThat(after.get("slaBadge").asText()).isEqualTo("Brazil demo rule \u2014 12h");
        // History is frozen: the earlier complaint keeps the rule it was filed under
        mockMvc.perform(get("/api/compliance/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$[?(@.id == " + before.get("id").asLong() + ")].slaBadge")
                        .value("Platform policy (generic) \u2014 24h"));

        mockMvc.perform(get("/api/compliance/rules").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$.rules[?(@.jurisdiction == 'BR')].label").value("Brazil demo rule"));
    }

    @Test
    void rulesEndpointListsEveryRuleWithItsLabel() throws Exception {
        mockMvc.perform(get("/api/compliance/rules")).andExpect(status().isUnauthorized());
        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/compliance/rules").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultJurisdiction").value("IN"))
                .andExpect(jsonPath("$.fallback").value("GENERIC"))
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("rules").size()).isGreaterThanOrEqualTo(5);
        for (JsonNode rule : body.get("rules")) {
            assertThat(rule.get("label").asText()).isNotBlank();
            assertThat(rule.get("badge").asText()).contains("\u2014");
        }
        mockMvc.perform(get("/api/compliance/rules").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$.rules[?(@.jurisdiction == 'US' && @.complaintType == 'USER_COMPLAINT')].deadlineHours").value(48))
                .andExpect(jsonPath("$.rules[?(@.jurisdiction == 'IN' && @.complaintType == 'USER_COMPLAINT')].deadlineHours").value(2))
                .andExpect(jsonPath("$.rules[?(@.jurisdiction == 'IN' && @.complaintType == 'GOVERNMENT_ORDER')].deadlineHours").value(3));
    }

    @Test
    void malformedJurisdictionIsRejectedBeforeAnalysis() throws Exception {
        mockMvc.perform(multipart("/api/compliance/complaints")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1}))
                        .param("complaintType", "USER_COMPLAINT")
                        .param("jurisdiction", "in-1; drop"))
                .andExpect(status().isBadRequest());
    }
}

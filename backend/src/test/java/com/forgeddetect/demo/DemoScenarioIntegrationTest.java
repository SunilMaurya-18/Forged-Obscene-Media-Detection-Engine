package com.forgeddetect.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 25 DoD (backend): one call seeds a linked story (flagged upload, appeal, signed report,
 * at-risk complaint, breach) through the real services; it is idempotent, its report verifies, the
 * public case and appeal screens open with the story's PINs, and a senior moderator can start over.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase25;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "app.security.extra-moderators=senior:senior:SENIOR_MODERATOR:core",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=true",
            "compliance.default-jurisdiction=IN",
            "demo.enabled=true",
            "report.signing.key-dir=${java.io.tmpdir}/fmd-phase25-keys"
        })
class DemoScenarioIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnalysisResultRepository results;

    @MockBean private MlClientService mlClientService;

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.62);
            ml.setExplicitScore(0.04);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setMediaType("image");
            return ml;
        });
    }

    @Test
    @Order(1)
    void oneClickSeedsTheWholeStoryOnceAndEveryPartOpens() throws Exception {
        JsonNode before = json(mockMvc.perform(get("/api/demo")).andExpect(status().isOk()));
        assertThat(before.get("enabled").asBoolean()).isTrue();
        assertThat(before.get("publicMode").asBoolean()).isFalse();
        assertThat(before.get("story").isNull()).as("GET never seeds").isTrue();
        assertThat(before.get("samples")).hasSize(4);
        assertThat(before.get("accounts").get(0).has("password")).as("logins only published in public mode").isFalse();

        JsonNode story = json(mockMvc.perform(post("/api/demo/story")).andExpect(status().isOk())).get("story");

        JsonNode flagged = story.get("flagged");
        assertThat(flagged.get("decision").asText()).isEqualTo("AUTO_FLAG");
        assertThat(flagged.get("decidedBy").asText()).isEqualTo("moderator");
        assertThat(flagged.get("appealStatus").asText()).isEqualTo("OPEN");
        assertThat(flagged.get("pin").asText()).matches("\\d{6}");

        JsonNode appeal = story.get("appeal");
        assertThat(appeal.get("status").asText()).isEqualTo("OPEN");
        assertThat(appeal.get("contestedBy").asText()).isEqualTo("moderator");
        assertThat(appeal.get("caseNumber").asText()).isEqualTo(flagged.get("noticeNumber").asText());

        JsonNode atRisk = story.get("atRisk");
        assertThat(atRisk.get("complaintType").asText()).isEqualTo("USER_COMPLAINT");
        assertThat(atRisk.get("slaStatus").asText()).isEqualTo("AT_RISK");
        assertThat(atRisk.get("caseNumber").asText()).startsWith("FMD-");

        JsonNode breached = story.get("breached");
        assertThat(breached.get("complaintType").asText()).isEqualTo("GOVERNMENT_ORDER");
        assertThat(breached.get("slaStatus").asText()).isEqualTo("BREACHED");
        assertThat(breached.get("escalatedAt").isNull()).isFalse();
        assertThat(breached.get("reference").asText()).isEqualTo(DemoScenarioService.GOVERNMENT_REFERENCE);

        String reportId = story.get("report").get("reportId").asText();
        assertThat(reportId).startsWith("RPT-" + flagged.get("id").asLong() + "-");

        // Phase 26: where the composite went, and the account it shares with the victim's report
        JsonNode investigation = story.get("investigation");
        assertThat(investigation.get("caseNumber").asText()).startsWith("INV-");
        assertThat(investigation.get("sightings").asInt()).as("one reported before the case was opened").isEqualTo(3);
        assertThat(investigation.get("platforms").asInt()).isEqualTo(3);
        JsonNode related = story.get("relatedInvestigation");
        assertThat(related.get("sightings").asInt()).isEqualTo(1);
        JsonNode caseView = json(mockMvc.perform(get("/api/investigations/" + investigation.get("caseNumber").asText())
                        .with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk()));
        assertThat(caseView.get("accounts").findValuesAsText("caseNumber")).contains(related.get("caseNumber").asText());
        mockMvc.perform(get("/api/investigations/" + investigation.get("caseNumber").asText())).andExpect(status().isUnauthorized());

        // Idempotent: a second visitor gets the same story
        JsonNode again = json(mockMvc.perform(post("/api/demo/story")).andExpect(status().isOk())).get("story");
        assertThat(again.get("flagged").get("id").asLong()).isEqualTo(flagged.get("id").asLong());

        // The signed report still matches the record, and the stored PDF is the one that was hashed
        JsonNode verdict = json(mockMvc.perform(get("/api/verify-report/" + reportId)).andExpect(status().isOk()));
        assertThat(verdict.get("status").asText()).as(verdict.toString()).isEqualTo("VALID");
        byte[] pdf = mockMvc.perform(get("/api/demo/report.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Report-Id", reportId))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(DemoSamples.sha256(pdf)).isEqualTo(story.get("report").get("sha256").asText());

        // The public screens open with the PINs the tour hands out
        mockMvc.perform(get("/api/complaints/" + atRisk.get("caseNumber").asText() + "/status")
                        .header("X-Case-Pin", atRisk.get("pin").asText()))
                .andExpect(status().isOk());
        JsonNode appealView = json(mockMvc.perform(get("/api/appeals/" + appeal.get("caseNumber").asText() + "/status")
                        .header("X-Case-Pin", appeal.get("pin").asText()))
                .andExpect(status().isOk()));
        assertThat(appealView.toString()).contains("OPEN");

        mockMvc.perform(get("/api/demo/samples/synthetic-stripes.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(get("/api/demo/samples/application.yml")).andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    void onlyModeratorsSeedExtraStoriesAndOnlySeniorsReset() throws Exception {
        long before = json(mockMvc.perform(post("/api/demo/story"))).get("story").get("flagged").get("id").asLong();

        mockMvc.perform(post("/api/demo/story").param("fresh", "true")).andExpect(status().isForbidden());
        JsonNode fresh = json(mockMvc.perform(post("/api/demo/story").param("fresh", "true").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk()));
        assertThat(fresh.get("story").get("flagged").get("id").asLong()).isNotEqualTo(before);

        mockMvc.perform(post("/api/demo/reset")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/demo/reset").with(httpBasic("moderator", "moderator"))).andExpect(status().isForbidden());

        JsonNode reset = json(mockMvc.perform(post("/api/demo/reset").with(httpBasic("senior", "senior")))
                .andExpect(status().isOk()));
        JsonNode story = reset.get("story");
        assertThat(story.get("resetAt").isNull()).isFalse();
        assertThat(results.existsById(before)).as("reset removes earlier items").isFalse();
        // 5 compliance seeds + the story's flagged upload, at-risk complaint and breach
        assertThat(results.count()).isEqualTo(8);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}

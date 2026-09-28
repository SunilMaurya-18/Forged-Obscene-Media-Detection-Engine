package com.forgeddetect.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.service.MlLabelResponse;
import com.forgeddetect.state.SharedState;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 25 DoD (backend, hosted demo): strangers can only upload the bundled synthetic samples (and
 * the labelled copies issued for them) on every upload route, may reset the demo at most once per
 * cooldown, and see the shared demo logins.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase25public;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            "compliance.seed-demo=false",
            "demo.enabled=true",
            "demo.public-mode=true",
            "demo.reset-cooldown=PT10M",
            "report.signing.key-dir=${java.io.tmpdir}/fmd-phase25-keys"
        })
class DemoPublicModeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DemoSamples samples;
    @Autowired private DemoProperties properties;
    @Autowired private SharedState state;

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
        when(mlClientService.label(any(), anyString(), any(), anyMap())).thenAnswer(inv -> {
            byte[] copy = ("labelled:" + UUID.randomUUID()).getBytes();
            return new MlLabelResponse(Base64.getEncoder().encodeToString(copy), "image/jpeg", null, null);
        });
    }

    @Test
    void onlySyntheticSamplesAndTheirLabelledCopiesCanBeUploaded() throws Exception {
        MockMultipartFile own = new MockMultipartFile("file", "holiday.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        mockMvc.perform(multipart("/api/analyze").file(own))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("demo_samples_only"));
        mockMvc.perform(multipart("/api/complaints").file(own).param("consent", "true").param("relationship", "SELF"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/verify").file(own)).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/analyze").file(own).header("X-API-Key", "fmd_live_nope")).andExpect(status().isForbidden());

        DemoSamples.Sample sample = samples.find("synthetic-stripes.jpg").orElseThrow();
        JsonNode analysed = json(mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", sample.name(), sample.mimeType(), sample.bytes())))
                .andExpect(status().isOk()));

        byte[] labelled = Base64.getDecoder().decode(analysed.get("labelledImage").asText());
        int verifyStatus = mockMvc.perform(multipart("/api/verify").file(new MockMultipartFile("file", "labelled.jpg", "image/jpeg", labelled)))
                .andReturn().getResponse().getStatus();
        assertThat(verifyStatus).as("a labelled copy issued by this demo is accepted").isNotEqualTo(403);
    }

    @Test
    void uploadsDrawFromOneInstanceWideBudget() throws Exception {
        int before = properties.getUploadsPerMinute();
        state.delete(DemoUploadGuard.BUDGET);
        properties.setUploadsPerMinute(2);
        try {
            DemoSamples.Sample sample = samples.find("synthetic-stripes.jpg").orElseThrow();
            MockMultipartFile file = new MockMultipartFile("file", sample.name(), sample.mimeType(), sample.bytes());
            MockMultipartFile own = new MockMultipartFile("file", "holiday.jpg", "image/jpeg", new byte[] {1, 2, 3});
            mockMvc.perform(multipart("/api/analyze").file(own)).andExpect(status().isForbidden());
            mockMvc.perform(multipart("/api/analyze").file(file)).andExpect(status().isOk());
            mockMvc.perform(multipart("/api/analyze").file(file)).andExpect(status().isOk());
            mockMvc.perform(multipart("/api/analyze").file(file))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.error").value("demo_rate_limited"));
            // Requests without files (status checks, the tour) never touch the budget
            mockMvc.perform(get("/api/demo")).andExpect(status().isOk());
        } finally {
            properties.setUploadsPerMinute(before);
            state.delete(DemoUploadGuard.BUDGET);
        }
    }

    @Test
    void visitorsMayResetOncePerCooldownAndSeeTheDemoLogins() throws Exception {
        JsonNode status = json(mockMvc.perform(get("/api/demo")).andExpect(status().isOk()));
        assertThat(status.get("publicMode").asBoolean()).isTrue();
        assertThat(status.get("accounts").get(0).get("username").asText()).isEqualTo("moderator");
        assertThat(status.get("accounts").get(0).get("password").asText()).isEqualTo("moderator");

        JsonNode reset = json(mockMvc.perform(post("/api/demo/reset")).andExpect(status().isOk()));
        assertThat(reset.get("story").get("breached").get("slaStatus").asText()).isEqualTo("BREACHED");
        assertThat(reset.get("nextResetAt").isNull()).as("public demo resets itself").isFalse();

        mockMvc.perform(post("/api/demo/reset"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("demo_reset_cooldown"));
        // Seniors aren't held by the visitor cooldown
        mockMvc.perform(post("/api/demo/reset").with(httpBasic("senior", "senior"))).andExpect(status().isOk());
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}

package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.forgeddetect.model.ApiClient;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ApiClientRepository;
import com.forgeddetect.service.ApiKeyService;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Phase 16 DoD: valid key succeeds, missing/invalid key is 401, exceeding the rate limit is 429. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase16;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            "api.rate-limits.FREE=3",
            "api.rate-limits.PRO=50",
            "api.bootstrap-key=fmd_live_test_demo_key_0123456789"
        })
class ApiKeyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApiClientRepository clients;
    @Autowired private AnalysisResultRepository analyses;

    @MockitoBean private MlClientService mlClientService;

    private static final MockMultipartFile UPLOAD =
            new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01});

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.91);
            ml.setExplicitScore(0.02);
            ml.setPhash(UUID.randomUUID().toString().substring(0, 16));
            ml.setHeatmap("aGVhdG1hcA==");
            ml.setContentHash("sha256:" + UUID.randomUUID());
            ml.setProvenanceId(UUID.randomUUID().toString());
            return ml;
        });
    }

    private JsonNode createClient(String name, String plan) throws Exception {
        String body = mockMvc.perform(post("/api/admin/clients")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"plan\":\"" + plan + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static MockMultipartHttpServletRequestBuilder analyze() {
        return multipart("/api/v1/analyze").file(UPLOAD);
    }

    @Test
    void validKeySucceedsAndIsStoredOnlyAsAHash() throws Exception {
        JsonNode created = createClient("Acme Social", "PRO");
        String key = created.get("apiKey").asText();
        assertThat(key).startsWith("fmd_live_").hasSizeGreaterThan(40);
        assertThat(created.get("keyPrefix").asText()).isEqualTo(key.substring(0, 15));

        mockMvc.perform(analyze().header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "50"))
                .andExpect(header().string("X-RateLimit-Remaining", "49"))
                .andExpect(jsonPath("$.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.forgeryScore").value(0.91))
                .andExpect(jsonPath("$.provenanceId").isNotEmpty())
                .andExpect(jsonPath("$.heatmap").doesNotExist())
                .andExpect(jsonPath("$.labelledImage").doesNotExist());

        mockMvc.perform(analyze().param("include", "heatmap").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heatmap").value("aGVhdG1hcA=="));

        ApiClient row = clients.findById(created.get("id").asLong()).orElseThrow();
        assertThat(row.getKeyHash()).isEqualTo(ApiKeyService.hash(key)).doesNotContain(key);
        assertThat(row.getRequestCount()).isEqualTo(2);
        assertThat(analyses.findAll().stream().filter(a -> row.getId().equals(a.getApiClientId())).count())
                .isEqualTo(2);

        String list = mockMvc.perform(get("/api/admin/clients").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("Acme Social").doesNotContain(key).doesNotContain(row.getKeyHash());
    }

    @Test
    void missingInvalidRevokedOrModeratorCredentialsAre401() throws Exception {
        mockMvc.perform(analyze())
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("missing_api_key"));
        mockMvc.perform(analyze().header("X-API-Key", "fmd_live_not-a-real-key-0000000000000000000"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));
        mockMvc.perform(analyze().with(httpBasic("moderator", "moderator")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/usage")).andExpect(status().isUnauthorized());

        JsonNode created = createClient("Revoked Co", "FREE");
        String key = created.get("apiKey").asText();
        mockMvc.perform(analyze().header("X-API-Key", key)).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/clients/" + created.get("id").asLong() + "/revoke")
                        .with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(analyze().header("X-API-Key", key)).andExpect(status().isUnauthorized());

        // An API key never opens moderator endpoints.
        mockMvc.perform(get("/api/queue").header("X-API-Key", key)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/clients")).andExpect(status().isUnauthorized());
    }

    @Test
    void exceedingTheRateLimitReturns429AndUsageReportsIt() throws Exception {
        String key = createClient("Burst Media", "FREE").get("apiKey").asText();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(analyze().header("X-API-Key", key)).andExpect(status().isOk());
        }
        mockMvc.perform(analyze().header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.error").value("rate_limited"));

        mockMvc.perform(get("/api/v1/usage").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client.name").value("Burst Media"))
                .andExpect(jsonPath("$.client.plan").value("FREE"))
                .andExpect(jsonPath("$.rateLimit.limitPerMinute").value(3))
                .andExpect(jsonPath("$.rateLimit.remaining").value(0))
                .andExpect(jsonPath("$.requests.total").value(3))
                .andExpect(jsonPath("$.requests.throttled").value(1))
                .andExpect(jsonPath("$.analyses.total").value(3))
                .andExpect(jsonPath("$.analyses.last24h").value(3))
                .andExpect(jsonPath("$.analyses.byDecision.AUTO_FLAG").value(3));
    }

    @Test
    void rotationInvalidatesTheOldKeyAndDemoKeyIsSeeded() throws Exception {
        JsonNode created = createClient("Rotate Inc", "PRO");
        String oldKey = created.get("apiKey").asText();
        String newKey = objectMapper.readTree(mockMvc.perform(
                        post("/api/admin/clients/" + created.get("id").asLong() + "/rotate")
                                .with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        assertThat(newKey).isNotEqualTo(oldKey);
        mockMvc.perform(analyze().header("X-API-Key", oldKey)).andExpect(status().isUnauthorized());
        mockMvc.perform(analyze().header("X-API-Key", newKey)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/usage").header("X-API-Key", "fmd_live_test_demo_key_0123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client.name").value("Bootstrap Client"));
    }

    @Test
    void badRequestsGetJsonErrors() throws Exception {
        String key = createClient("Validation Ltd", "PRO").get("apiKey").asText();
        mockMvc.perform(multipart("/api/v1/analyze").header("X-API-Key", key))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("file_required"));
        mockMvc.perform(analyze().param("include", "everything").header("X-API-Key", key))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_include"));
        mockMvc.perform(post("/api/admin/clients")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Validation Ltd\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A client named 'Validation Ltd' already exists"));

        when(mlClientService.infer(any(), anyString(), any())).thenThrow(new IllegalStateException("ml down"));
        mockMvc.perform(analyze().header("X-API-Key", key))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("analysis_unavailable"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ml down"))));
    }
}

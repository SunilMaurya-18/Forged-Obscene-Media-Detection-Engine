package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.AnalysisResult;
import com.forgeddetect.observability.RequestIdFilter;
import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import com.forgeddetect.service.MlRejectedException;
import com.forgeddetect.signing.ReportTimestampService;
import com.forgeddetect.support.FakeTsa;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Phase 24 DoD: key rotation keeps old reports verifiable, reports carry verifiable RFC 3161 timestamps,
 * {@code /actuator/prometheus} exposes decision and latency metrics, and request IDs flow to the ML service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase24;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false"
        })
class EngineeringMaturityIntegrationTest {

    private static final FakeTsa TSA = startTsa();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("report.timestamp.url", TSA::url);
        registry.add("report.signing.key-dir", () -> System.getProperty("java.io.tmpdir") + "/fmd-phase24-" + UUID.randomUUID());
    }

    @AfterAll
    static void stopTsa() {
        TSA.close();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnalysisResultRepository repository;
    @Autowired private ReportTimestampService timestamps;
    @Autowired private ObjectProvider<WebClient.Builder> webClients;

    @MockitoBean private MlClientService mlClientService;

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.93);
            ml.setExplicitScore(0.05);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            ml.setProvenanceId(UUID.randomUUID().toString());
            ml.setContentHash("sha256:" + "cd".repeat(32));
            return ml;
        });
    }

    @Test
    void reportsCarryAVerifiableTimestampAndTamperingWithItIsDetected() throws Exception {
        long id = analyze();
        String reportId = report(id);
        JsonNode v = verify(reportId);
        assertThat(v.get("status").asText()).isEqualTo("VALID");
        assertThat(v.at("/timestamp/status").asText()).isEqualTo("VERIFIED");
        assertThat(v.at("/timestamp/authority").asText()).contains("FMD Test TSA");
        assertThat(v.get("message").asText()).contains("Timestamped");

        AnalysisResult item = repository.findById(id).orElseThrow();
        item.setReportTimestampToken(timestamps.stamp("other bytes".getBytes(StandardCharsets.UTF_8)).stamp().tokenBase64());
        repository.save(item);
        JsonNode tampered = verify(reportId);
        assertThat(tampered.get("status").asText()).isEqualTo("TAMPERED");
        assertThat(tampered.get("changedFields").toString()).contains("timestamp");
    }

    @Test
    void rotationNeedsASeniorAndOldReportsStillVerify() throws Exception {
        long id = analyze();
        String oldReport = report(id);
        String oldKey = verify(oldReport).get("keyId").asText();

        mockMvc.perform(post("/api/admin/signing-keys/rotate").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isForbidden());
        JsonNode rotated = objectMapper.readTree(mockMvc.perform(post("/api/admin/signing-keys/rotate")
                        .with(httpBasic("senior", "senior")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(rotated.get("previousKeyId").asText()).isEqualTo(oldKey);
        String newKey = rotated.get("activeKeyId").asText();
        assertThat(newKey).isNotEqualTo(oldKey);

        JsonNode old = verify(oldReport);
        assertThat(old.get("status").asText()).isEqualTo("VALID");
        assertThat(old.get("keyStatus").asText()).isEqualTo("RETIRED");

        long second = analyze();
        JsonNode fresh = verify(report(second));
        assertThat(fresh.get("keyId").asText()).isEqualTo(newKey);
        assertThat(fresh.get("keyStatus").asText()).isEqualTo("ACTIVE");

        JsonNode keys = objectMapper.readTree(mockMvc.perform(get("/api/verify-report/keys"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(keys.get("activeKeyId").asText()).isEqualTo(newKey);
        assertThat(keys.get("keys").toString()).contains(oldKey, newKey, "RETIRED");
    }

    @Test
    void prometheusExposesDecisionAndLatencyMetrics() throws Exception {
        analyze();
        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("fmd_decisions_total{", "source=\"model\"", "decision=\"AUTO_FLAG\"");
        assertThat(metrics).contains("http_server_requests_seconds_bucket", "uri=\"/api/analyze\"");
        assertThat(metrics).contains("fmd_sla_open{", "fmd_review_queue", "fmd_model_score_bucket");
    }

    @Test
    void requestIdsAreEchoedAndForwardedToTheMlService() throws Exception {
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "edge-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "edge-123"));
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "bad id\nwith newline"))
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));

        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer ml = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ml.createContext("/infer", ex -> {
            seen.set(ex.getRequestHeaders().getFirst(RequestIdFilter.HEADER));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        ml.start();
        try {
            MDC.put(RequestIdFilter.MDC_KEY, "req-abc");
            webClients.getObject().baseUrl("http://127.0.0.1:" + ml.getAddress().getPort()).build()
                    .post().uri("/infer").retrieve().toBodilessEntity().block();
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
            ml.stop(0);
        }
        assertThat(seen.get()).isEqualTo("req-abc");
    }

    @Test
    void aRejectedUploadIsA400AndAnUnexpectedFailureKeepsItsRequestId() throws Exception {
        doThrow(new MlRejectedException("ML service /infer failed: HTTP 400", null))
                .when(mlClientService).infer(any(), anyString(), any());
        mockMvc.perform(multipart("/api/analyze").file(new MockMultipartFile("file", "x.png", "image/png", new byte[] {9})))
                .andExpect(status().isBadRequest());

        doThrow(new IllegalStateException("ML service down")).when(mlClientService).infer(any(), anyString(), any());
        mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1}))
                        .header(RequestIdFilter.HEADER, "fail-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.HEADER, "fail-1"));
    }

    private long analyze() throws Exception {
        String body = mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String report(long id) throws Exception {
        return mockMvc.perform(multipart("/api/queue/" + id + "/report").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Report-Id");
    }

    private JsonNode verify(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/verify-report/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static FakeTsa startTsa() {
        try {
            return new FakeTsa();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}

package com.forgeddetect.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.model.KnownHashSource;
import com.forgeddetect.repository.KnownHashRepository;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 18 DoD: a seeded hash matches, an unseeded one doesn't, and only a hash is ever accepted. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase18;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
            "prescreen.seed-hashes=c2423d3d3b783cc6",
            "prescreen.phash-max-distance=8"
        })
class PrescreenIntegrationTest {

    private static final String SEEDED = "c2423d3d3b783cc6";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private KnownHashRepository knownHashes;

    @MockBean private MlClientService mlClientService;

    private ResultActions prescreen(String json) throws Exception {
        return mockMvc.perform(post("/api/prescreen").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void seededHashMatchesWithoutLoggingIn() throws Exception {
        assertThat(knownHashes.countBySource(KnownHashSource.INTERNAL_CONFIRMED)).isGreaterThanOrEqualTo(1);
        prescreen("{\"hash\":\"" + SEEDED + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value(true))
                .andExpect(jsonPath("$.algorithm").value("phash"))
                // The answer never discloses how close it was or where the hash came from
                .andExpect(jsonPath("$.distance").doesNotExist())
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void nearDuplicateMatchesAndUnrelatedHashDoesNot() throws Exception {
        // 3 bits flipped (a re-encode or small crop), upper-case input
        prescreen("{\"hash\":\"C2423D3D3B783CC1\",\"algorithm\":\"phash\"}").andExpect(jsonPath("$.match").value(true));
        prescreen("{\"hash\":\"3dbdc2c2c487c339\"}").andExpect(status().isOk()).andExpect(jsonPath("$.match").value(false));
    }

    @Test
    void onlyAHashIsAccepted() throws Exception {
        prescreen("{\"hash\":\"" + SEEDED + "\",\"image\":\"/9j/4AAQSkZJRg==\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("only_hash_accepted"));
        prescreen("{\"hash\":\"not-a-hash\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_hash"));
        prescreen("{\"hash\":\"" + SEEDED + "\",\"algorithm\":\"md5\"}").andExpect(status().isBadRequest());
        prescreen("{\"hash\":1234}").andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/prescreen").file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2})))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void moderatorTakedownAddsTheHashToTheKnownList() throws Exception {
        String phash = "0f0f0f0f12345678";
        prescreen("{\"hash\":\"" + phash + "\"}").andExpect(jsonPath("$.match").value(false));

        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(0.62);
        ml.setExplicitScore(0.05);
        ml.setPhash(phash);
        ml.setHeatmap("");
        ml.setProvenanceId(UUID.randomUUID().toString());
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);
        long id = objectMapper.readTree(mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3})))
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/queue/" + id + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"AUTO_FLAG\"}"))
                .andExpect(status().isOk());
        prescreen("{\"hash\":\"" + phash + "\"}").andExpect(jsonPath("$.match").value(true));
    }

    @Test
    void stopNciiImportIsModeratorOnlyAndMatchesByPdq() throws Exception {
        String pdq = "ab".repeat(32);
        String body = "{\"source\":\"STOPNCII_IMPORT\",\"algorithm\":\"pdq\",\"hashes\":[\"" + pdq + "\",\"zz\"]}";
        mockMvc.perform(post("/api/admin/known-hashes/import").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/known-hashes/import")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.invalid").value(1));
        prescreen("{\"hash\":\"" + pdq + "\",\"algorithm\":\"pdq\"}").andExpect(jsonPath("$.match").value(true));
        // Same bits as a pHash-length prefix never cross-match another algorithm's list
        prescreen("{\"hash\":\"" + pdq.substring(0, 16) + "\"}").andExpect(jsonPath("$.match").value(false));
        mockMvc.perform(get("/api/admin/known-hashes").with(httpBasic("moderator", "moderator")))
                .andExpect(jsonPath("$.bySource.STOPNCII_IMPORT").value(1));
    }
}

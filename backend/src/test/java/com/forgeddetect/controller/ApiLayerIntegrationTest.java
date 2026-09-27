package com.forgeddetect.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase8;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "decision.forgery-auto-flag=0.85",
            "decision.explicit-auto-flag=0.85",
            "decision.forgery-review=0.50",
            "decision.explicit-review=0.50"
        })
class ApiLayerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MlClientService mlClientService;

    @Test
    void analyzePersistsAndModeratorCanListQueue() throws Exception {
        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(0.62);
        ml.setExplicitScore(0.10);
        ml.setPhash("abcd1234abcd1234");
        ml.setHeatmap("aGVhdG1hcA==");
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);

        MockMultipartFile file =
                new MockMultipartFile("file", "sample.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01});

        mockMvc.perform(multipart("/api/analyze").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"));

        mockMvc.perform(get("/api/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decision").value("REVIEW"));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.thresholds.forgeryReview").value(0.50))
                .andExpect(jsonPath("$.thresholds.explicitAutoFlag").value(0.85));
    }

    @Test
    void queueDecisionRequiresModerator() throws Exception {
        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(0.55);
        ml.setExplicitScore(0.0);
        ml.setPhash("deadbeefdeadbeef");
        ml.setHeatmap("");
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);

        MockMultipartFile file =
                new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3});

        String id =
                mockMvc.perform(multipart("/api/analyze").file(file))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1");

        mockMvc.perform(
                        post("/api/queue/" + id + "/decision")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/queue/" + id + "/decision")
                                .with(httpBasic("moderator", "moderator"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"));
    }
}

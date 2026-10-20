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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 22: moderator decisions are compared with the model's original routing in /api/stats. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase22;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
class ModerationAgreementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MlClientService mlClientService;

    private long analyze(double forgery, String phash) throws Exception {
        MlInferResponse ml = new MlInferResponse();
        ml.setForgeryScore(forgery);
        ml.setExplicitScore(0.02);
        ml.setPhash(phash);
        ml.setHeatmap("");
        when(mlClientService.infer(any(), anyString(), any())).thenReturn(ml);
        String body = mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", phash + ".jpg", "image/jpeg", phash.getBytes())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(body.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void decide(long id, String decision) throws Exception {
        mockMvc.perform(post("/api/queue/" + id + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"" + decision + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value(decision))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
    }

    @Test
    void statsCompareModeratorDecisionsWithModelRouting() throws Exception {
        long cleared = analyze(0.62, "1111222233334444");
        long confirmed = analyze(0.60, "5555666677778888");
        long overturned = analyze(0.95, "9999aaaabbbbcccc");
        analyze(0.55, "ddddeeeeffff0000");

        decide(cleared, "ALLOW");
        decide(confirmed, "AUTO_FLAG");

        // Phase 23: overturning an AUTO_FLAG is only a proposal until a second moderator confirms it
        mockMvc.perform(post("/api/queue/" + overturned + "/decision")
                        .with(httpBasic("moderator", "moderator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("AUTO_FLAG"))
                .andExpect(jsonPath("$.pendingDecision").value("ALLOW"));
        mockMvc.perform(post("/api/queue/" + overturned + "/second-review")
                        .with(httpBasic("reviewer", "reviewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());

        mockMvc.perform(get("/api/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelDecision").value("REVIEW"))
                .andExpect(jsonPath("$[0].reviewedAt").value(Matchers.nullValue()));

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderation.reviewed").value(3))
                .andExpect(jsonPath("$.moderation.review.confirmed").value(1))
                .andExpect(jsonPath("$.moderation.review.cleared").value(1))
                .andExpect(jsonPath("$.moderation.review.confirmRate").value(0.5))
                .andExpect(jsonPath("$.moderation.autoFlag.upheld").value(0))
                .andExpect(jsonPath("$.moderation.autoFlag.overturned").value(1))
                .andExpect(jsonPath("$.moderation.autoFlag.overturnRate").value(1.0))
                .andExpect(jsonPath("$.moderation.allow.takenDown").value(0))
                .andExpect(jsonPath("$.moderation.precisionProxy").value(Matchers.closeTo(1.0 / 3.0, 1e-9)));
    }
}

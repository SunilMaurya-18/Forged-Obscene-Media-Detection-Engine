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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 23 DoD: the exposure limit forces a break, and reveals are audit-logged. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase23wellbeing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "app.security.extra-moderators=reviewer:reviewer:MODERATOR:core",
            "compliance.sla.check-interval=PT1H",
            "compliance.seed-demo=false",
            "decision.forgery-review=0.5",
            "decision.forgery-auto-flag=0.85",
            "wellbeing.exposure-limit=3",
            "wellbeing.break-duration=PT15M",
            "wellbeing.support-url=/support"
        })
class ModeratorWellbeingIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T09:00:00Z");

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

    @MockBean private MlClientService mlClientService;

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.6);
            ml.setExplicitScore(0.05);
            ml.setPhash(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            ml.setHeatmap("");
            return ml;
        });
    }

    @Test
    void exposureLimitForcesABreakAndRevealsAreLogged() throws Exception {
        long a = analyze();
        long b = analyze();
        long c = analyze();
        long d = analyze();

        JsonNode me = json(mockMvc.perform(get("/api/moderation/me").with(httpBasic("moderator", "moderator"))));
        assertThat(me.get("wellbeing").get("limit").asInt()).isEqualTo(3);
        assertThat(me.get("wellbeing").get("supportUrl").asText()).isEqualTo("/support");

        // Revealing and then deciding the same item is one exposure, not two
        mockMvc.perform(post("/api/queue/" + a + "/reveal").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wellbeing.exposures").value(1));
        decide(a).andExpect(status().isOk()).andExpect(jsonPath("$.wellbeing.exposures").value(1));
        decide(b).andExpect(status().isOk()).andExpect(jsonPath("$.wellbeing.remaining").value(1));
        decide(c).andExpect(status().isOk()).andExpect(jsonPath("$.wellbeing.onBreak").value(true));

        decide(d).andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("break_required"))
                .andExpect(jsonPath("$.wellbeing.breakEndsAt").value(T0.plus(Duration.ofMinutes(15)).toString()));
        mockMvc.perform(post("/api/queue/" + d + "/reveal").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isLocked());
        // A report renders the media panels, so it's exposure too
        mockMvc.perform(multipart("/api/queue/" + d + "/report").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isLocked());

        // Other moderators are unaffected
        decide(d, "reviewer").andExpect(status().isOk());

        JsonNode log = json(mockMvc.perform(get("/api/queue/" + a + "/audit").with(httpBasic("reviewer", "reviewer"))));
        assertThat(log.toString()).contains("\"actor\":\"moderator\",\"action\":\"MEDIA_REVEALED\"");

        clock.advance(Duration.ofMinutes(15));
        JsonNode after = json(mockMvc.perform(get("/api/moderation/me").with(httpBasic("moderator", "moderator"))));
        assertThat(after.get("wellbeing").get("onBreak").asBoolean()).isFalse();
        assertThat(after.get("wellbeing").get("exposures").asInt()).isZero();
        long e = analyze();
        decide(e).andExpect(status().isOk());

        mockMvc.perform(post("/api/moderation/break").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onBreak").value(true));
    }

    private long analyze() throws Exception {
        return json(mockMvc.perform(multipart("/api/analyze")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())).get("id").asLong();
    }

    private ResultActions decide(long id) throws Exception {
        return decide(id, "moderator");
    }

    private ResultActions decide(long id, String user) throws Exception {
        return mockMvc.perform(post("/api/queue/" + id + "/decision").with(httpBasic(user, user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ALLOW\"}"));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}

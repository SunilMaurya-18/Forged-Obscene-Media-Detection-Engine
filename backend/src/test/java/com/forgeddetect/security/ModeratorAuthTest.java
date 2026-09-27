package com.forgeddetect.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.forgeddetect.service.MlClientService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase8auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator"
        })
class ModeratorAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MlClientService mlClientService;

    @Test
    void unauthenticatedQueueDecisionIsUnauthorized() throws Exception {
        mockMvc.perform(
                        post("/api/queue/1/decision")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"ALLOW\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedQueueListIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/queue")).andExpect(status().isUnauthorized());
    }

    @Test
    void moderatorCanAccessQueue() throws Exception {
        mockMvc.perform(get("/api/queue").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/queue").with(httpBasic("moderator", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithBasicReturnsModeratorProfile() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(httpBasic("moderator", "moderator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("moderator"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_MODERATOR"));
    }
}

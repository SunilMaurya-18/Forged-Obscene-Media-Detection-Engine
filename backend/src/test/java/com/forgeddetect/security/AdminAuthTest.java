package com.forgeddetect.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.forgeddetect.service.MlClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:adminauth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=owner",
            "app.security.moderator-password=owner-password-12",
            "app.security.moderator-role=ADMIN",
            "app.security.extra-moderators="
        })
class AdminAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MlClientService mlClientService;

    @Test
    void adminGetsSeniorAndAdminRoles() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(httpBasic("owner", "owner-password-12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.roles", containsInAnyOrder(
                        "ROLE_MODERATOR", "ROLE_SENIOR_MODERATOR", "ROLE_ADMIN")));
    }

    @Test
    void adminCanListAccountsAndOverview() throws Exception {
        mockMvc.perform(get("/api/admin/overview").with(httpBasic("owner", "owner-password-12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.admins").value(1));
        mockMvc.perform(get("/api/admin/accounts").with(httpBasic("owner", "owner-password-12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("owner"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));
    }

    @Test
    void adminCanCreateModerator() throws Exception {
        mockMvc.perform(
                        post("/api/admin/accounts")
                                .with(httpBasic("owner", "owner-password-12"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"reviewer1\",\"password\":\"reviewer-pass-12\",\"role\":\"MODERATOR\",\"team\":\"core\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("reviewer1"))
                .andExpect(jsonPath("$.role").value("MODERATOR"));
    }
}

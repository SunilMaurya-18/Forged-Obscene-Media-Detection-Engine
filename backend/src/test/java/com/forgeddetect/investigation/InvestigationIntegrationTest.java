package com.forgeddetect.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeddetect.service.MlClientService;
import com.forgeddetect.service.MlInferResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 26 DoD: partner and moderator sightings link to open cases by perceptual hash or provenance
 * ID, whichever came first; distant copies don't; accounts are stored only as platform-scoped
 * pseudonyms and show up across cases; partners never learn about matches; moderators only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phase26;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "ml.service.url=http://localhost:9",
            "app.security.moderator-username=moderator",
            "app.security.moderator-password=moderator",
            "api.bootstrap-key=" + InvestigationIntegrationTest.PARTNER_KEY,
            "api.rate-limits.FREE=1000",
            "prescreen.phash-max-distance=8"
        })
class InvestigationIntegrationTest {

    static final String PARTNER_KEY = "fmd_live_test_demo_key_0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SightingRepository sightings;
    @Autowired private AccountPseudonyms pseudonyms;

    @MockitoBean private MlClientService mlClientService;

    private final AtomicReference<String> nextPhash = new AtomicReference<>();

    @BeforeEach
    void stubMl() {
        when(mlClientService.infer(any(), anyString(), any())).thenAnswer(inv -> {
            MlInferResponse ml = new MlInferResponse();
            ml.setForgeryScore(0.91);
            ml.setExplicitScore(0.02);
            ml.setPhash(nextPhash.get());
            ml.setHeatmap("");
            ml.setMediaType("image");
            return ml;
        });
    }

    @Test
    void sightingsBeforeAndAfterOpeningLinkButDistantOnesDoNot() throws Exception {
        String hash = randomHash();
        long id = upload(hash).get("id").asLong();

        // Reported before any case exists: stored, and the partner learns nothing about matches
        String account = "acct-" + RANDOM.nextInt(1_000_000);
        JsonNode first = json(partner(sighting(flip(hash, 2), "pixelgram", "pg-1001", account)).andExpect(status().isCreated()));
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(first.get("accountRecorded").asBoolean()).isTrue();
        assertThat(first.toString()).doesNotContain(account).doesNotContainIgnoringCase("investigation").doesNotContain("linked");

        JsonNode again = json(partner(sighting(flip(hash, 2), "PixelGram", "pg-1001", account)).andExpect(status().isOk()));
        assertThat(again.get("duplicate").asBoolean()).isTrue();
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());

        JsonNode opened = json(moderator(post("/api/investigations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", id, "title", "Spread check")))).andExpect(status().isCreated()));
        String caseNumber = opened.get("caseNumber").asText();
        assertThat(caseNumber).matches("INV-[A-Z0-9]{4}-[A-Z0-9]{4}");
        assertThat(opened.get("status").asText()).isEqualTo("OPEN");
        assertThat(opened.get("sightings")).hasSize(1);
        assertThat(opened.get("sightings").get(0).get("via").asText()).isEqualTo("PHASH");
        assertThat(opened.get("sightings").get(0).get("distance").asInt()).isEqualTo(2);

        // Reported after: linked on arrival. Same account ID on another platform is a different account.
        partner(sighting(hash, "chirp", "ch-77", account)).andExpect(status().isCreated());
        // Far outside the threshold: kept, not linked
        partner(sighting(invert(hash), "chirp", "ch-78", account)).andExpect(status().isCreated());

        JsonNode detail = json(moderator(get("/api/investigations/" + caseNumber.toLowerCase())).andExpect(status().isOk()));
        assertThat(detail.get("sightings")).hasSize(2);
        assertThat(detail.get("counts").get("platforms").asInt()).isEqualTo(2);
        assertThat(detail.get("accounts")).hasSize(2);
        assertThat(detail.get("evidence").get(0).get("analysisId").asLong()).isEqualTo(id);
        assertThat(detail.toString()).doesNotContain(account);
        assertThat(texts(detail.get("timeline"), "kind")).contains("case", "sighting");

        List<Sighting> stored = sightings.findAll().stream().filter(s -> s.getPostRef().startsWith("pg-1001")
                || s.getPostRef().startsWith("ch-7")).toList();
        assertThat(stored).hasSize(3).allSatisfy(s -> {
            assertThat(s.getAccountKey()).matches("[0-9a-f]{64}").isNotEqualTo(account);
            assertThat(s.getPlatform()).isIn("pixelgram", "chirp");
        });
        assertThat(stored).filteredOn(s -> s.getPlatform().equals("pixelgram"))
                .allSatisfy(s -> assertThat(s.getAccountKey()).isEqualTo(pseudonyms.of("pixelgram", account)));
        assertThat(pseudonyms.of("pixelgram", account)).isNotEqualTo(pseudonyms.of("chirp", account));
    }

    @Test
    void anAccountSeenInTwoCasesIsLinkedFromBoth() throws Exception {
        String firstHash = randomHash();
        String secondHash = randomHash();
        String caseA = open(upload(firstHash).get("id").asLong());
        String caseB = open(upload(secondHash).get("id").asLong());

        partner(sighting(firstHash, "vidshare", "vs-1", "shared-uploader")).andExpect(status().isCreated());
        partner(sighting(secondHash, "vidshare", "vs-2", "shared-uploader")).andExpect(status().isCreated());

        JsonNode a = json(moderator(get("/api/investigations/" + caseA)));
        assertThat(a.get("accounts")).hasSize(1);
        assertThat(a.get("accounts").get(0).get("totalSightings").asInt()).isEqualTo(2);
        assertThat(texts(a.get("accounts").get(0).get("otherCases"), "caseNumber")).containsExactly(caseB);
        JsonNode b = json(moderator(get("/api/investigations/" + caseB)));
        assertThat(texts(b.get("accounts").get(0).get("otherCases"), "caseNumber")).containsExactly(caseA);
        assertThat(a.get("accounts").get(0).get("account").asText())
                .isEqualTo(b.get("accounts").get(0).get("account").asText()).hasSize(12);
    }

    @Test
    void closedCasesDoNotLinkAndReopeningCatchesUp() throws Exception {
        String hash = randomHash();
        long id = upload(hash).get("id").asLong();
        String caseNumber = open(id);

        moderator(post("/api/investigations/" + caseNumber + "/close").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", "Handed to platform trust team")))).andExpect(status().isOk());
        partner(sighting(hash, "pixelgram", "pg-closed-1", null)).andExpect(status().isCreated());
        JsonNode closed = json(moderator(get("/api/investigations/" + caseNumber)));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("sightings")).isEmpty();
        assertError(moderator(post("/api/investigations/" + caseNumber + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("text", "late note")))), 409, "case_closed");

        JsonNode reopened = json(moderator(post("/api/investigations/" + caseNumber + "/reopen")).andExpect(status().isOk()));
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("sightings")).hasSize(1);
        assertThat(reopened.get("accounts")).as("no account ID was sent").isEmpty();
        assertError(moderator(post("/api/investigations/" + caseNumber + "/reopen")), 409, "already_open");
        assertThat(texts(reopened.get("timeline"), "type")).contains("OPENED", "CLOSED", "REOPENED", "SIGHTING_LINKED");
    }

    @Test
    void provenanceIdsLinkAndModeratorsCanRecordSightings() throws Exception {
        JsonNode item = upload(randomHash());
        String caseNumber = open(item.get("id").asLong());

        Map<String, Object> byProvenance = Map.of(
                "provenanceId", item.get("provenanceId").asText(), "platform", "chirp", "postRef", "ch-prov-1");
        partner(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON).content(body(byProvenance)))
                .andExpect(status().isCreated());

        JsonNode recorded = json(moderator(post("/api/investigations/" + caseNumber + "/sightings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("platform", "Vid Share", "postRef", "vs-told-by-victim", "accountId", "uploader-9"))))
                .andExpect(status().isCreated()));
        JsonNode rows = recorded.get("sightings");
        assertThat(rows).hasSize(2);
        assertThat(texts(rows, "via")).containsExactlyInAnyOrder("PROVENANCE", "PROVENANCE");
        assertThat(texts(rows, "source")).containsExactlyInAnyOrder("PARTNER_API", "MODERATOR");
        assertThat(texts(rows, "platform")).contains("vid-share");
        assertThat(texts(rows, "reporter")).contains("moderator", "Bootstrap Client");
    }

    @Test
    void itemsNotesAndListing() throws Exception {
        long first = upload(randomHash()).get("id").asLong();
        long second = upload(randomHash()).get("id").asLong();
        String caseNumber = open(first);

        JsonNode added = json(moderator(post("/api/investigations/" + caseNumber + "/items").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", second)))).andExpect(status().isOk()));
        assertThat(added.get("evidence")).hasSize(2);
        assertError(moderator(post("/api/investigations/" + caseNumber + "/items").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", second)))), 409, "already_in_case");
        assertError(moderator(post("/api/investigations/" + caseNumber + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("text", "  ")))), 400, "note_required");
        JsonNode noted = json(moderator(post("/api/investigations/" + caseNumber + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("text", "Checked the partner list")))).andExpect(status().isOk()));
        assertThat(texts(noted.get("timeline"), "type")).contains("NOTE_ADDED", "ITEM_ADDED");

        JsonNode forItem = json(moderator(get("/api/investigations").param("analysisId", String.valueOf(second))));
        assertThat(texts(forItem, "caseNumber")).containsExactly(caseNumber);
        assertThat(forItem.get(0).get("items").asInt()).isEqualTo(2);
        assertError(moderator(get("/api/investigations").param("status", "pending")), 400, "invalid_status");
        assertError(moderator(get("/api/investigations/INV-0000-0000")), 404, "case_not_found");
        assertError(moderator(post("/api/investigations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", 999_999)))), 404, "analysis_not_found");
    }

    @Test
    void partnerInputIsValidated() throws Exception {
        String hash = randomHash();
        assertError(partner(sighting(hash, "pixelgram", "https://pixelgram.example/p/1", "a")), 400, "invalid_sighting");
        assertError(partner(sighting(hash, "pixelgram", "@someone", "a")), 400, "invalid_sighting");
        assertError(partner(sighting("not-a-hash", "pixelgram", "pg-9", "a")), 400, "invalid_sighting");
        assertError(partner(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("platform", "pixelgram", "postRef", "pg-9")))), 400, "invalid_sighting");
        assertError(partner(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phash", hash, "postRef", "pg-9", "observedAt",
                        Instant.now().plus(1, ChronoUnit.HOURS).toString())))), 400, "invalid_sighting");

        // No platform: the partner's own name stands in
        JsonNode defaulted = json(partner(post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("phash", hash, "postRef", "own-1")))).andExpect(status().isCreated()));
        assertThat(defaulted.get("platform").asText()).isEqualTo("bootstrap-client");
        assertThat(defaulted.get("accountRecorded").asBoolean()).isFalse();
    }

    @Test
    void onlyModeratorsReadInvestigationsAndOnlyPartnersReport() throws Exception {
        mockMvc.perform(get("/api/investigations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/investigations").header("X-API-Key", PARTNER_KEY)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/investigations").header("X-API-Key", PARTNER_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"analysisId\":1}")).andExpect(status().isUnauthorized());
        mockMvc.perform(sighting(randomHash(), "pixelgram", "pg-anon", null)).andExpect(status().isUnauthorized());
        moderator(get("/api/investigations")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode upload(String phash) throws Exception {
        nextPhash.set(phash);
        MockMultipartFile file = new MockMultipartFile("file", "synthetic.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
        return json(mockMvc.perform(multipart("/api/analyze").file(file)).andExpect(status().isOk()));
    }

    private String open(long analysisId) throws Exception {
        return json(moderator(post("/api/investigations").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("analysisId", analysisId)))).andExpect(status().isCreated())).get("caseNumber").asText();
    }

    private MockHttpServletRequestBuilder sighting(String phash, String platform, String postRef, String accountId) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phash", phash);
        m.put("platform", platform);
        m.put("postRef", postRef);
        if (accountId != null) {
            m.put("accountId", accountId);
        }
        return post("/api/v1/sightings").contentType(MediaType.APPLICATION_JSON).content(body(m));
    }

    private ResultActions partner(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.header("X-API-Key", PARTNER_KEY));
    }

    private ResultActions moderator(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mockMvc.perform(request.with(httpBasic("moderator", "moderator")));
    }

    private void assertError(ResultActions actions, int status, String code) throws Exception {
        JsonNode body = json(actions.andExpect(status().is(status)));
        assertThat(body.get("error").asText()).as(body.toString()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private static String randomHash() {
        return String.format("%016x", RANDOM.nextLong());
    }

    private static String flip(String hash, int bits) {
        return String.format("%016x", Long.parseUnsignedLong(hash, 16) ^ ((1L << bits) - 1));
    }

    private static String invert(String hash) {
        return String.format("%016x", ~Long.parseUnsignedLong(hash, 16));
    }
}

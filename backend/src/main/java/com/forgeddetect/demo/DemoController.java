package com.forgeddetect.demo;

import com.forgeddetect.security.DevModeratorProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 25: the one-click demo. Everything here is public while {@code demo.enabled=true} and 404
 * otherwise; the few privileged actions check the caller's role themselves.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoProperties properties;
    private final DemoScenarioService scenario;
    private final DemoResetService resets;
    private final DemoSamples samples;
    private final DevModeratorProperties moderators;

    public DemoController(
            DemoProperties properties,
            DemoScenarioService scenario,
            DemoResetService resets,
            DemoSamples samples,
            DevModeratorProperties moderators) {
        this.properties = properties;
        this.scenario = scenario;
        this.resets = resets;
        this.samples = samples;
        this.moderators = moderators;
    }

    /** Demo settings and the current story, if one was seeded. Never seeds by itself. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        if (!properties.isEnabled()) {
            return noStore(Map.of("enabled", false));
        }
        return noStore(view(scenario.current().orElse(null)));
    }

    /**
     * Returns the story, seeding it on first use. {@code fresh=true} (moderators only) seeds another
     * one next to the existing data.
     */
    @PostMapping("/story")
    public ResponseEntity<Map<String, Object>> story(
            @RequestParam(defaultValue = "false") boolean fresh, Authentication auth) {
        requireEnabled();
        if (fresh && !hasRole(auth, "MODERATOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a signed-in moderator can seed an extra story.");
        }
        DemoStory story = fresh ? scenario.create(null) : scenario.ensure();
        return noStore(view(story));
    }

    /** Public demo: anyone, rate limited by the cooldown. Otherwise senior moderators only. */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(Authentication auth) {
        requireEnabled();
        boolean senior = hasRole(auth, "SENIOR_MODERATOR");
        if (!properties.isPublicMode() && !senior) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a senior moderator can reset the demo data.");
        }
        return noStore(view(resets.reset(!senior)));
    }

    @GetMapping("/samples/{name}")
    public ResponseEntity<byte[]> sample(@PathVariable String name) {
        requireEnabled();
        DemoSamples.Sample s = samples.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(s.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(s.name()).build().toString())
                .body(s.bytes());
    }

    /** The story's signed report exactly as it was generated, so its SHA-256 matches the one on the tour. */
    @GetMapping("/report.pdf")
    public ResponseEntity<byte[]> report() {
        requireEnabled();
        DemoStory story = scenario.current()
                .filter(s -> s.getReportPdf() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No demo story yet."));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("demo-" + story.getReportId() + ".pdf").build().toString())
                .header("X-Report-Id", story.getReportId())
                .header("X-Report-SHA256", story.getReportSha256())
                .body(story.getReportPdf());
    }

    @ExceptionHandler(DemoResetService.CoolingDown.class)
    public ResponseEntity<Map<String, Object>> coolingDown(DemoResetService.CoolingDown ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(Map.of("error", "demo_reset_cooldown", "message", ex.getReason(), "retryAfterSeconds", ex.retryAfterSeconds()));
    }

    @ExceptionHandler(DemoScenarioService.DemoBusy.class)
    public ResponseEntity<Map<String, Object>> busy(DemoScenarioService.DemoBusy ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "3")
                .body(Map.of("error", "demo_busy", "message", ex.getReason()));
    }

    private Map<String, Object> view(DemoStory story) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("publicMode", properties.isPublicMode());
        body.put("jurisdiction", properties.getJurisdiction());
        body.put("resetInterval", properties.scheduledReset() ? properties.getResetInterval().toString() : null);
        body.put("nextResetAt", resets.nextReset().map(Object::toString).orElse(null));
        body.put("resetCooldownSeconds", properties.getResetCooldown().toSeconds());
        body.put("samples", samples.all().stream().map(DemoSamples.Sample::view).toList());
        body.put("accounts", accounts());
        body.put("story", story != null ? scenario.view(story) : null);
        return body;
    }

    /** Sign-in details are published only on a public demo, whose accounts exist to be shared. */
    private List<Map<String, Object>> accounts() {
        return moderators.accounts().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", a.username());
            m.put("role", a.role().name());
            m.put("team", a.team());
            if (properties.isPublicMode()) {
                m.put("password", a.password());
            }
            return m;
        }).toList();
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

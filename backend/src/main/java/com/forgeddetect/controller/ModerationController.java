package com.forgeddetect.controller;

import com.forgeddetect.service.ModerationService;
import com.forgeddetect.service.WellbeingService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 23: the signed-in moderator's profile, team roster and wellbeing state. */
@RestController
@RequestMapping("/api/moderation")
public class ModerationController {

    private final ModerationService moderation;
    private final WellbeingService wellbeing;

    public ModerationController(ModerationService moderation, WellbeingService wellbeing) {
        this.moderation = moderation;
        this.wellbeing = wellbeing;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> me(Principal principal) {
        String me = principal.getName();
        return Map.of("moderator", moderation.moderator(me).view(), "wellbeing", wellbeing.status(me).view());
    }

    @GetMapping("/teams")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, List<Map<String, Object>>> teams() {
        return moderation.teams();
    }

    /** Voluntary break; enforced the same way as one triggered by the exposure limit. */
    @PostMapping("/break")
    @PreAuthorize("hasRole('MODERATOR')")
    public Map<String, Object> takeBreak(Principal principal) {
        return wellbeing.startBreak(principal.getName()).view();
    }
}

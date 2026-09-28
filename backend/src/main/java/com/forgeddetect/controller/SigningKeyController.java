package com.forgeddetect.controller;

import com.forgeddetect.model.ModerationAction;
import com.forgeddetect.service.ModerationAuditService;
import com.forgeddetect.service.ReportSigningService;
import com.forgeddetect.signing.SigningKeyProvider;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 24: report-signing key rotation. Senior moderators only (see {@code SecurityConfig}); audit-logged. */
@RestController
@RequestMapping("/api/admin/signing-keys")
public class SigningKeyController {

    private final ReportSigningService signing;
    private final ModerationAuditService audit;

    public SigningKeyController(ReportSigningService signing, ModerationAuditService audit) {
        this.signing = signing;
        this.audit = audit;
    }

    @PostMapping("/rotate")
    public Map<String, Object> rotate(Principal principal) {
        SigningKeyProvider provider = signing.provider();
        String previous = provider.activeKeyId();
        SigningKeyProvider.KeyInfo next = provider.rotate();
        audit.record(principal.getName(), ModerationAction.SIGNING_KEY_ROTATED, null, null,
                "Report signing key rotated " + previous + " -> " + next.keyId() + " (" + provider.kind() + ")");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previousKeyId", previous);
        body.put("activeKeyId", next.keyId());
        body.put("provider", provider.kind());
        body.put("message", "New reports are signed with " + next.keyId() + ". Reports signed with " + previous
                + " still verify: its public key stays in the keyring.");
        return body;
    }
}

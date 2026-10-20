package com.forgeddetect.security;

import com.forgeddetect.model.ModeratorRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dev-only moderator seed credentials (Phase 8). Phase 23 adds extra accounts so second review and
 * appeals (which need a different person) can be exercised locally.
 */
@ConfigurationProperties(prefix = "app.security")
public class DevModeratorProperties {

    public record Account(String username, String password, ModeratorRole role, String team) {}

    private String moderatorUsername = "moderator";
    private String moderatorPassword = "moderator";

    /** Role for the primary account; use {@code ADMIN} in production so the owner can open /admin. */
    private String moderatorRole = "MODERATOR";

    /** Comma-separated {@code username:password:ROLE:team}; ROLE and team are optional. */
    private String extraModerators = "";

    /** Primary account (team "core") followed by the extra accounts; malformed entries are skipped. */
    public List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        out.add(new Account(moderatorUsername, moderatorPassword, parseRole(moderatorRole, ModeratorRole.MODERATOR), "core"));
        if (extraModerators == null || extraModerators.isBlank()) {
            return out;
        }
        for (String entry : extraModerators.split(",")) {
            String[] p = entry.trim().split(":");
            if (p.length < 2 || p[0].isBlank() || p[1].isBlank() || p[0].trim().equals(moderatorUsername)) {
                continue;
            }
            ModeratorRole role = p.length > 2 && !p[2].isBlank()
                    ? parseRole(p[2], null)
                    : ModeratorRole.MODERATOR;
            if (role == null) {
                continue;
            }
            String team = p.length > 3 && !p[3].isBlank() ? p[3].trim().toLowerCase(Locale.ROOT) : "core";
            out.add(new Account(p[0].trim(), p[1], role, team));
        }
        return out;
    }

    private static ModeratorRole parseRole(String raw, ModeratorRole fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ModeratorRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public String getModeratorUsername() {
        return moderatorUsername;
    }

    public void setModeratorUsername(String moderatorUsername) {
        this.moderatorUsername = moderatorUsername;
    }

    public String getModeratorPassword() {
        return moderatorPassword;
    }

    public void setModeratorPassword(String moderatorPassword) {
        this.moderatorPassword = moderatorPassword;
    }

    public String getModeratorRole() {
        return moderatorRole;
    }

    public void setModeratorRole(String moderatorRole) {
        this.moderatorRole = moderatorRole;
    }

    public String getExtraModerators() {
        return extraModerators;
    }

    public void setExtraModerators(String extraModerators) {
        this.extraModerators = extraModerators;
    }
}

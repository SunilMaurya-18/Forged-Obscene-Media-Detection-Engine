package com.forgeddetect.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dev-only moderator seed credentials (Phase 8).
 */
@ConfigurationProperties(prefix = "app.security")
public class DevModeratorProperties {

    private String moderatorUsername = "moderator";
    private String moderatorPassword = "moderator";

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
}

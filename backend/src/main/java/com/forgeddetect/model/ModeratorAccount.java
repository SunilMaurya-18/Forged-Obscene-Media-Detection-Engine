package com.forgeddetect.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Phase 23: a moderator login, with the team they work in and their role. Password is BCrypt-hashed. */
@Entity
@Table(name = "moderator_accounts")
public class ModeratorAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, length = 80)
    private String passwordHash;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ModeratorRole role = ModeratorRole.MODERATOR;

    @Column(nullable = false, length = 64)
    private String team;

    /** When false the account cannot sign in; used by the owner admin console. */
    @Column(nullable = false)
    private boolean enabled = true;

    protected ModeratorAccount() {}

    public ModeratorAccount(String username, String passwordHash, String displayName, ModeratorRole role, String team) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.team = team;
        this.enabled = true;
    }

    public boolean isAdmin() {
        return role == ModeratorRole.ADMIN;
    }

    /** Seniors and admins may decide escalated items and approve legal requests. */
    public boolean isSenior() {
        return role == ModeratorRole.SENIOR_MODERATOR || role == ModeratorRole.ADMIN;
    }

    public boolean isInvestigator() {
        return role == ModeratorRole.INVESTIGATOR;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public ModeratorRole getRole() {
        return role;
    }

    public void setRole(ModeratorRole role) {
        this.role = role;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

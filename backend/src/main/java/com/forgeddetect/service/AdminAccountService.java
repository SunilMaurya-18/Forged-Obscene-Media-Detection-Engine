package com.forgeddetect.service;

import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.model.ModeratorRole;
import com.forgeddetect.repository.ModeratorAccountRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Owner-admin console: create and manage staff accounts without editing .env. */
@Service
public class AdminAccountService {

    private static final int MIN_PASSWORD = 12;
    private static final int MAX_USERNAME = 64;
    private static final Set<String> WEAK = Set.of(
            "moderator", "reviewer", "senior", "apac", "admin", "forged", "password", "changeme");

    private final ModeratorAccountRepository accounts;
    private final PasswordEncoder encoder;

    public AdminAccountService(ModeratorAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return accounts.findAllByOrderByTeamAscUsernameAsc().stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(String username, String password, String role, String team, String displayName) {
        String user = requireUsername(username);
        if (accounts.findByUsername(user).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username_taken");
        }
        requirePassword(user, password);
        ModeratorRole parsed = parseRole(role);
        String t = normalizeTeam(team, parsed);
        String display = displayName == null || displayName.isBlank() ? user : displayName.trim();
        if (display.length() > 80) {
            display = display.substring(0, 80);
        }
        ModeratorAccount saved = accounts.save(new ModeratorAccount(user, encoder.encode(password), display, parsed, t));
        return view(saved);
    }

    @Transactional
    public Map<String, Object> update(String username, String role, String team, String displayName, Boolean enabled, String actor) {
        ModeratorAccount a = find(username);
        if (role != null && !role.isBlank()) {
            ModeratorRole next = parseRole(role);
            if (a.isAdmin() && next != ModeratorRole.ADMIN && countAdmins() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "last_admin");
            }
            a.setRole(next);
        }
        if (team != null && !team.isBlank()) {
            a.setTeam(normalizeTeam(team, a.getRole()));
        }
        if (displayName != null && !displayName.isBlank()) {
            String display = displayName.trim();
            a.setDisplayName(display.length() > 80 ? display.substring(0, 80) : display);
        }
        if (enabled != null) {
            if (!enabled && a.getUsername().equals(actor)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot_disable_self");
            }
            if (!enabled && a.isAdmin() && countAdmins() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "last_admin");
            }
            a.setEnabled(enabled);
        }
        return view(accounts.save(a));
    }

    @Transactional
    public Map<String, Object> setPassword(String username, String password, String actor) {
        ModeratorAccount a = find(username);
        requirePassword(a.getUsername(), password);
        a.setPasswordHash(encoder.encode(password));
        return view(accounts.save(a));
    }

    private ModeratorAccount find(String username) {
        return accounts.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found"));
    }

    private long countAdmins() {
        return accounts.findAll().stream().filter(a -> a.isAdmin() && a.isEnabled()).count();
    }

    private Map<String, Object> view(ModeratorAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", a.getUsername());
        m.put("displayName", a.getDisplayName());
        m.put("role", a.getRole().name());
        m.put("team", a.getTeam());
        m.put("enabled", a.isEnabled());
        m.put("admin", a.isAdmin());
        m.put("senior", a.isSenior());
        m.put("investigator", a.isInvestigator());
        return m;
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username_required");
        }
        String user = username.trim().toLowerCase(Locale.ROOT);
        if (user.length() > MAX_USERNAME || !user.matches("[a-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_username");
        }
        return user;
    }

    private static void requirePassword(String username, String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weak_password");
        }
        if (password.equalsIgnoreCase(username) || WEAK.contains(password.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weak_password");
        }
    }

    private static ModeratorRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return ModeratorRole.MODERATOR;
        }
        try {
            return ModeratorRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_role");
        }
    }

    private static String normalizeTeam(String team, ModeratorRole role) {
        String t = team == null || team.isBlank()
                ? (role == ModeratorRole.INVESTIGATOR ? "agency" : "core")
                : team.trim().toLowerCase(Locale.ROOT);
        if (t.length() > 64) {
            t = t.substring(0, 64);
        }
        return t;
    }
}

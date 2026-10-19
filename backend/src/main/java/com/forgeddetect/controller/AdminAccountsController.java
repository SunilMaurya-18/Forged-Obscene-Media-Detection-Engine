package com.forgeddetect.controller;

import com.forgeddetect.repository.AnalysisResultRepository;
import com.forgeddetect.repository.ModeratorAccountRepository;
import com.forgeddetect.service.AdminAccountService;
import com.forgeddetect.service.AnalysisService;
import com.forgeddetect.service.ApiKeyService;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-only admin console: staff accounts and a short system overview. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountsController {

    private final AdminAccountService accounts;
    private final ModeratorAccountRepository accountRepo;
    private final AnalysisService analysis;
    private final AnalysisResultRepository results;
    private final ApiKeyService apiKeys;

    public AdminAccountsController(
            AdminAccountService accounts,
            ModeratorAccountRepository accountRepo,
            AnalysisService analysis,
            AnalysisResultRepository results,
            ApiKeyService apiKeys) {
        this.accounts = accounts;
        this.accountRepo = accountRepo;
        this.analysis = analysis;
        this.results = results;
        this.apiKeys = apiKeys;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", analysis.stats());
        long admins = accountRepo.findAll().stream().filter(a -> a.isAdmin() && a.isEnabled()).count();
        long staff = accountRepo.findAll().stream().filter(a -> !a.isInvestigator()).count();
        long investigators = accountRepo.findAll().stream().filter(a -> a.isInvestigator()).count();
        body.put("accounts", Map.of(
                "total", accountRepo.count(),
                "staff", staff,
                "admins", admins,
                "investigators", investigators,
                "disabled", accountRepo.findAll().stream().filter(a -> !a.isEnabled()).count()));
        body.put("apiClients", apiKeys.list().size());
        body.put("analyses", results.count());
        body.put("soloOperator", admins > 0);
        return body;
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> listAccounts() {
        return accounts.list();
    }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateBody body) {
        CreateBody b = body == null ? new CreateBody(null, null, null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accounts.create(b.username(), b.password(), b.role(), b.team(), b.displayName()));
    }

    @PostMapping("/accounts/{username}")
    public Map<String, Object> update(
            @PathVariable String username, @RequestBody(required = false) UpdateBody body, Principal principal) {
        UpdateBody b = body == null ? new UpdateBody(null, null, null, null) : body;
        return accounts.update(username, b.role(), b.team(), b.displayName(), b.enabled(), principal.getName());
    }

    @PostMapping("/accounts/{username}/password")
    public Map<String, Object> password(
            @PathVariable String username, @RequestBody(required = false) PasswordBody body, Principal principal) {
        return accounts.setPassword(username, body != null ? body.password() : null, principal.getName());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", String.valueOf(ex.getReason()), "message", messageFor(ex.getReason())));
    }

    private static String messageFor(String reason) {
        return switch (reason == null ? "" : reason) {
            case "username_taken" -> "That username is already in use.";
            case "username_required" -> "Choose a username.";
            case "invalid_username" -> "Usernames are lowercase letters, digits, dots, underscores or hyphens.";
            case "weak_password" -> "Passwords need 12+ characters and must not be a published default or match the username.";
            case "invalid_role" -> "Role must be MODERATOR, SENIOR_MODERATOR, ADMIN or INVESTIGATOR.";
            case "last_admin" -> "Keep at least one enabled ADMIN account.";
            case "cannot_disable_self" -> "You cannot disable your own account while signed in.";
            case "not_found" -> "No account with that username.";
            default -> "Request failed.";
        };
    }

    public record CreateBody(String username, String password, String role, String team, String displayName) {}

    public record UpdateBody(String role, String team, String displayName, Boolean enabled) {}

    public record PasswordBody(String password) {}
}

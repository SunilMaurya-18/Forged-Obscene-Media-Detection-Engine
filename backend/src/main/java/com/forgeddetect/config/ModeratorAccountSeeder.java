package com.forgeddetect.config;

import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.repository.ModeratorAccountRepository;
import com.forgeddetect.security.DevModeratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 23: keeps the configured dev moderator accounts in {@code moderator_accounts}. Config wins:
 * a changed password, role or team is applied on the next start. Accounts added directly in the DB
 * are left alone.
 */
@Component
@Order(0)
public class ModeratorAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModeratorAccountSeeder.class);

    private final ModeratorAccountRepository accounts;
    private final DevModeratorProperties props;
    private final PasswordEncoder encoder;

    public ModeratorAccountSeeder(ModeratorAccountRepository accounts, DevModeratorProperties props, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.props = props;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (DevModeratorProperties.Account a : props.accounts()) {
            ModeratorAccount existing = accounts.findByUsername(a.username()).orElse(null);
            if (existing == null) {
                accounts.save(new ModeratorAccount(a.username(), encoder.encode(a.password()), a.username(), a.role(), a.team()));
                log.info("Seeded moderator account '{}' ({}, team {})", a.username(), a.role(), a.team());
                continue;
            }
            if (!encoder.matches(a.password(), existing.getPasswordHash())) {
                existing.setPasswordHash(encoder.encode(a.password()));
            }
            existing.setRole(a.role());
            existing.setTeam(a.team());
            accounts.save(existing);
        }
    }
}

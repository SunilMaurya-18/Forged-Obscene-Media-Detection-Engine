package com.forgeddetect.security;

import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.repository.ModeratorAccountRepository;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Moderators live in {@code moderator_accounts}. Seniors get MODERATOR + SENIOR_MODERATOR so every
 * {@code hasRole('MODERATOR')} check still admits them. Admins get those plus ADMIN. Investigators
 * get INVESTIGATOR alone, so no moderator endpoint admits them.
 */
public class ModeratorUserDetailsService implements UserDetailsService {

    private final ModeratorAccountRepository accounts;

    public ModeratorUserDetailsService(ModeratorAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        ModeratorAccount a = accounts.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown moderator"));
        if (!a.isEnabled()) {
            throw new DisabledException("Account disabled");
        }
        User.UserBuilder user = User.withUsername(a.getUsername()).password(a.getPasswordHash());
        if (a.isInvestigator()) {
            return user.roles("INVESTIGATOR").build();
        }
        if (a.isAdmin()) {
            return user.roles("MODERATOR", "SENIOR_MODERATOR", "ADMIN").build();
        }
        return a.isSenior() ? user.roles("MODERATOR", "SENIOR_MODERATOR").build() : user.roles("MODERATOR").build();
    }
}

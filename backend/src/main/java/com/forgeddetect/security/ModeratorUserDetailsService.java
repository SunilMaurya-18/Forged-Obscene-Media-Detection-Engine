package com.forgeddetect.security;

import com.forgeddetect.model.ModeratorAccount;
import com.forgeddetect.repository.ModeratorAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Phase 23: moderators live in {@code moderator_accounts}. Seniors get both roles, MODERATOR first,
 * so every existing {@code hasRole('MODERATOR')} check still admits them.
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
        User.UserBuilder user = User.withUsername(a.getUsername()).password(a.getPasswordHash());
        return a.isSenior() ? user.roles("MODERATOR", "SENIOR_MODERATOR").build() : user.roles("MODERATOR").build();
    }
}

package com.forgeddetect.security;

import com.forgeddetect.model.PlanTier;
import java.security.Principal;

/** Authenticated {@code /api/v1} caller. Carries no secret. */
public record ApiClientPrincipal(Long id, String name, PlanTier plan) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.religioustext.app.model.user.Role;
import org.religioustext.app.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges the app's User entity with Spring Security's authentication.
 * The "username" field in the login form is treated as the email address.
 */
@Service
@Transactional(readOnly = true)
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(final UserRepository aUserRepository) {
        this.userRepository = aUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(final String anEmail)
            throws UsernameNotFoundException {
        return userRepository
            .findByEmailIgnoreCase(anEmail.trim())
            .filter(org.religioustext.app.model.user.User::isActive)
            .filter(u -> {
                if (!u.isVerified())
                    throw new org.springframework.security.authentication.DisabledException(
                        "Email address not verified. Check your inbox for the verification link.");
                return true;
            })
            .map(u -> User.withUsername(u.getEmail())
                .password(u.getPasswordHash())
                // Grant CUMULATIVE authorities down the ladder so @RolesAllowed gates work
                // without depending on RoleHierarchy wiring (docs/access-control.md §7).
                // e.g. a contributor → ROLE_CONSUMER + ROLE_CONTRIBUTOR. ROLE_USER is a
                // legacy alias for consumer, kept so existing @RolesAllowed("USER") views
                // (ProfileView, PreferencesView) keep working during the rename.
                .authorities(authoritiesFor(u.getRole()))
                .build())
            .orElseThrow(() -> new UsernameNotFoundException(
                "No active account for: " + anEmail));
    }

    /** Cumulative authorities: every ladder rung up to and including the user's role,
     *  plus the legacy ROLE_USER alias. */
    private static List<GrantedAuthority> authoritiesFor(final Role aRole) {
        final Role effective = aRole == null ? Role.consumer : aRole;
        final List<GrantedAuthority> auths = new ArrayList<>();
        for (final Role r : Role.values()) {
            if (effective.atLeast(r)) auths.add(new SimpleGrantedAuthority(r.authority()));
        }
        auths.add(new SimpleGrantedAuthority("ROLE_USER")); // legacy alias for consumer
        return auths;
    }
}


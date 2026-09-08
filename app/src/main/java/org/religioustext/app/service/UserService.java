package org.religioustext.app.service;

import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    /** How long a password-reset link stays valid. */
    public static final long RESET_TOKEN_VALID_MINUTES = 60;

    /** How long an admin-invitation link stays valid (it is a reset link with a longer leash). */
    public static final long INVITE_TOKEN_VALID_DAYS = 7;

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService     mailService;

    public UserService(final UserRepository aUserRepository,
                       final PasswordEncoder aPasswordEncoder,
                       final MailService aMailService) {
        this.userRepository  = aUserRepository;
        this.passwordEncoder = aPasswordEncoder;
        this.mailService     = aMailService;
    }

    /**
     * Registers a new user. Email is normalised to lowercase.
     * The account starts UNVERIFIED — a verification email is sent and the user
     * must click the link before they can sign in.
     *
     * @throws EmailAlreadyUsedException if the email is already registered.
     * @throws WeakPasswordException     if the password is shorter than 8 characters.
     */
    public User register(final String anEmail,
                         final String aDisplayName,
                         final String aRawPassword) {
        if (aRawPassword == null || aRawPassword.length() < 8)
            throw new WeakPasswordException();
        final String normalised = anEmail.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalised))
            throw new EmailAlreadyUsedException();

        final String token = UUID.randomUUID().toString();

        final User user = new User();
        user.setEmail(normalised);
        user.setDisplayName(aDisplayName == null || aDisplayName.isBlank()
                            ? null : aDisplayName.trim());
        user.setPasswordHash(passwordEncoder.encode(aRawPassword));
        user.setVerified(false);              // must click the verification link
        user.setEmailVerificationToken(token);
        user.setActive(true);
        final User saved = userRepository.save(user);

        mailService.sendVerificationEmail(normalised, token);
        return saved;
    }

    /**
     * Verifies an account via the one-time token from the email link.
     *
     * @return true if the token was valid and the account is now verified;
     *         false if the token was not found (already used or invalid).
     */
    public boolean verifyEmail(final String aToken) {
        return userRepository.verifyByToken(aToken) > 0;
    }

    /**
     * Begins a password reset: if an account exists for the email, store a
     * one-time token (valid for {@link #RESET_TOKEN_VALID_MINUTES}) and email a
     * reset link. Deliberately returns nothing and reveals nothing — the caller
     * shows the same neutral message whether or not the address is registered,
     * so this endpoint cannot be used to probe which emails have accounts.
     */
    public void requestPasswordReset(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) return;
        userRepository.findByEmailIgnoreCase(anEmail.trim()).ifPresent(user -> {
            final String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetExpires(
                LocalDateTime.now().plusMinutes(RESET_TOKEN_VALID_MINUTES));
            userRepository.save(user);
            mailService.sendPasswordResetEmail(
                user.getEmail(), token, RESET_TOKEN_VALID_MINUTES);
        });
    }

    /**
     * Completes a password reset using the token from the email link.
     * Validates the token exists and has not expired, sets the new password,
     * and clears the token so the link can't be reused.
     *
     * @return true on success; false if the token is unknown or expired.
     * @throws WeakPasswordException if the new password is shorter than 8 characters.
     */
    public boolean resetPassword(final String aToken, final String aNewPassword) {
        if (aNewPassword == null || aNewPassword.length() < 8)
            throw new WeakPasswordException();
        if (aToken == null || aToken.isBlank()) return false;

        final Optional<User> match = userRepository.findByPasswordResetToken(aToken);
        if (match.isEmpty()) return false;

        final User user = match.get();
        final LocalDateTime expires = user.getPasswordResetExpires();
        if (expires == null || expires.isBefore(LocalDateTime.now())) {
            // Expired — clear the stale token so it can't linger.
            user.setPasswordResetToken(null);
            user.setPasswordResetExpires(null);
            userRepository.save(user);
            return false;
        }

        user.setPasswordHash(passwordEncoder.encode(aNewPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpires(null);
        // Completing a reset PROVES control of the mailbox — the very thing verification
        // establishes. Crucial for claim-by-reset: channel accounts are seeded unverified
        // (no verification mail is ever sent to them), so without this a claiming channel
        // would set a password and then be refused at login as unverified.
        user.setVerified(true);
        userRepository.save(user);
        return true;
    }

    /** Updates the display name for the given account (identified by email). */
    public void updateDisplayName(final String anEmail, final String aDisplayName) {
        userRepository.findByEmailIgnoreCase(anEmail).ifPresent(u -> {
            u.setDisplayName(aDisplayName == null || aDisplayName.isBlank()
                             ? null : aDisplayName.trim());
            userRepository.save(u);
        });
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(final String anEmail) {
        return userRepository.findByEmailIgnoreCase(anEmail.trim());
    }

    /**
     * The label the UI shows for a signed-in principal: the account's display name when set,
     * otherwise the email's local part ("admin" for {@literal admin@test.local}). One shared
     * resolution so every header (reader toolbar, About nav, …) renders the same name.
     *
     * @param anEmail the principal's email (may be blank for anonymous — returned as-is)
     * @return the display name, or the email prefix, or {@code anEmail} itself when unknown
     */
    @Transactional(readOnly = true)
    public String displayLabel(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) return anEmail;
        return findByEmail(anEmail)
            .map(User::getDisplayName)
            .filter(n -> n != null && !n.isBlank())
            .orElse(anEmail.contains("@")
                ? anEmail.substring(0, anEmail.indexOf('@')) : anEmail);
    }

    // ── Admin-created accounts (accessor management) ─────────────────────────

    /**
     * Creates an account on an admin's behalf and emails the invitee a claim link. The account
     * starts with an UNUSABLE random password and unverified; the claim link is a normal
     * password-reset link (valid {@link #INVITE_TOKEN_VALID_DAYS} days), and completing it both
     * sets the password and marks the account verified — the same claim-by-reset flow that
     * turns a seeded channel account into a real login.
     *
     * Caller authorisation (who may invite, which roles they may hand out) is the caller's
     * responsibility — see {@code AccessorAdminService}.
     *
     * @param anEmail       the invitee's address (normalised to lowercase)
     * @param aDisplayName  optional display name (blank → none)
     * @param aRole         the invitee's starting rung ({@code null} → {@link Role#consumer})
     * @return the persisted user
     * @throws EmailAlreadyUsedException if the email is already registered
     */
    public User invite(final String anEmail
                       , final String aDisplayName
                       , final Role aRole) {
        final String normalised = anEmail.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalised))
            throw new EmailAlreadyUsedException();

        final String token = UUID.randomUUID().toString();

        final User user = new User();
        user.setEmail(normalised);
        user.setDisplayName(aDisplayName == null || aDisplayName.isBlank()
                            ? null : aDisplayName.trim());
        // Unusable until claimed: random, never disclosed, and unguessable.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setVerified(false);
        user.setActive(true);
        user.setRole(aRole == null ? Role.consumer : aRole);
        user.setPasswordResetToken(token);
        user.setPasswordResetExpires(
            LocalDateTime.now().plusDays(INVITE_TOKEN_VALID_DAYS));
        final User saved = userRepository.save(user);

        mailService.sendInviteEmail(normalised, token, INVITE_TOKEN_VALID_DAYS);
        return saved;
    }

    /**
     * Re-issues the claim link for a not-yet-verified invited account: a fresh token, a fresh
     * {@link #INVITE_TOKEN_VALID_DAYS}-day window, a fresh email. No-op for verified accounts
     * (they have a password; the forgot-password flow is theirs).
     *
     * @param aUserId the invited account's {@code usr-} id
     */
    public void resendInvite(final String aUserId) {
        userRepository.findById(aUserId).filter(u -> !u.isVerified()).ifPresent(user -> {
            final String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetExpires(
                LocalDateTime.now().plusDays(INVITE_TOKEN_VALID_DAYS));
            userRepository.save(user);
            mailService.sendInviteEmail(user.getEmail(), token, INVITE_TOKEN_VALID_DAYS);
        });
    }

    /**
     * Moves an account to another rung on the role ladder. Ladder enforcement (who may grant
     * what) is the caller's responsibility — see {@code AccessorAdminService}.
     *
     * @param aUserId the account's {@code usr-} id
     * @param aRole   the new rung
     */
    public void setRole(final String aUserId, final Role aRole) {
        if (aRole == null) return;
        userRepository.findById(aUserId).ifPresent(u -> {
            u.setRole(aRole);
            userRepository.save(u);
        });
    }

    /**
     * Every account, email-ordered — the admin accessors grid.
     *
     * @return all users, including system (channel) accounts
     */
    @Transactional(readOnly = true)
    public List<User> listAccounts() {
        return userRepository.findAll(Sort.by("email"));
    }

    // ── Typed exceptions ──────────────────────────────────────────────────────

    public static class EmailAlreadyUsedException extends RuntimeException {
        public EmailAlreadyUsedException() { super("Email already registered"); }
    }

    public static class WeakPasswordException extends RuntimeException {
        public WeakPasswordException() { super("Password must be at least 8 characters"); }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends transactional emails. Falls back to logging the link to the console
 * when no SMTP server is configured (dev mode), so the full auth flow can be
 * tested without a real mail server.
 *
 * SMTP configuration (goes in application-local.properties, NOT committed):
 *
 *   spring.mail.host=smtp.gmail.com
 *   spring.mail.port=587
 *   spring.mail.username=your@gmail.com
 *   spring.mail.password=your-app-password
 *   spring.mail.properties.mail.smtp.auth=true
 *   spring.mail.properties.mail.smtp.starttls.enable=true
 *   religioustext.mail.from=your@gmail.com
 *   religioustext.base-url=http://localhost:8090
 *
 * For Mailgun / Amazon SES the host/port differ; consult their SMTP docs.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    // Optional: Spring only injects this if spring-boot-starter-mail is on the
    // classpath AND spring.mail.host is configured. When absent, falls back to log.
    @Autowired(required = false)
    private JavaMailSender mailSender;

    @org.springframework.beans.factory.annotation.Value(
        "${religioustext.mail.from:noreply@commonroot.local}")
    private String fromAddress;

    @org.springframework.beans.factory.annotation.Value(
        "${religioustext.base-url:http://localhost:8090}")
    private String baseUrl;

    /**
     * Sends an email-verification link.
     *
     * @param aToEmail  The recipient's email address.
     * @param aToken    The one-time verification token stored on the user record.
     */
    public void sendVerificationEmail(final String aToEmail, final String aToken) {
        final String link = baseUrl + "/verify?token=" + aToken;
        final String subject = "Verify your Common Root? account";
        final String body = """
            <p>Hello,</p>
            <p>Click the link below to verify your email address and activate
            your <strong>Common Root?</strong> account:</p>
            <p><a href="%s">%s</a></p>
            <p>The link is valid until you use it. If you did not create this
            account, you can safely ignore this email.</p>
            <p style="color:#888;font-size:12px">
            Reading and studying the texts is always free — no account needed.
            Your account only adds private notes and comments on verses.</p>
            """.formatted(link, link);

        if (mailSender == null) {
            // No SMTP configured — log the link so the feature can be tested locally.
            log.warn("===== EMAIL VERIFICATION (no SMTP configured) =====");
            log.warn("To: {}", aToEmail);
            log.warn("Verification link: {}", link);
            log.warn("===================================================");
            return;
        }

        try {
            final MimeMessage msg = mailSender.createMimeMessage();
            final MimeMessageHelper h = new MimeMessageHelper(msg, "utf-8");
            h.setFrom(fromAddress);
            h.setTo(aToEmail);
            h.setSubject(subject);
            h.setText(body, true);
            mailSender.send(msg);
            log.info("Verification email sent to {}", aToEmail);
        } catch (final MessagingException | MailException ex) {
            log.error("Failed to send verification email to {}: {}", aToEmail, ex.getMessage());
            log.warn("Verification link (fallback): {}", link);
        }
    }

    /**
     * Sends a password-reset link. Same dev fallback as verification: when no
     * SMTP is configured the link is logged so the flow is testable locally.
     *
     * @param aToEmail        The recipient's email address.
     * @param aToken          The one-time reset token stored on the user record.
     * @param aValidMinutes   How long the link stays valid, for the email copy.
     */
    public void sendPasswordResetEmail(final String aToEmail, final String aToken,
                                       final long aValidMinutes) {
        final String link = baseUrl + "/reset?token=" + aToken;
        final String subject = "Reset your Common Root? password";
        final String body = """
            <p>Hello,</p>
            <p>We received a request to reset the password for your
            <strong>Common Root?</strong> account. Click the link below to choose
            a new password:</p>
            <p><a href="%s">%s</a></p>
            <p>This link is valid for about %d minutes. If you did not request a
            password reset, you can safely ignore this email — your password will
            not change.</p>
            """.formatted(link, link, aValidMinutes);

        if (mailSender == null) {
            // No SMTP configured — log the link so the feature can be tested locally.
            log.warn("===== PASSWORD RESET (no SMTP configured) =====");
            log.warn("To: {}", aToEmail);
            log.warn("Reset link: {}", link);
            log.warn("===============================================");
            return;
        }

        try {
            final MimeMessage msg = mailSender.createMimeMessage();
            final MimeMessageHelper h = new MimeMessageHelper(msg, "utf-8");
            h.setFrom(fromAddress);
            h.setTo(aToEmail);
            h.setSubject(subject);
            h.setText(body, true);
            mailSender.send(msg);
            log.info("Password-reset email sent to {}", aToEmail);
        } catch (final MessagingException | MailException ex) {
            log.error("Failed to send password-reset email to {}: {}", aToEmail, ex.getMessage());
            log.warn("Reset link (fallback): {}", link);
        }
    }

    /**
     * Sends an admin-invitation link. The invited account exists but has an unusable random
     * password; the link is a normal reset link, so claiming the invite proves mailbox control
     * and marks the account verified (the same claim-by-reset trick channel accounts use).
     * Same dev fallback as the other mails: no SMTP → the link is logged.
     *
     * @param aToEmail    The invitee's email address.
     * @param aToken      The one-time reset token stored on the user record.
     * @param aValidDays  How long the link stays valid, for the email copy.
     */
    public void sendInviteEmail(final String aToEmail, final String aToken,
                                final long aValidDays) {
        final String link = baseUrl + "/reset?token=" + aToken;
        final String subject = "You have been invited to Common Root?";
        final String body = """
            <p>Hello,</p>
            <p>An administrator has created a <strong>Common Root?</strong> account
            for this address. Click the link below to choose a password and
            activate it:</p>
            <p><a href="%s">%s</a></p>
            <p>The link is valid for about %d days. If you were not expecting this
            invitation, you can safely ignore this email — the account stays
            unusable without it.</p>
            <p style="color:#888;font-size:12px">
            Reading and studying the texts is always free — no account needed.
            Your account only adds private notes and comments on verses.</p>
            """.formatted(link, link, aValidDays);

        if (mailSender == null) {
            // No SMTP configured — log the link so the feature can be tested locally.
            log.warn("===== ACCOUNT INVITE (no SMTP configured) =====");
            log.warn("To: {}", aToEmail);
            log.warn("Invite link: {}", link);
            log.warn("===============================================");
            return;
        }

        try {
            final MimeMessage msg = mailSender.createMimeMessage();
            final MimeMessageHelper h = new MimeMessageHelper(msg, "utf-8");
            h.setFrom(fromAddress);
            h.setTo(aToEmail);
            h.setSubject(subject);
            h.setText(body, true);
            mailSender.send(msg);
            log.info("Invite email sent to {}", aToEmail);
        } catch (final MessagingException | MailException ex) {
            log.error("Failed to send invite email to {}: {}", aToEmail, ex.getMessage());
            log.warn("Invite link (fallback): {}", link);
        }
    }
}

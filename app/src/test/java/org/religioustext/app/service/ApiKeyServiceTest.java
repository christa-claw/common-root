package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.ApiKeyRepository;
import org.religioustext.app.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The key lifecycle as pure(ish) behaviour: format, hash-at-rest, the shown-once
 * property, the live-key cap, and the fail-closed resolution ladder
 * (docs/api-test-cases.md K1–K3, K6, K10, K12-adjacent).
 */
class ApiKeyServiceTest {

    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final UserRepository   users   = mock(UserRepository.class);
    private final ApiKeyService    service = new ApiKeyService(apiKeys, users);

    // K1 — key format
    @Test
    void generatedKeysHaveTheDocumentedShape() {
        for (int i = 0; i < 50; i++) {
            final String key = service.generate();
            assertThat(key).matches("^crk_[0-9A-Za-z]{40}$");
        }
    }

    @Test
    void keyShapeIsRecognised() {
        assertThat(ApiKeyService.looksLikeKey("crk_" + "a".repeat(40))).isTrue();
        assertThat(ApiKeyService.looksLikeKey("crk_" + "a".repeat(39))).isFalse();
        assertThat(ApiKeyService.looksLikeKey("crk_" + "a".repeat(41))).isFalse();
        assertThat(ApiKeyService.looksLikeKey("CRK_" + "a".repeat(40))).isFalse();  // prefix is exact
        assertThat(ApiKeyService.looksLikeKey("crk_" + "a".repeat(39) + "!")).isFalse();
        assertThat(ApiKeyService.looksLikeKey(null)).isFalse();
        assertThat(ApiKeyService.looksLikeKey("")).isFalse();
    }

    // K3 — hash at rest, fixed vector so a digest change cannot pass silently
    @Test
    void hashIsSha256Hex() {
        assertThat(ApiKeyService.sha256Hex("crk_test"))
            .isEqualTo("7f792a508427b1a55b871a32b5a83c61a49c1cf24e5246b5b2495ab73d1a2878");
        assertThat(ApiKeyService.sha256Hex("")).isEqualTo(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    // K2 — the plaintext exists once; the stored row holds only hash + display id
    @Test
    void createStoresHashAndDisplayIdNeverThePlaintext() {
        when(apiKeys.countByUserIdAndRevokedAtIsNull("usr-1")).thenReturn(0L);
        when(apiKeys.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        final ApiKeyService.CreatedKey created = service.create("usr-1", "  laptop  ");
        final ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        org.mockito.Mockito.verify(apiKeys).save(saved.capture());

        assertThat(created.key()).matches("^crk_[0-9A-Za-z]{40}$");
        assertThat(saved.getValue().getKeyHash())
            .isEqualTo(ApiKeyService.sha256Hex(created.key()))
            .isNotEqualTo(created.key());
        assertThat(saved.getValue().getKeyId())
            .hasSize(8)
            .isEqualTo(created.key().substring(4, 12));
        assertThat(saved.getValue().getLabel()).isEqualTo("laptop");
    }

    // K10 — the live-key cap
    @Test
    void sixthLiveKeyIsRefused() {
        when(apiKeys.countByUserIdAndRevokedAtIsNull("usr-1"))
            .thenReturn((long) ApiKeyService.MAX_LIVE_KEYS);
        assertThatThrownBy(() -> service.create("usr-1", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("revoke");
    }

    // K5/K6/K8 — the resolution ladder fails closed at every rung
    @Test
    void resolutionFailsClosed() {
        // malformed never reaches the repository
        assertThat(service.resolve("not-a-key")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();

        // unknown
        when(apiKeys.findByKeyHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.resolve("crk_" + "a".repeat(40))).isEmpty();

        // revoked
        final ApiKey revoked = new ApiKey();
        revoked.setUserId("usr-1");
        revoked.setRevokedAt(LocalDateTime.now());
        when(apiKeys.findByKeyHash(anyString())).thenReturn(Optional.of(revoked));
        assertThat(service.resolve("crk_" + "a".repeat(40))).isEmpty();

        // live key, but the owner is gone
        final ApiKey live = new ApiKey();
        live.setUserId("usr-gone");
        when(apiKeys.findByKeyHash(anyString())).thenReturn(Optional.of(live));
        when(users.findById("usr-gone")).thenReturn(Optional.empty());
        assertThat(service.resolve("crk_" + "a".repeat(40))).isEmpty();
    }

    @Test
    void resolutionSucceedsForALiveKeyOfAnActiveAccount() {
        final ApiKey live = new ApiKey();
        live.setUserId("usr-1");
        final User owner = mock(User.class);
        when(owner.isActive()).thenReturn(true);
        when(apiKeys.findByKeyHash(anyString())).thenReturn(Optional.of(live));
        when(users.findById("usr-1")).thenReturn(Optional.of(owner));
        when(apiKeys.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        final Optional<ApiKeyService.ResolvedKey> resolved =
            service.resolve("crk_" + "a".repeat(40));
        assertThat(resolved).isPresent();
        assertThat(resolved.get().owner()).isSameAs(owner);
        assertThat(live.getLastUsedAt()).isNotNull();   // K11: successful use is recorded
    }
}

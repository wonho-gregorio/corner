package com.gym.management.auth.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDomainTests {
    @Test
    void refreshTokensAreRandomAndOnlyTheirHashesNeedToBeStored() {
        var service = new TokenService(null, new AuthProperties(
                "test", Duration.ofMinutes(15), Duration.ofDays(30), "unused-in-this-test"));

        var first = service.createRefreshToken();
        var second = service.createRefreshToken();

        assertThat(first).isNotEqualTo(second);
        assertThat(service.hashRefreshToken(first)).hasSize(64).doesNotContain(first);
        assertThat(service.hashRefreshToken(first)).isEqualTo(service.hashRefreshToken(first));
    }

    @Test
    void refreshSessionCanOnlyBeUsedBeforeExpiryAndRevocation() {
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var session = AuthRefreshSessionEntity.create(1L, "hash", 0L, now.plusSeconds(60), now, null, null);

        assertThat(session.isUsableAt(now)).isTrue();
        assertThat(session.isUsableAt(now.plusSeconds(60))).isFalse();

        session.revoke(now.plusSeconds(10));

        assertThat(session.isUsableAt(now.plusSeconds(11))).isFalse();
    }

    @Test
    void passwordChangeInvalidatesExistingSessionVersion() {
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var account = StaffAccountEntity.createInitialAdmin(1L, "admin", "old-hash", "관리자", now);

        account.changePassword("new-hash", now.plusSeconds(1));

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.getSessionVersion()).isEqualTo(1L);
        assertThat(account.isMustChangePassword()).isFalse();
    }
}

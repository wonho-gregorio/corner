package com.gym.management.auth.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GymSettingsTests {
    @Test
    void updatesGymInformationAndCheckoutPolicyTogether() {
        var createdAt = Instant.parse("2026-09-15T00:00:00Z");
        var updatedAt = createdAt.plusSeconds(60);
        var gym = GymEntity.create("기존 도장", null, null, null, createdAt);

        gym.updateSettings("새 도장", "02-123-4567", "서울시", "2층", true, 1L, updatedAt);

        assertThat(gym.getName()).isEqualTo("새 도장");
        assertThat(gym.getRepresentativePhone()).isEqualTo("02-123-4567");
        assertThat(gym.isCheckoutEnabled()).isTrue();
        assertThat(gym.getUpdatedBy()).isEqualTo(1L);
        assertThat(gym.getUpdatedAt()).isEqualTo(updatedAt);
    }
}

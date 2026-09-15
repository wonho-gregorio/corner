package com.gym.management.member.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemberGroupTests {
    @Test
    void deactivatesWithoutDeletingTheMemberGroup() {
        var createdAt = Instant.parse("2026-09-15T00:00:00Z");
        var updatedAt = createdAt.plusSeconds(60);
        var group = MemberGroupEntity.create(1L, "초등부", 1, RecordStatus.ACTIVE, 1L, createdAt);

        group.update("초등부", 2, RecordStatus.INACTIVE, 2L, updatedAt);

        assertThat(group.getStatus()).isEqualTo(RecordStatus.INACTIVE);
        assertThat(group.getDisplayOrder()).isEqualTo(2);
        assertThat(group.getUpdatedBy()).isEqualTo(2L);
        assertThat(group.getUpdatedAt()).isEqualTo(updatedAt);
    }
}

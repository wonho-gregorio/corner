package com.gym.management.member.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MemberManagementTests {
    @Test
    void issuesSequentialValuesFromTheLockedMonthlySequence() {
        var sequence = new MemberNumberSequenceEntity();
        var now = Instant.parse("2026-09-28T00:00:00Z");

        assertThat(sequence.next(now)).isEqualTo(1);
        assertThat(sequence.next(now.plusSeconds(1))).isEqualTo(2);
        assertThat(sequence.getUpdatedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void recordsLifecycleTimestampsWhenStatusChanges() {
        var createdAt = Instant.parse("2026-09-28T00:00:00Z");
        var archivedAt = createdAt.plusSeconds(60);
        var member = MemberEntity.create(
                1L, 10L, "202609-0001", MemberStatus.ACTIVE, "홍길동", "010-1234-5678",
                "01012345678", LocalDate.of(2000, 1, 1), Gender.UNSPECIFIED, null, null,
                null, null, null, LocalDate.of(2026, 9, 28), 1L, createdAt);

        member.update(10L, MemberStatus.ARCHIVED, "홍길동", "010-1234-5678", "01012345678",
                LocalDate.of(2000, 1, 1), Gender.UNSPECIFIED, null, null, null, null, null, 1L, archivedAt);

        assertThat(member.getArchivedAt()).isEqualTo(archivedAt);
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ARCHIVED);
    }

    @Test
    void normalizesAndMasksPhoneNumbersForSearchResults() {
        assertThat(MemberService.normalizePhone("010-1234 5678")).isEqualTo("01012345678");
        assertThat(MemberService.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
    }
}

package com.gym.management.membership.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

enum MembershipStopMode { IMMEDIATE, EFFECTIVE_DATE_START, EFFECTIVE_DATE_END }

@Getter
@Entity
@Table(name = "membership_terminations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipTerminationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long membershipId;
    private Long refundOperationId;
    private LocalDate effectiveDate;
    @Enumerated(EnumType.STRING) private MembershipStopMode stopMode;
    private Instant stopAt;
    private String reason;
    private Long processedBy;
    private Instant createdAt;
}

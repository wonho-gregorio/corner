package com.gym.management.attendance.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

enum AttendanceType { FREE, GENERAL_CLASS, PT_CLASS }
enum AttendanceStatus { ACTIVE, CANCELLED }
enum AttendanceAdjustmentType { CANCEL, TIME_CORRECTION }
enum AttendanceRestrictionType { OVERDUE_PAYMENT, BEFORE_FULL_PAYMENT }

@Getter
@Entity
@Table(name = "attendances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AttendanceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long memberId;
    private Long lessonSessionId;
    private Long membershipId;
    private Long countDebitEntryId;
    @Enumerated(EnumType.STRING) private AttendanceType attendanceType;
    private LocalDate businessDate;
    private Instant originalCheckInAt;
    private Instant originalCheckOutAt;
    private Instant currentCheckInAt;
    private Instant currentCheckOutAt;
    @Enumerated(EnumType.STRING) private AttendanceStatus status;
    private boolean countsForDailyTotal;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "attendance_adjustments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AttendanceAdjustmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long attendanceId;
    @Enumerated(EnumType.STRING) private AttendanceAdjustmentType adjustmentType;
    private Instant beforeCheckInAt;
    private Instant afterCheckInAt;
    private Instant beforeCheckOutAt;
    private Instant afterCheckOutAt;
    private String reason;
    private Long actorAccountId;
    private Instant createdAt;
}

@Getter
@Entity
@Table(name = "attendance_exceptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AttendanceExceptionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long attendanceId;
    private Long membershipId;
    @Enumerated(EnumType.STRING) private AttendanceRestrictionType restrictionType;
    private long outstandingAmountWon;
    private String reason;
    private Long approvedBy;
    private Instant createdAt;
}

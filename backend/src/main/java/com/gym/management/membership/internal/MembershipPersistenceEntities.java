package com.gym.management.membership.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

enum ProductType { PERIOD, COUNT, HYBRID }
enum PeriodUnit { DAY, MONTH }
enum PaymentPlan { LUMP_SUM, INSTALLMENT, SELECT_AT_ISSUE }
enum InitialPaymentType { AMOUNT, RATE }
enum AttendancePaymentPolicy { ALLOW, WARN, RESTRICT }
enum SaleStatus { ON_SALE, STOPPED }
enum PromotionStatus { ACTIVE, INACTIVE }
enum DiscountType { RATE, AMOUNT }
enum MembershipStatus { SCHEDULED, ACTIVE, PAUSED, EXPIRED, EXHAUSTED, TERMINATED }
enum PauseStatus { PLANNED, ACTIVE, COMPLETED, CANCELLED }
enum MembershipEventType {
    ISSUED, START_DATE_CHANGED, EXTENDED, PAUSED, RESUMED, EXPIRED, EXHAUSTED, TERMINATED, STATUS_CHANGED
}
enum CountEntryType { ISSUE, ATTENDANCE_DEBIT, ATTENDANCE_RESTORE, MANUAL_ADD, MANUAL_DEDUCT }
enum CountEntrySourceType { ISSUE, ATTENDANCE, MANUAL }

@Getter
@Entity
@Table(name = "membership_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipProductEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    @Enumerated(EnumType.STRING) private ProductType productType;
    private Integer durationValue;
    @Enumerated(EnumType.STRING) private PeriodUnit durationUnit;
    private Integer validityValue;
    @Enumerated(EnumType.STRING) private PeriodUnit validityUnit;
    private Integer totalCount;
    private long listPriceWon;
    private boolean partialPaymentAllowed;
    @Enumerated(EnumType.STRING) private PaymentPlan defaultPaymentPlan;
    private Integer maxInstallmentCount;
    @Enumerated(EnumType.STRING) private InitialPaymentType minimumInitialPaymentType;
    private Long minimumInitialPaymentValue;
    private boolean useBeforeFullPaymentAllowed;
    @Enumerated(EnumType.STRING) private AttendancePaymentPolicy overdueAttendancePolicy;
    private boolean pauseAllowed;
    private Integer maxPauseCount;
    private Integer maxPauseDaysPerPause;
    private Integer maxTotalPauseDays;
    private Integer minimumUseDaysBeforePause;
    private boolean extendExpiryOnPause;
    @Enumerated(EnumType.STRING) private SaleStatus saleStatus;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "promotions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PromotionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String name;
    private LocalDate startsOn;
    private LocalDate endsOn;
    @Enumerated(EnumType.STRING) private DiscountType discountType;
    private long discountValue;
    @Enumerated(EnumType.STRING) private PromotionStatus status;
    private String adminMemo;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "promotion_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PromotionProductEntity {
    @EmbeddedId
    private PromotionProductId id;
    private Instant createdAt;
    private Long createdBy;
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PromotionProductId implements Serializable {
    private Long promotionId;
    private Long productId;
}

@Getter
@Entity
@Table(name = "memberships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long memberId;
    private Long productId;
    private Long promotionId;
    @Enumerated(EnumType.STRING) private ProductType productType;
    @Enumerated(EnumType.STRING) private MembershipStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer totalCount;
    private Integer remainingCount;
    private long listPriceWon;
    private long discountWon;
    private long contractAmountWon;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> termsSnapshot;
    private Instant issuedAt;
    private Long issuedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "membership_pauses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipPauseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long membershipId;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDate actualResumedOn;
    @Enumerated(EnumType.STRING) private PauseStatus status;
    private int extensionDays;
    private boolean isPolicyException;
    private String reason;
    private Long approvedBy;
    private Instant createdAt;
}

@Getter
@Entity
@Table(name = "membership_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long membershipId;
    @Enumerated(EnumType.STRING) private MembershipEventType eventType;
    @Enumerated(EnumType.STRING) private MembershipStatus fromStatus;
    @Enumerated(EnumType.STRING) private MembershipStatus toStatus;
    private LocalDate oldStartDate;
    private LocalDate newStartDate;
    private LocalDate oldEndDate;
    private LocalDate newEndDate;
    private Instant effectiveAt;
    private String reason;
    private Long actorAccountId;
    private Instant createdAt;
}

@Getter
@Entity
@Table(name = "membership_count_entries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MembershipCountEntryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long membershipId;
    @Enumerated(EnumType.STRING) private CountEntryType entryType;
    private int deltaCount;
    @Enumerated(EnumType.STRING) private CountEntrySourceType sourceType;
    private String sourceId;
    private Long reversesEntryId;
    private String reason;
    private Long actorAccountId;
    private Instant createdAt;
}

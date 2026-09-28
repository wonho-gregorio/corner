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

    static MembershipProductEntity create(
            long gymId, String name, ProductType productType, Integer durationValue, PeriodUnit durationUnit,
            Integer validityValue, PeriodUnit validityUnit, Integer totalCount, long listPriceWon,
            boolean partialPaymentAllowed, PaymentPlan defaultPaymentPlan, Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed, AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed, Integer maxPauseCount, Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays, Integer minimumUseDaysBeforePause, boolean extendExpiryOnPause,
            SaleStatus saleStatus, long actorAccountId, Instant now
    ) {
        var product = new MembershipProductEntity();
        product.gymId = gymId;
        product.createdAt = now;
        product.createdBy = actorAccountId;
        product.apply(name, productType, durationValue, durationUnit, validityValue, validityUnit, totalCount,
                listPriceWon, partialPaymentAllowed, defaultPaymentPlan, maxInstallmentCount,
                minimumInitialPaymentType, minimumInitialPaymentValue, useBeforeFullPaymentAllowed,
                overdueAttendancePolicy, pauseAllowed, maxPauseCount, maxPauseDaysPerPause, maxTotalPauseDays,
                minimumUseDaysBeforePause, extendExpiryOnPause, saleStatus, actorAccountId, now);
        return product;
    }

    void update(
            String name, ProductType productType, Integer durationValue, PeriodUnit durationUnit,
            Integer validityValue, PeriodUnit validityUnit, Integer totalCount, long listPriceWon,
            boolean partialPaymentAllowed, PaymentPlan defaultPaymentPlan, Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed, AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed, Integer maxPauseCount, Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays, Integer minimumUseDaysBeforePause, boolean extendExpiryOnPause,
            SaleStatus saleStatus, long actorAccountId, Instant now
    ) {
        apply(name, productType, durationValue, durationUnit, validityValue, validityUnit, totalCount,
                listPriceWon, partialPaymentAllowed, defaultPaymentPlan, maxInstallmentCount,
                minimumInitialPaymentType, minimumInitialPaymentValue, useBeforeFullPaymentAllowed,
                overdueAttendancePolicy, pauseAllowed, maxPauseCount, maxPauseDaysPerPause, maxTotalPauseDays,
                minimumUseDaysBeforePause, extendExpiryOnPause, saleStatus, actorAccountId, now);
    }

    private void apply(
            String name, ProductType productType, Integer durationValue, PeriodUnit durationUnit,
            Integer validityValue, PeriodUnit validityUnit, Integer totalCount, long listPriceWon,
            boolean partialPaymentAllowed, PaymentPlan defaultPaymentPlan, Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed, AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed, Integer maxPauseCount, Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays, Integer minimumUseDaysBeforePause, boolean extendExpiryOnPause,
            SaleStatus saleStatus, long actorAccountId, Instant now
    ) {
        this.name = name;
        this.productType = productType;
        this.durationValue = durationValue;
        this.durationUnit = durationUnit;
        this.validityValue = validityValue;
        this.validityUnit = validityUnit;
        this.totalCount = totalCount;
        this.listPriceWon = listPriceWon;
        this.partialPaymentAllowed = partialPaymentAllowed;
        this.defaultPaymentPlan = defaultPaymentPlan;
        this.maxInstallmentCount = maxInstallmentCount;
        this.minimumInitialPaymentType = minimumInitialPaymentType;
        this.minimumInitialPaymentValue = minimumInitialPaymentValue;
        this.useBeforeFullPaymentAllowed = useBeforeFullPaymentAllowed;
        this.overdueAttendancePolicy = overdueAttendancePolicy;
        this.pauseAllowed = pauseAllowed;
        this.maxPauseCount = maxPauseCount;
        this.maxPauseDaysPerPause = maxPauseDaysPerPause;
        this.maxTotalPauseDays = maxTotalPauseDays;
        this.minimumUseDaysBeforePause = minimumUseDaysBeforePause;
        this.extendExpiryOnPause = extendExpiryOnPause;
        this.saleStatus = saleStatus;
        this.updatedAt = now;
        this.updatedBy = actorAccountId;
    }
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

    static PromotionEntity create(
            long gymId, String name, LocalDate startsOn, LocalDate endsOn, DiscountType discountType,
            long discountValue, PromotionStatus status, String adminMemo, long actorAccountId, Instant now
    ) {
        var promotion = new PromotionEntity();
        promotion.gymId = gymId;
        promotion.createdAt = now;
        promotion.createdBy = actorAccountId;
        promotion.update(name, startsOn, endsOn, discountType, discountValue, status, adminMemo,
                actorAccountId, now);
        return promotion;
    }

    void update(
            String name, LocalDate startsOn, LocalDate endsOn, DiscountType discountType,
            long discountValue, PromotionStatus status, String adminMemo, long actorAccountId, Instant now
    ) {
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.status = status;
        this.adminMemo = adminMemo;
        this.updatedAt = now;
        this.updatedBy = actorAccountId;
    }
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

    static PromotionProductEntity create(long promotionId, long productId, long actorAccountId, Instant now) {
        var link = new PromotionProductEntity();
        link.id = new PromotionProductId(promotionId, productId);
        link.createdAt = now;
        link.createdBy = actorAccountId;
        return link;
    }
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PromotionProductId implements Serializable {
    private Long promotionId;
    private Long productId;

    PromotionProductId(Long promotionId, Long productId) {
        this.promotionId = promotionId;
        this.productId = productId;
    }
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
    private String idempotencyKey;
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

    static MembershipEntity create(
            long gymId, long memberId, long productId, Long promotionId, String idempotencyKey,
            ProductType productType, MembershipStatus status, LocalDate startDate, LocalDate endDate,
            Integer totalCount, long listPriceWon, long discountWon, long contractAmountWon,
            Map<String, Object> termsSnapshot, long actorAccountId, Instant now
    ) {
        var membership = new MembershipEntity();
        membership.gymId = gymId;
        membership.memberId = memberId;
        membership.productId = productId;
        membership.promotionId = promotionId;
        membership.idempotencyKey = idempotencyKey;
        membership.productType = productType;
        membership.status = status;
        membership.startDate = startDate;
        membership.endDate = endDate;
        membership.totalCount = totalCount;
        membership.remainingCount = totalCount;
        membership.listPriceWon = listPriceWon;
        membership.discountWon = discountWon;
        membership.contractAmountWon = contractAmountWon;
        membership.termsSnapshot = Map.copyOf(termsSnapshot);
        membership.issuedAt = now;
        membership.issuedBy = actorAccountId;
        membership.createdAt = now;
        membership.updatedAt = now;
        membership.updatedBy = actorAccountId;
        return membership;
    }
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

    static MembershipEventEntity issued(
            long membershipId, MembershipStatus status, LocalDate startDate, LocalDate endDate,
            long actorAccountId, Instant now
    ) {
        var event = new MembershipEventEntity();
        event.membershipId = membershipId;
        event.eventType = MembershipEventType.ISSUED;
        event.toStatus = status;
        event.newStartDate = startDate;
        event.newEndDate = endDate;
        event.effectiveAt = now;
        event.actorAccountId = actorAccountId;
        event.createdAt = now;
        return event;
    }
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

    static MembershipCountEntryEntity issued(
            long membershipId, int totalCount, long actorAccountId, Instant now
    ) {
        var entry = new MembershipCountEntryEntity();
        entry.membershipId = membershipId;
        entry.entryType = CountEntryType.ISSUE;
        entry.deltaCount = totalCount;
        entry.sourceType = CountEntrySourceType.ISSUE;
        entry.sourceId = Long.toString(membershipId);
        entry.actorAccountId = actorAccountId;
        entry.createdAt = now;
        return entry;
    }
}

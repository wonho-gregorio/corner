package com.gym.management.payment.internal;

import jakarta.persistence.Column;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

enum ChargePlanType { LUMP_SUM, PARTIAL, INSTALLMENT, UNPAID }
enum ChargeStatus { SCHEDULED, PARTIALLY_PAID, PAID, OVERDUE, CANCELLED }
enum PaymentOperationType { REGISTER, CANCEL, CORRECT, REFUND }
enum PaymentTransactionType { PAYMENT, CANCELLATION, REFUND }
enum PaymentMethod { CASH, CARD, BANK_TRANSFER }
enum PaymentInputSource { MANUAL, PROVIDER }
enum PaymentSyncStatus { NOT_APPLICABLE, PENDING, SYNCED, FAILED }

@Getter
@Entity
@Table(name = "charges")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ChargeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long memberId;
    private Long membershipId;
    @Enumerated(EnumType.STRING) private ChargePlanType planType;
    private long contractAmountWon;
    private long adjustedAmountWon;
    private long paidAmountWon;
    private long balanceWon;
    @Enumerated(EnumType.STRING) private ChargeStatus status;
    private LocalDate firstDueOn;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "charge_installments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ChargeInstallmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long chargeId;
    private int installmentNo;
    private LocalDate dueOn;
    private long amountWon;
    private long paidAmountWon;
    @Enumerated(EnumType.STRING) private ChargeStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;
}

@Getter
@Entity
@Table(name = "charge_plan_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ChargePlanRevisionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long chargeId;
    private int revisionNo;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> beforePlan;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> afterPlan;
    private String reason;
    private Long changedBy;
    private Instant createdAt;
}

@Getter
@Entity
@Table(name = "payment_operations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PaymentOperationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long chargeId;
    @Enumerated(EnumType.STRING) private PaymentOperationType operationType;
    private String idempotencyKey;
    private Instant processedAt;
    private Long processedBy;
    private String reason;
    private String memo;
}

@Getter
@Entity
@Table(name = "payment_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PaymentTransactionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long operationId;
    private Long chargeId;
    @Enumerated(EnumType.STRING) private PaymentTransactionType transactionType;
    @Enumerated(EnumType.STRING) private PaymentMethod paymentMethod;
    private long amountWon;
    private Long originalTransactionId;
    private Long refundDeductionWon;
    private Integer usedDays;
    private Integer usedCount;
    @Enumerated(EnumType.STRING) private PaymentInputSource inputSource;
    private String provider;
    private String externalTransactionId;
    private String approvalNumber;
    @Enumerated(EnumType.STRING) private PaymentSyncStatus syncStatus;
    private Instant createdAt;
}

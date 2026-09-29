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
enum InitialPaymentRequirementType { AMOUNT, RATE }

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
    @Enumerated(EnumType.STRING) private InitialPaymentRequirementType minimumInitialPaymentType;
    private Long minimumInitialPaymentValue;
    private LocalDate firstDueOn;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static ChargeEntity create(
            long gymId, long memberId, long membershipId, ChargePlanType planType,
            long contractAmountWon, InitialPaymentRequirementType minimumInitialPaymentType,
            Long minimumInitialPaymentValue, LocalDate firstDueOn, long actorAccountId, Instant now
    ) {
        var charge = new ChargeEntity();
        charge.gymId = gymId;
        charge.memberId = memberId;
        charge.membershipId = membershipId;
        charge.planType = planType;
        charge.contractAmountWon = contractAmountWon;
        charge.adjustedAmountWon = contractAmountWon;
        charge.paidAmountWon = 0;
        charge.balanceWon = contractAmountWon;
        charge.status = contractAmountWon == 0 ? ChargeStatus.PAID : ChargeStatus.SCHEDULED;
        charge.minimumInitialPaymentType = minimumInitialPaymentType;
        charge.minimumInitialPaymentValue = minimumInitialPaymentValue;
        charge.firstDueOn = firstDueOn;
        charge.createdAt = now;
        charge.createdBy = actorAccountId;
        charge.updatedAt = now;
        charge.updatedBy = actorAccountId;
        return charge;
    }

    void applyPayment(long amountWon, ChargeStatus nextStatus, long actorAccountId, Instant now) {
        if (amountWon <= 0 || amountWon > balanceWon) throw new IllegalArgumentException("Invalid payment amount");
        this.paidAmountWon += amountWon;
        this.balanceWon -= amountWon;
        this.status = nextStatus;
        this.updatedAt = now;
        this.updatedBy = actorAccountId;
    }
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

    static ChargeInstallmentEntity create(
            long chargeId, int installmentNo, LocalDate dueOn, long amountWon, Instant now
    ) {
        var installment = new ChargeInstallmentEntity();
        installment.chargeId = chargeId;
        installment.installmentNo = installmentNo;
        installment.dueOn = dueOn;
        installment.amountWon = amountWon;
        installment.paidAmountWon = 0;
        installment.status = amountWon == 0 ? ChargeStatus.PAID : ChargeStatus.SCHEDULED;
        installment.createdAt = now;
        installment.updatedAt = now;
        return installment;
    }

    long remainingAmountWon() {
        return amountWon - paidAmountWon;
    }

    void applyPayment(long paymentWon, LocalDate businessDate, Instant now) {
        if (paymentWon <= 0 || paymentWon > remainingAmountWon()) {
            throw new IllegalArgumentException("Invalid installment payment amount");
        }
        this.paidAmountWon += paymentWon;
        var remaining = remainingAmountWon();
        this.status = remaining == 0
                ? ChargeStatus.PAID
                : (dueOn.isBefore(businessDate) ? ChargeStatus.OVERDUE : ChargeStatus.PARTIALLY_PAID);
        this.updatedAt = now;
    }

    void refreshStatus(LocalDate businessDate, Instant now) {
        var nextStatus = remainingAmountWon() == 0
                ? ChargeStatus.PAID
                : (dueOn.isBefore(businessDate)
                ? ChargeStatus.OVERDUE
                : (paidAmountWon > 0 ? ChargeStatus.PARTIALLY_PAID : ChargeStatus.SCHEDULED));
        if (status != nextStatus) {
            status = nextStatus;
            updatedAt = now;
        }
    }
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

    static PaymentOperationEntity register(
            long gymId, long chargeId, String idempotencyKey, Instant processedAt,
            long actorAccountId, String memo
    ) {
        var operation = new PaymentOperationEntity();
        operation.gymId = gymId;
        operation.chargeId = chargeId;
        operation.operationType = PaymentOperationType.REGISTER;
        operation.idempotencyKey = idempotencyKey;
        operation.processedAt = processedAt;
        operation.processedBy = actorAccountId;
        operation.memo = memo;
        return operation;
    }
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

    static PaymentTransactionEntity payment(
            long operationId, long chargeId, PaymentMethod paymentMethod, long amountWon,
            String approvalNumber, Instant now
    ) {
        var transaction = new PaymentTransactionEntity();
        transaction.operationId = operationId;
        transaction.chargeId = chargeId;
        transaction.transactionType = PaymentTransactionType.PAYMENT;
        transaction.paymentMethod = paymentMethod;
        transaction.amountWon = amountWon;
        transaction.inputSource = PaymentInputSource.MANUAL;
        transaction.approvalNumber = approvalNumber;
        transaction.syncStatus = PaymentSyncStatus.NOT_APPLICABLE;
        transaction.createdAt = now;
        return transaction;
    }
}

@Getter
@Entity
@Table(name = "payment_installment_allocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PaymentInstallmentAllocationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long transactionId;
    private Long installmentId;
    private long amountWon;
    private Instant createdAt;

    static PaymentInstallmentAllocationEntity create(
            long transactionId, long installmentId, long amountWon, Instant now
    ) {
        var allocation = new PaymentInstallmentAllocationEntity();
        allocation.transactionId = transactionId;
        allocation.installmentId = installmentId;
        allocation.amountWon = amountWon;
        allocation.createdAt = now;
        return allocation;
    }
}

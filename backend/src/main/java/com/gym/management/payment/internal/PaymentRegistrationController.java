package com.gym.management.payment.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.auth.AuthenticatedStaff;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

interface PaymentOperationRepository extends JpaRepository<PaymentOperationEntity, Long> {
    Optional<PaymentOperationEntity> findByGymIdAndIdempotencyKey(Long gymId, String idempotencyKey);

    List<PaymentOperationEntity> findAllByChargeIdOrderByProcessedAtDescIdDesc(Long chargeId);
}

interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, Long> {
    List<PaymentTransactionEntity> findAllByOperationIdOrderByIdAsc(Long operationId);
}

interface PaymentInstallmentAllocationRepository extends JpaRepository<PaymentInstallmentAllocationEntity, Long> {
    List<PaymentInstallmentAllocationEntity> findAllByTransactionIdOrderByIdAsc(Long transactionId);
}

@Service
class PaymentRegistrationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final ChargeRepository charges;
    private final ChargeInstallmentRepository installments;
    private final PaymentOperationRepository operations;
    private final PaymentTransactionRepository transactions;
    private final PaymentInstallmentAllocationRepository allocations;
    private final AuditTrail auditTrail;
    private final EntityManager entityManager;
    private final Clock clock;

    PaymentRegistrationService(
            ChargeRepository charges,
            ChargeInstallmentRepository installments,
            PaymentOperationRepository operations,
            PaymentTransactionRepository transactions,
            PaymentInstallmentAllocationRepository allocations,
            AuditTrail auditTrail,
            EntityManager entityManager
    ) {
        this.charges = charges;
        this.installments = installments;
        this.operations = operations;
        this.transactions = transactions;
        this.allocations = allocations;
        this.auditTrail = auditTrail;
        this.entityManager = entityManager;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    ChargeView getCharge(long gymId, long chargeId) {
        var charge = charges.findByIdAndGymId(chargeId, gymId).orElseThrow(PaymentRegistrationService::chargeNotFound);
        return toChargeView(charge);
    }

    @Transactional(readOnly = true)
    List<PaymentOperationView> listPayments(long gymId, long chargeId) {
        var charge = charges.findByIdAndGymId(chargeId, gymId).orElseThrow(PaymentRegistrationService::chargeNotFound);
        return operations.findAllByChargeIdOrderByProcessedAtDescIdDesc(charge.getId()).stream()
                .map(this::toOperationView)
                .toList();
    }

    @Transactional
    PaymentRegistrationResult register(
            long gymId, long actorAccountId, long chargeId, String idempotencyKey, PaymentCommand command
    ) {
        validateIdempotencyKey(idempotencyKey);
        validateCommand(command);
        acquireIdempotencyLock(gymId, idempotencyKey);
        var existing = operations.findByGymIdAndIdempotencyKey(gymId, idempotencyKey);
        if (existing.isPresent()) {
            assertSameRequest(existing.get(), chargeId, command);
            var charge = charges.findByIdAndGymId(chargeId, gymId)
                    .orElseThrow(PaymentRegistrationService::chargeNotFound);
            return new PaymentRegistrationResult(toOperationView(existing.get()), toChargeView(charge));
        }

        var charge = charges.findForUpdateByIdAndGymId(chargeId, gymId)
                .orElseThrow(PaymentRegistrationService::chargeNotFound);
        if (command.processedAt().isBefore(charge.getCreatedAt())) badRequest("PAYMENT_TIME_BEFORE_CHARGE");
        if (charge.getStatus() == ChargeStatus.CANCELLED) conflict("CHARGE_CANCELLED");
        if (charge.getBalanceWon() == 0) conflict("CHARGE_ALREADY_PAID");
        if (command.totalAmountWon() > charge.getBalanceWon()) conflict("PAYMENT_EXCEEDS_BALANCE");
        validateMinimumInitialPayment(charge, command.totalAmountWon());

        var lockedInstallments = installments.findAllForUpdateByChargeId(chargeId);
        if (lockedInstallments.isEmpty()) throw new IllegalStateException("Charge has no installment schedule");
        var now = clock.instant();
        var businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        var operation = operations.save(PaymentOperationEntity.register(
                gymId, chargeId, idempotencyKey, command.processedAt(), actorAccountId, clean(command.memo())));

        for (var item : command.items()) {
            var transaction = transactions.save(PaymentTransactionEntity.payment(
                    operation.getId(), chargeId, item.paymentMethod(), item.amountWon(),
                    clean(item.approvalNumber()), now));
            allocate(transaction, item.amountWon(), lockedInstallments, businessDate, now);
        }
        lockedInstallments.forEach(installment -> installment.refreshStatus(businessDate, now));

        var remainingAfterPayment = charge.getBalanceWon() - command.totalAmountWon();
        var nextStatus = remainingAfterPayment == 0
                ? ChargeStatus.PAID
                : (lockedInstallments.stream().anyMatch(item -> item.getStatus() == ChargeStatus.OVERDUE)
                ? ChargeStatus.OVERDUE : ChargeStatus.PARTIALLY_PAID);
        var before = chargeAuditValues(charge);
        charge.applyPayment(command.totalAmountWon(), nextStatus, actorAccountId, now);
        auditTrail.record(gymId, actorAccountId, "PAYMENT", "PAYMENT_REGISTERED", "CHARGE",
                Long.toString(chargeId), null, before, chargeAuditValues(charge), now);
        return new PaymentRegistrationResult(toOperationView(operation), toChargeView(charge));
    }

    private void allocate(
            PaymentTransactionEntity transaction,
            long amountWon,
            List<ChargeInstallmentEntity> lockedInstallments,
            LocalDate businessDate,
            Instant now
    ) {
        var remaining = amountWon;
        for (var installment : lockedInstallments) {
            if (remaining == 0) break;
            var openAmount = installment.remainingAmountWon();
            if (openAmount == 0) continue;
            var allocated = Math.min(openAmount, remaining);
            installment.applyPayment(allocated, businessDate, now);
            allocations.save(PaymentInstallmentAllocationEntity.create(
                    transaction.getId(), installment.getId(), allocated, now));
            remaining -= allocated;
        }
        if (remaining != 0) throw new IllegalStateException("Payment could not be fully allocated");
    }

    static void validateMinimumInitialPayment(ChargeEntity charge, long paymentWon) {
        if (charge.getPaidAmountWon() != 0 || charge.getMinimumInitialPaymentType() == null) return;
        long minimum = switch (charge.getMinimumInitialPaymentType()) {
            case AMOUNT -> charge.getMinimumInitialPaymentValue();
            case RATE -> BigDecimal.valueOf(charge.getContractAmountWon())
                    .multiply(BigDecimal.valueOf(charge.getMinimumInitialPaymentValue()))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                    .longValueExact();
        };
        minimum = Math.min(minimum, charge.getBalanceWon());
        if (paymentWon < minimum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MINIMUM_INITIAL_PAYMENT_NOT_MET");
        }
    }

    private void acquireIdempotencyLock(long gymId, String idempotencyKey) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, :seed))")
                .setParameter("key", idempotencyKey)
                .setParameter("seed", gymId)
                .getSingleResult();
    }

    private void validateCommand(PaymentCommand command) {
        if (command.items() == null || command.items().isEmpty()) badRequest("PAYMENT_ITEM_REQUIRED");
        long sum = 0;
        for (var item : command.items()) {
            if (item.amountWon() <= 0) badRequest("PAYMENT_AMOUNT_MUST_BE_POSITIVE");
            try {
                sum = Math.addExact(sum, item.amountWon());
            } catch (ArithmeticException exception) {
                badRequest("PAYMENT_AMOUNT_OVERFLOW");
            }
        }
        if (sum != command.totalAmountWon()) badRequest("PAYMENT_ITEM_TOTAL_MISMATCH");
        if (command.processedAt().isAfter(clock.instant().plusSeconds(300))) {
            badRequest("PAYMENT_TIME_IN_FUTURE");
        }
    }

    private void assertSameRequest(PaymentOperationEntity operation, long chargeId, PaymentCommand command) {
        var existingTransactions = transactions.findAllByOperationIdOrderByIdAsc(operation.getId());
        if (!Objects.equals(operation.getChargeId(), chargeId)
                || !operation.getProcessedAt().equals(command.processedAt())
                || !Objects.equals(operation.getMemo(), clean(command.memo()))
                || existingTransactions.size() != command.items().size()) {
            conflict("IDEMPOTENCY_KEY_REUSED");
        }
        for (int index = 0; index < existingTransactions.size(); index++) {
            var existing = existingTransactions.get(index);
            var requested = command.items().get(index);
            if (existing.getPaymentMethod() != requested.paymentMethod()
                    || existing.getAmountWon() != requested.amountWon()
                    || !Objects.equals(existing.getApprovalNumber(), clean(requested.approvalNumber()))) {
                conflict("IDEMPOTENCY_KEY_REUSED");
            }
        }
    }

    private ChargeView toChargeView(ChargeEntity charge) {
        var installmentViews = installments.findAllByChargeIdOrderByInstallmentNoAsc(charge.getId()).stream()
                .map(item -> new InstallmentView(item.getId(), item.getInstallmentNo(), item.getDueOn(),
                        item.getAmountWon(), item.getPaidAmountWon(), item.remainingAmountWon(), item.getStatus()))
                .toList();
        return new ChargeView(charge.getId(), charge.getMemberId(), charge.getMembershipId(), charge.getPlanType(),
                charge.getContractAmountWon(), charge.getAdjustedAmountWon(), charge.getPaidAmountWon(),
                charge.getBalanceWon(), charge.getStatus(), charge.getMinimumInitialPaymentType(),
                charge.getMinimumInitialPaymentValue(), charge.getFirstDueOn(), installmentViews);
    }

    private PaymentOperationView toOperationView(PaymentOperationEntity operation) {
        var paymentViews = transactions.findAllByOperationIdOrderByIdAsc(operation.getId()).stream()
                .map(transaction -> new PaymentItemView(transaction.getId(), transaction.getPaymentMethod(),
                        transaction.getAmountWon(), transaction.getApprovalNumber(),
                        allocations.findAllByTransactionIdOrderByIdAsc(transaction.getId()).stream()
                                .map(allocation -> new AllocationView(allocation.getInstallmentId(),
                                        allocation.getAmountWon())).toList()))
                .toList();
        return new PaymentOperationView(operation.getId(), operation.getChargeId(), operation.getProcessedAt(),
                operation.getProcessedBy(), operation.getMemo(), paymentViews);
    }

    private static LinkedHashMap<String, Object> chargeAuditValues(ChargeEntity charge) {
        var values = new LinkedHashMap<String, Object>();
        values.put("paidAmountWon", charge.getPaidAmountWon());
        values.put("balanceWon", charge.getBalanceWon());
        values.put("status", charge.getStatus().name());
        return values;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) badRequest("INVALID_IDEMPOTENCY_KEY");
    }

    private static void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static ResponseStatusException chargeNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "CHARGE_NOT_FOUND");
    }

    record PaymentCommand(long totalAmountWon, Instant processedAt, String memo, List<PaymentItemCommand> items) {
    }

    record PaymentItemCommand(PaymentMethod paymentMethod, long amountWon, String approvalNumber) {
    }

    record InstallmentView(
            long id, int installmentNo, LocalDate dueOn, long amountWon, long paidAmountWon,
            long balanceWon, ChargeStatus status
    ) {
    }

    record ChargeView(
            long id, long memberId, long membershipId, ChargePlanType planType, long contractAmountWon,
            long adjustedAmountWon, long paidAmountWon, long balanceWon, ChargeStatus status,
            InitialPaymentRequirementType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            LocalDate firstDueOn, List<InstallmentView> installments
    ) {
    }

    record AllocationView(long installmentId, long amountWon) {
    }

    record PaymentItemView(
            long transactionId, PaymentMethod paymentMethod, long amountWon,
            String approvalNumber, List<AllocationView> allocations
    ) {
    }

    record PaymentOperationView(
            long operationId, long chargeId, Instant processedAt, long processedBy,
            String memo, List<PaymentItemView> payments
    ) {
    }

    record PaymentRegistrationResult(PaymentOperationView operation, ChargeView charge) {
    }
}

@RestController
@RequestMapping("/api/charges/{chargeId}")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('PAYMENT_REGISTER')")
public class PaymentRegistrationController {
    private final PaymentRegistrationService service;

    PaymentRegistrationController(PaymentRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    public PaymentRegistrationService.ChargeView getCharge(
            @AuthenticationPrincipal AuthenticatedStaff principal, @PathVariable long chargeId
    ) {
        return service.getCharge(principal.gymId(), chargeId);
    }

    @GetMapping("/payments")
    public List<PaymentRegistrationService.PaymentOperationView> listPayments(
            @AuthenticationPrincipal AuthenticatedStaff principal, @PathVariable long chargeId
    ) {
        return service.listPayments(principal.gymId(), chargeId);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentRegistrationService.PaymentRegistrationResult register(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long chargeId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody RegisterPaymentRequest request
    ) {
        return service.register(
                principal.gymId(), principal.accountId(), chargeId, idempotencyKey.trim(), request.toCommand());
    }

    public record RegisterPaymentRequest(
            @Positive long totalAmountWon,
            @NotNull @PastOrPresent Instant processedAt,
            @Size(max = 1000) String memo,
            @NotEmpty List<@Valid PaymentItemRequest> items
    ) {
        PaymentRegistrationService.PaymentCommand toCommand() {
            return new PaymentRegistrationService.PaymentCommand(totalAmountWon, processedAt, memo,
                    items.stream().map(PaymentItemRequest::toCommand).toList());
        }
    }

    public record PaymentItemRequest(
            @NotNull PaymentMethod paymentMethod,
            @Positive long amountWon,
            @Size(max = 100) String approvalNumber
    ) {
        PaymentRegistrationService.PaymentItemCommand toCommand() {
            return new PaymentRegistrationService.PaymentItemCommand(paymentMethod, amountWon, approvalNumber);
        }
    }
}

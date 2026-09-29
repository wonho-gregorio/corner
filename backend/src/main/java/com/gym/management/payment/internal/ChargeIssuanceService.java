package com.gym.management.payment.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.payment.ChargeIssuer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

interface ChargeRepository extends JpaRepository<ChargeEntity, Long> {
    Optional<ChargeEntity> findByMembershipId(Long membershipId);

    Optional<ChargeEntity> findByIdAndGymId(Long id, Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select charge from ChargeEntity charge where charge.id = :chargeId and charge.gymId = :gymId")
    Optional<ChargeEntity> findForUpdateByIdAndGymId(
            @Param("chargeId") long chargeId, @Param("gymId") long gymId);
}

interface ChargeInstallmentRepository extends JpaRepository<ChargeInstallmentEntity, Long> {
    List<ChargeInstallmentEntity> findAllByChargeIdOrderByInstallmentNoAsc(Long chargeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select installment from ChargeInstallmentEntity installment
             where installment.chargeId = :chargeId
             order by installment.installmentNo
            """)
    List<ChargeInstallmentEntity> findAllForUpdateByChargeId(@Param("chargeId") long chargeId);
}

@Service
class ChargeIssuanceService implements ChargeIssuer {
    private final ChargeRepository charges;
    private final ChargeInstallmentRepository installments;
    private final AuditTrail auditTrail;
    private final Clock clock;

    ChargeIssuanceService(
            ChargeRepository charges, ChargeInstallmentRepository installments, AuditTrail auditTrail
    ) {
        this.charges = charges;
        this.installments = installments;
        this.auditTrail = auditTrail;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ChargeSnapshot create(IssueCharge command) {
        var existing = charges.findByMembershipId(command.membershipId());
        if (existing.isPresent()) return toSnapshot(existing.get());
        var now = clock.instant();
        var charge = charges.save(ChargeEntity.create(
                command.gymId(), command.memberId(), command.membershipId(),
                ChargePlanType.valueOf(command.planType().name()), command.contractAmountWon(),
                command.minimumInitialPaymentType() == null ? null
                        : InitialPaymentRequirementType.valueOf(command.minimumInitialPaymentType().name()),
                command.minimumInitialPaymentValue(), command.firstDueOn(), command.actorAccountId(), now));
        installments.saveAll(command.installments().stream().map(installment -> ChargeInstallmentEntity.create(
                charge.getId(), installment.installmentNo(), installment.dueOn(), installment.amountWon(), now)).toList());
        var auditValues = new java.util.LinkedHashMap<String, Object>();
        auditValues.put("memberId", command.memberId());
        auditValues.put("membershipId", command.membershipId());
        auditValues.put("planType", command.planType().name());
        auditValues.put("contractAmountWon", command.contractAmountWon());
        auditValues.put("firstDueOn", command.firstDueOn().toString());
        auditValues.put("installmentCount", command.installments().size());
        auditTrail.record(command.gymId(), command.actorAccountId(), "PAYMENT", "CHARGE_CREATED", "CHARGE",
                Long.toString(charge.getId()), null, null, auditValues, now);
        return toSnapshot(charge);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ChargeSnapshot getByMembershipId(long membershipId) {
        return charges.findByMembershipId(membershipId)
                .map(this::toSnapshot)
                .orElseThrow(() -> new IllegalStateException("Charge was not created for membership " + membershipId));
    }

    private ChargeSnapshot toSnapshot(ChargeEntity charge) {
        var schedule = installments.findAllByChargeIdOrderByInstallmentNoAsc(charge.getId()).stream()
                .map(installment -> new Installment(
                        installment.getInstallmentNo(), installment.getDueOn(), installment.getAmountWon()))
                .toList();
        return new ChargeSnapshot(charge.getId(), charge.getMembershipId(), PlanType.valueOf(charge.getPlanType().name()),
                charge.getContractAmountWon(), charge.getPaidAmountWon(), charge.getBalanceWon(),
                charge.getStatus().name(), charge.getMinimumInitialPaymentType() == null ? null
                : MinimumPaymentType.valueOf(charge.getMinimumInitialPaymentType().name()),
                charge.getMinimumInitialPaymentValue(), charge.getFirstDueOn(), schedule);
    }
}

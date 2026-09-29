package com.gym.management.payment;

import java.time.LocalDate;
import java.util.List;

public interface ChargeIssuer {
    ChargeSnapshot create(IssueCharge command);

    ChargeSnapshot getByMembershipId(long membershipId);

    enum PlanType { LUMP_SUM, PARTIAL, INSTALLMENT, UNPAID }
    enum MinimumPaymentType { AMOUNT, RATE }

    record Installment(int installmentNo, LocalDate dueOn, long amountWon) {
    }

    record IssueCharge(
            long gymId,
            long memberId,
            long membershipId,
            PlanType planType,
            long contractAmountWon,
            MinimumPaymentType minimumInitialPaymentType,
            Long minimumInitialPaymentValue,
            LocalDate firstDueOn,
            List<Installment> installments,
            long actorAccountId
    ) {
    }

    record ChargeSnapshot(
            long id,
            long membershipId,
            PlanType planType,
            long contractAmountWon,
            long paidAmountWon,
            long balanceWon,
            String status,
            MinimumPaymentType minimumInitialPaymentType,
            Long minimumInitialPaymentValue,
            LocalDate firstDueOn,
            List<Installment> installments
    ) {
    }
}

package com.gym.management.payment.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeIssuanceTests {
    @Test
    void createsAnUnpaidScheduledChargeForTheFullContractBalance() {
        var charge = ChargeEntity.create(
                1L, 2L, 3L, ChargePlanType.INSTALLMENT, 300_000,
                InitialPaymentRequirementType.RATE, 20L,
                LocalDate.of(2026, 9, 28), 4L, Instant.parse("2026-09-28T00:00:00Z"));

        assertThat(charge.getPaidAmountWon()).isZero();
        assertThat(charge.getBalanceWon()).isEqualTo(300_000);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.SCHEDULED);
    }

    @Test
    void zeroWonContractStartsPaid() {
        var charge = ChargeEntity.create(
                1L, 2L, 3L, ChargePlanType.LUMP_SUM, 0,
                null, null, LocalDate.of(2026, 9, 28), 4L, Instant.parse("2026-09-28T00:00:00Z"));

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getBalanceWon()).isZero();
    }
}

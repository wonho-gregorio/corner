package com.gym.management.payment.internal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRegistrationTests {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void paymentReducesChargeBalanceAndMarksItPartiallyPaid() {
        var charge = charge(300_000, null, null);

        charge.applyPayment(100_000, ChargeStatus.PARTIALLY_PAID, 9L, NOW.plusSeconds(1));

        assertThat(charge.getPaidAmountWon()).isEqualTo(100_000);
        assertThat(charge.getBalanceWon()).isEqualTo(200_000);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
    }

    @Test
    void installmentPaymentCannotExceedItsRemainingAmount() {
        var installment = ChargeInstallmentEntity.create(
                1L, 1, LocalDate.of(2026, 10, 1), 100_000, NOW);

        assertThatThrownBy(() -> installment.applyPayment(
                100_001, LocalDate.of(2026, 9, 29), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overdueOpenInstallmentIsRefreshedEvenWithoutAnAllocation() {
        var installment = ChargeInstallmentEntity.create(
                1L, 1, LocalDate.of(2026, 9, 1), 100_000, NOW);

        installment.refreshStatus(LocalDate.of(2026, 9, 29), NOW.plusSeconds(1));

        assertThat(installment.getStatus()).isEqualTo(ChargeStatus.OVERDUE);
    }

    @Test
    void rateBasedMinimumInitialPaymentRoundsUp() {
        var charge = charge(99_999, InitialPaymentRequirementType.RATE, 15L);

        assertThatThrownBy(() -> PaymentRegistrationService.validateMinimumInitialPayment(charge, 14_999))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MINIMUM_INITIAL_PAYMENT_NOT_MET");
    }

    private static ChargeEntity charge(
            long amountWon, InitialPaymentRequirementType minimumType, Long minimumValue
    ) {
        return ChargeEntity.create(
                1L, 2L, 3L, ChargePlanType.PARTIAL, amountWon, minimumType, minimumValue,
                LocalDate.of(2026, 9, 29), 9L, NOW);
    }
}

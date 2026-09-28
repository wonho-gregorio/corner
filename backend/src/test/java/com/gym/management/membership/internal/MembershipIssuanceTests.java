package com.gym.management.membership.internal;

import com.gym.management.payment.ChargeIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipIssuanceTests {
    @Test
    void calculatesInclusiveEndDateFromMonths() {
        var product = product(ProductType.PERIOD, 3, PeriodUnit.MONTH, null, null, null, false, null);

        assertThat(MembershipIssuanceService.calculateEndDate(product, LocalDate.of(2026, 1, 31)))
                .isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    void countProductUsesValidityInsteadOfDuration() {
        var product = product(ProductType.COUNT, null, null, 30, PeriodUnit.DAY, 10, false, null);

        assertThat(MembershipIssuanceService.calculateEndDate(product, LocalDate.of(2026, 9, 1)))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void rateDiscountRoundsDownToWon() {
        assertThat(MembershipIssuanceService.calculateRateDiscount(99_999, 15)).isEqualTo(14_999);
    }

    @Test
    void installmentScheduleMustEqualTheContractAmount() {
        var product = product(ProductType.PERIOD, 3, PeriodUnit.MONTH, null, null, null, true, 3);
        var due = LocalDate.of(2026, 9, 28);
        var schedule = List.of(
                new MembershipIssuanceService.InstallmentCommand(1, due, 40_000),
                new MembershipIssuanceService.InstallmentCommand(2, due.plusMonths(1), 60_000));

        var result = MembershipIssuanceService.buildInstallments(
                product, ChargeIssuer.PlanType.INSTALLMENT, 100_000, due, schedule);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ChargeIssuer.Installment::amountWon).containsExactly(40_000L, 60_000L);
    }

    @Test
    void rejectsInstallmentsWhoseSumDiffersFromTheContract() {
        var product = product(ProductType.PERIOD, 3, PeriodUnit.MONTH, null, null, null, true, 3);
        var due = LocalDate.of(2026, 9, 28);

        assertThatThrownBy(() -> MembershipIssuanceService.buildInstallments(
                product, ChargeIssuer.PlanType.INSTALLMENT, 100_000, due, List.of(
                        new MembershipIssuanceService.InstallmentCommand(1, due, 40_000),
                        new MembershipIssuanceService.InstallmentCommand(2, due.plusMonths(1), 50_000))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INSTALLMENT_TOTAL_MISMATCH");
    }

    private static MembershipProductEntity product(
            ProductType type,
            Integer durationValue,
            PeriodUnit durationUnit,
            Integer validityValue,
            PeriodUnit validityUnit,
            Integer totalCount,
            boolean partialPaymentAllowed,
            Integer maxInstallmentCount
    ) {
        var now = Instant.parse("2026-09-28T00:00:00Z");
        return MembershipProductEntity.create(
                1L, "테스트 상품", type, durationValue, durationUnit, validityValue, validityUnit, totalCount,
                100_000, partialPaymentAllowed,
                partialPaymentAllowed ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM,
                maxInstallmentCount, partialPaymentAllowed ? InitialPaymentType.RATE : null,
                partialPaymentAllowed ? 20L : null, false, AttendancePaymentPolicy.ALLOW,
                false, null, null, null, null, false, SaleStatus.ON_SALE, 1L, now);
    }
}

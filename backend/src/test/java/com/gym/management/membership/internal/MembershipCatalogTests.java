package com.gym.management.membership.internal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipCatalogTests {
    @Test
    void periodProductKeepsOnlyItsDuration() {
        var normalized = MembershipCatalogService.normalizeAndValidate(command(
                ProductType.PERIOD, 3, PeriodUnit.MONTH, 30, PeriodUnit.DAY, 12,
                false, true, false, true));

        assertThat(normalized.durationValue()).isEqualTo(3);
        assertThat(normalized.validityValue()).isNull();
        assertThat(normalized.totalCount()).isNull();
    }

    @Test
    void countProductRequiresCountAndValidity() {
        var normalized = MembershipCatalogService.normalizeAndValidate(command(
                ProductType.COUNT, 3, PeriodUnit.MONTH, 90, PeriodUnit.DAY, 20,
                false, false, false, false));

        assertThat(normalized.durationValue()).isNull();
        assertThat(normalized.validityValue()).isEqualTo(90);
        assertThat(normalized.totalCount()).isEqualTo(20);
    }

    @Test
    void hybridProductKeepsDurationAndCount() {
        var normalized = MembershipCatalogService.normalizeAndValidate(command(
                ProductType.HYBRID, 3, PeriodUnit.MONTH, 90, PeriodUnit.DAY, 20,
                false, false, false, false));

        assertThat(normalized.durationValue()).isEqualTo(3);
        assertThat(normalized.validityValue()).isNull();
        assertThat(normalized.totalCount()).isEqualTo(20);
    }

    @Test
    void disablingPartialPaymentAlsoDisablesUseBeforeFullPayment() {
        var normalized = MembershipCatalogService.normalizeAndValidate(command(
                ProductType.PERIOD, 1, PeriodUnit.MONTH, null, null, null,
                false, true, false, false));

        assertThat(normalized.defaultPaymentPlan()).isEqualTo(PaymentPlan.LUMP_SUM);
        assertThat(normalized.maxInstallmentCount()).isNull();
        assertThat(normalized.minimumInitialPaymentType()).isNull();
        assertThat(normalized.useBeforeFullPaymentAllowed()).isFalse();
    }

    @Test
    void disablingPauseClearsItsDependentPolicy() {
        var normalized = MembershipCatalogService.normalizeAndValidate(command(
                ProductType.PERIOD, 1, PeriodUnit.MONTH, null, null, null,
                false, false, false, true));

        assertThat(normalized.maxPauseCount()).isNull();
        assertThat(normalized.maxTotalPauseDays()).isNull();
        assertThat(normalized.extendExpiryOnPause()).isFalse();
    }

    @Test
    void rejectsAmountMinimumAboveTheProductPrice() {
        var invalid = new MembershipCatalogService.ProductCommand(
                "3개월권", ProductType.PERIOD, 3, PeriodUnit.MONTH, null, null, null, 100_000,
                true, PaymentPlan.INSTALLMENT, 3, InitialPaymentType.AMOUNT, 100_001L, true,
                AttendancePaymentPolicy.RESTRICT, false, null, null, null, null, false, SaleStatus.ON_SALE);

        assertThatThrownBy(() -> MembershipCatalogService.normalizeAndValidate(invalid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MINIMUM_INITIAL_AMOUNT_EXCEEDS_PRICE");
    }

    private static MembershipCatalogService.ProductCommand command(
            ProductType type,
            Integer durationValue,
            PeriodUnit durationUnit,
            Integer validityValue,
            PeriodUnit validityUnit,
            Integer count,
            boolean partialPaymentAllowed,
            boolean useBeforeFullPaymentAllowed,
            boolean pauseAllowed,
            boolean extendExpiryOnPause
    ) {
        return new MembershipCatalogService.ProductCommand(
                "테스트 상품", type, durationValue, durationUnit, validityValue, validityUnit, count, 100_000,
                partialPaymentAllowed, PaymentPlan.INSTALLMENT, 3, InitialPaymentType.RATE, 20L,
                useBeforeFullPaymentAllowed, AttendancePaymentPolicy.WARN, pauseAllowed, 2, 7, 14, 10,
                extendExpiryOnPause, SaleStatus.ON_SALE);
    }
}

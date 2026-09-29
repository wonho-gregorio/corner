package com.gym.management.membership.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.auth.AuthenticatedStaff;
import com.gym.management.member.MemberDirectory;
import com.gym.management.payment.ChargeIssuer;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {
    List<MembershipEntity> findAllByGymIdAndMemberIdOrderByIssuedAtDescIdDesc(Long gymId, Long memberId);

    Optional<MembershipEntity> findByIdAndGymIdAndMemberId(Long id, Long gymId, Long memberId);

    Optional<MembershipEntity> findByGymIdAndIdempotencyKey(Long gymId, String idempotencyKey);
}

interface MembershipEventRepository extends JpaRepository<MembershipEventEntity, Long> {
}

interface MembershipCountEntryRepository extends JpaRepository<MembershipCountEntryEntity, Long> {
}

@Service
class MembershipIssuanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private final MembershipRepository memberships;
    private final MembershipEventRepository events;
    private final MembershipCountEntryRepository countEntries;
    private final MembershipProductRepository products;
    private final PromotionRepository promotions;
    private final PromotionProductRepository promotionProducts;
    private final MemberDirectory memberDirectory;
    private final ChargeIssuer chargeIssuer;
    private final AuditTrail auditTrail;
    private final EntityManager entityManager;
    private final Clock clock;

    MembershipIssuanceService(
            MembershipRepository memberships,
            MembershipEventRepository events,
            MembershipCountEntryRepository countEntries,
            MembershipProductRepository products,
            PromotionRepository promotions,
            PromotionProductRepository promotionProducts,
            MemberDirectory memberDirectory,
            ChargeIssuer chargeIssuer,
            AuditTrail auditTrail,
            EntityManager entityManager
    ) {
        this.memberships = memberships;
        this.events = events;
        this.countEntries = countEntries;
        this.products = products;
        this.promotions = promotions;
        this.promotionProducts = promotionProducts;
        this.memberDirectory = memberDirectory;
        this.chargeIssuer = chargeIssuer;
        this.auditTrail = auditTrail;
        this.entityManager = entityManager;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    List<IssuedMembershipView> list(long gymId, long memberId) {
        return memberships.findAllByGymIdAndMemberIdOrderByIssuedAtDescIdDesc(gymId, memberId).stream()
                .map(membership -> toView(membership, chargeIssuer.getByMembershipId(membership.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    IssuedMembershipView get(long gymId, long memberId, long membershipId) {
        var membership = memberships.findByIdAndGymIdAndMemberId(membershipId, gymId, memberId)
                .orElseThrow(MembershipIssuanceService::membershipNotFound);
        return toView(membership, chargeIssuer.getByMembershipId(membershipId));
    }

    @Transactional
    IssuedMembershipView issue(
            long gymId, long actorAccountId, long memberId, String idempotencyKey, IssueCommand command
    ) {
        validateIdempotencyKey(idempotencyKey);
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, :seed))")
                .setParameter("key", idempotencyKey)
                .setParameter("seed", gymId)
                .getSingleResult();
        memberDirectory.requireIssuable(gymId, memberId);
        var existing = memberships.findByGymIdAndIdempotencyKey(gymId, idempotencyKey);
        if (existing.isPresent()) {
            var charge = chargeIssuer.getByMembershipId(existing.get().getId());
            assertSameRequest(existing.get(), charge, memberId, command);
            return toView(existing.get(), charge);
        }

        var product = products.findForUpdateByIdAndGymId(command.productId(), gymId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBERSHIP_PRODUCT_NOT_FOUND"));
        if (product.getSaleStatus() != SaleStatus.ON_SALE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MEMBERSHIP_PRODUCT_NOT_ON_SALE");
        }
        var today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        var endDate = calculateEndDate(product, command.startDate());
        if (endDate.isBefore(today)) badRequest("MEMBERSHIP_END_DATE_IN_PAST");

        PromotionEntity promotion = null;
        var discountWon = 0L;
        if (command.promotionId() != null) {
            promotion = promotions.findForUpdateByIdAndGymId(command.promotionId(), gymId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND"));
            validatePromotion(promotion, product.getId(), today);
            discountWon = calculateDiscount(product.getListPriceWon(), promotion);
        }
        var contractAmountWon = product.getListPriceWon() - discountWon;
        var planType = resolvePlanType(product, command.planType(), contractAmountWon);
        var installmentSchedule = buildInstallments(
                product, planType, contractAmountWon, command.firstDueOn(), command.installments());
        var now = clock.instant();
        var status = command.startDate().isAfter(today) ? MembershipStatus.SCHEDULED : MembershipStatus.ACTIVE;
        var termsSnapshot = termsSnapshot(product, promotion, planType);
        var membership = memberships.saveAndFlush(MembershipEntity.create(
                gymId, memberId, product.getId(), promotion == null ? null : promotion.getId(), idempotencyKey,
                product.getProductType(), status, command.startDate(), endDate, product.getTotalCount(),
                product.getListPriceWon(), discountWon, contractAmountWon, termsSnapshot, actorAccountId, now));
        events.save(MembershipEventEntity.issued(
                membership.getId(), status, command.startDate(), endDate, actorAccountId, now));
        if (product.getTotalCount() != null) {
            countEntries.save(MembershipCountEntryEntity.issued(
                    membership.getId(), product.getTotalCount(), actorAccountId, now));
        }
        var charge = chargeIssuer.create(new ChargeIssuer.IssueCharge(
                gymId, memberId, membership.getId(), planType, contractAmountWon,
                product.getMinimumInitialPaymentType() == null ? null
                        : ChargeIssuer.MinimumPaymentType.valueOf(product.getMinimumInitialPaymentType().name()),
                product.getMinimumInitialPaymentValue(), command.firstDueOn(),
                installmentSchedule, actorAccountId));
        auditTrail.record(gymId, actorAccountId, "MEMBERSHIP", "MEMBERSHIP_ISSUED", "MEMBERSHIP",
                Long.toString(membership.getId()), null, null, auditValues(membership, charge.id()), now);
        return toView(membership, charge);
    }

    static LocalDate calculateEndDate(MembershipProductEntity product, LocalDate startDate) {
        var value = product.getProductType() == ProductType.COUNT
                ? product.getValidityValue() : product.getDurationValue();
        var unit = product.getProductType() == ProductType.COUNT
                ? product.getValidityUnit() : product.getDurationUnit();
        return switch (unit) {
            case DAY -> startDate.plusDays(value).minusDays(1);
            case MONTH -> startDate.plusMonths(value).minusDays(1);
        };
    }

    static long calculateRateDiscount(long listPriceWon, long rate) {
        return java.math.BigDecimal.valueOf(listPriceWon)
                .multiply(java.math.BigDecimal.valueOf(rate))
                .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.DOWN)
                .longValueExact();
    }

    private void validatePromotion(PromotionEntity promotion, long productId, LocalDate today) {
        if (promotion.getStatus() != PromotionStatus.ACTIVE
                || today.isBefore(promotion.getStartsOn()) || today.isAfter(promotion.getEndsOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PROMOTION_NOT_APPLICABLE");
        }
        if (!promotionProducts.existsById(new PromotionProductId(promotion.getId(), productId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PROMOTION_PRODUCT_NOT_APPLICABLE");
        }
    }

    private static long calculateDiscount(long listPriceWon, PromotionEntity promotion) {
        return promotion.getDiscountType() == DiscountType.AMOUNT
                ? promotion.getDiscountValue()
                : calculateRateDiscount(listPriceWon, promotion.getDiscountValue());
    }

    private static ChargeIssuer.PlanType resolvePlanType(
            MembershipProductEntity product, ChargeIssuer.PlanType requested, long contractAmountWon
    ) {
        if (contractAmountWon == 0) return ChargeIssuer.PlanType.LUMP_SUM;
        if (!product.isPartialPaymentAllowed()) {
            if (requested != null && requested != ChargeIssuer.PlanType.LUMP_SUM) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PARTIAL_PAYMENT_NOT_ALLOWED");
            }
            return ChargeIssuer.PlanType.LUMP_SUM;
        }
        if (requested != null) return requested;
        return switch (product.getDefaultPaymentPlan()) {
            case LUMP_SUM -> ChargeIssuer.PlanType.LUMP_SUM;
            case INSTALLMENT -> ChargeIssuer.PlanType.INSTALLMENT;
            case SELECT_AT_ISSUE -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "CHARGE_PLAN_SELECTION_REQUIRED");
        };
    }

    static List<ChargeIssuer.Installment> buildInstallments(
            MembershipProductEntity product,
            ChargeIssuer.PlanType planType,
            long contractAmountWon,
            LocalDate firstDueOn,
            List<InstallmentCommand> requested
    ) {
        if (planType != ChargeIssuer.PlanType.INSTALLMENT) {
            if (requested != null && !requested.isEmpty()) badRequest("INSTALLMENTS_ONLY_FOR_INSTALLMENT_PLAN");
            return List.of(new ChargeIssuer.Installment(1, firstDueOn, contractAmountWon));
        }
        if (requested == null || requested.size() < 2) badRequest("INSTALLMENT_SCHEDULE_REQUIRED");
        if (product.getMaxInstallmentCount() == null || requested.size() > product.getMaxInstallmentCount()) {
            badRequest("MAX_INSTALLMENT_COUNT_EXCEEDED");
        }
        var sorted = requested.stream().sorted(java.util.Comparator.comparingInt(InstallmentCommand::installmentNo)).toList();
        long total = 0;
        for (int index = 0; index < sorted.size(); index++) {
            var installment = sorted.get(index);
            if (installment.installmentNo() != index + 1) badRequest("INSTALLMENT_NUMBER_INVALID");
            if (installment.amountWon() <= 0) badRequest("INSTALLMENT_AMOUNT_INVALID");
            if (index > 0 && installment.dueOn().isBefore(sorted.get(index - 1).dueOn())) {
                badRequest("INSTALLMENT_DUE_DATE_ORDER_INVALID");
            }
            try {
                total = Math.addExact(total, installment.amountWon());
            } catch (ArithmeticException exception) {
                badRequest("INSTALLMENT_AMOUNT_OVERFLOW");
            }
        }
        if (!sorted.getFirst().dueOn().equals(firstDueOn)) badRequest("FIRST_DUE_DATE_MISMATCH");
        if (total != contractAmountWon) badRequest("INSTALLMENT_TOTAL_MISMATCH");
        return sorted.stream().map(item -> new ChargeIssuer.Installment(
                item.installmentNo(), item.dueOn(), item.amountWon())).toList();
    }

    private static Map<String, Object> termsSnapshot(
            MembershipProductEntity product, PromotionEntity promotion, ChargeIssuer.PlanType planType
    ) {
        var values = new LinkedHashMap<String, Object>();
        values.put("productName", product.getName());
        values.put("productType", product.getProductType().name());
        put(values, "durationValue", product.getDurationValue());
        put(values, "durationUnit", enumName(product.getDurationUnit()));
        put(values, "validityValue", product.getValidityValue());
        put(values, "validityUnit", enumName(product.getValidityUnit()));
        put(values, "totalCount", product.getTotalCount());
        values.put("partialPaymentAllowed", product.isPartialPaymentAllowed());
        values.put("defaultPaymentPlan", product.getDefaultPaymentPlan().name());
        values.put("selectedChargePlanType", planType.name());
        put(values, "maxInstallmentCount", product.getMaxInstallmentCount());
        put(values, "minimumInitialPaymentType", enumName(product.getMinimumInitialPaymentType()));
        put(values, "minimumInitialPaymentValue", product.getMinimumInitialPaymentValue());
        values.put("useBeforeFullPaymentAllowed", product.isUseBeforeFullPaymentAllowed());
        values.put("overdueAttendancePolicy", product.getOverdueAttendancePolicy().name());
        values.put("pauseAllowed", product.isPauseAllowed());
        put(values, "maxPauseCount", product.getMaxPauseCount());
        put(values, "maxPauseDaysPerPause", product.getMaxPauseDaysPerPause());
        put(values, "maxTotalPauseDays", product.getMaxTotalPauseDays());
        put(values, "minimumUseDaysBeforePause", product.getMinimumUseDaysBeforePause());
        values.put("extendExpiryOnPause", product.isExtendExpiryOnPause());
        if (promotion != null) {
            values.put("promotionName", promotion.getName());
            values.put("promotionDiscountType", promotion.getDiscountType().name());
            values.put("promotionDiscountValue", promotion.getDiscountValue());
        }
        return values;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private static Map<String, Object> auditValues(MembershipEntity membership, long chargeId) {
        var values = new LinkedHashMap<String, Object>();
        values.put("memberId", membership.getMemberId());
        values.put("productId", membership.getProductId());
        put(values, "promotionId", membership.getPromotionId());
        values.put("status", membership.getStatus().name());
        values.put("startDate", membership.getStartDate().toString());
        values.put("endDate", membership.getEndDate().toString());
        values.put("contractAmountWon", membership.getContractAmountWon());
        values.put("chargeId", chargeId);
        return values;
    }

    private static IssuedMembershipView toView(MembershipEntity membership, ChargeIssuer.ChargeSnapshot charge) {
        return new IssuedMembershipView(membership.getId(), membership.getMemberId(), membership.getProductId(),
                membership.getPromotionId(), membership.getProductType(), membership.getStatus(),
                membership.getStartDate(), membership.getEndDate(), membership.getTotalCount(),
                membership.getRemainingCount(), membership.getListPriceWon(), membership.getDiscountWon(),
                membership.getContractAmountWon(), membership.getTermsSnapshot(), membership.getIssuedAt(),
                new ChargeView(charge.id(), charge.planType(), charge.contractAmountWon(), charge.paidAmountWon(),
                        charge.balanceWon(), charge.status(), charge.minimumInitialPaymentType(),
                        charge.minimumInitialPaymentValue(), charge.firstDueOn(), charge.installments()));
    }

    private static void assertSameRequest(
            MembershipEntity existing, ChargeIssuer.ChargeSnapshot charge, long memberId, IssueCommand command
    ) {
        if (!Objects.equals(existing.getMemberId(), memberId)
                || !Objects.equals(existing.getProductId(), command.productId())
                || !Objects.equals(existing.getPromotionId(), command.promotionId())
                || !existing.getStartDate().equals(command.startDate())
                || !charge.firstDueOn().equals(command.firstDueOn())
                || (command.planType() != null && charge.planType() != command.planType())
                || !sameRequestedInstallments(charge.installments(), command.installments())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        }
    }

    private static boolean sameRequestedInstallments(
            List<ChargeIssuer.Installment> existing, List<InstallmentCommand> requested
    ) {
        if (requested == null || requested.isEmpty()) return true;
        if (existing.size() != requested.size()) return false;
        var sorted = requested.stream().sorted(java.util.Comparator.comparingInt(InstallmentCommand::installmentNo)).toList();
        for (int index = 0; index < existing.size(); index++) {
            var left = existing.get(index);
            var right = sorted.get(index);
            if (left.installmentNo() != right.installmentNo()
                    || !left.dueOn().equals(right.dueOn())
                    || left.amountWon() != right.amountWon()) return false;
        }
        return true;
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100) badRequest("INVALID_IDEMPOTENCY_KEY");
    }

    private static void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException membershipNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
    }

    record IssueCommand(
            long productId,
            Long promotionId,
            LocalDate startDate,
            ChargeIssuer.PlanType planType,
            LocalDate firstDueOn,
            List<InstallmentCommand> installments
    ) {
    }

    record InstallmentCommand(int installmentNo, LocalDate dueOn, long amountWon) {
    }

    record ChargeView(
            long id,
            ChargeIssuer.PlanType planType,
            long contractAmountWon,
            long paidAmountWon,
            long balanceWon,
            String status,
            ChargeIssuer.MinimumPaymentType minimumInitialPaymentType,
            Long minimumInitialPaymentValue,
            LocalDate firstDueOn,
            List<ChargeIssuer.Installment> installments
    ) {
    }

    record IssuedMembershipView(
            long id,
            long memberId,
            long productId,
            Long promotionId,
            ProductType productType,
            MembershipStatus status,
            LocalDate startDate,
            LocalDate endDate,
            Integer totalCount,
            Integer remainingCount,
            long listPriceWon,
            long discountWon,
            long contractAmountWon,
            Map<String, Object> termsSnapshot,
            Instant issuedAt,
            ChargeView charge
    ) {
    }
}

@RestController
@RequestMapping("/api/members/{memberId}/memberships")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('MEMBER_MANAGE')")
public class MembershipIssuanceController {
    private final MembershipIssuanceService service;

    MembershipIssuanceController(MembershipIssuanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<MembershipIssuanceService.IssuedMembershipView> list(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long memberId
    ) {
        return service.list(principal.gymId(), memberId);
    }

    @GetMapping("/{membershipId}")
    public MembershipIssuanceService.IssuedMembershipView get(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long memberId,
            @PathVariable long membershipId
    ) {
        return service.get(principal.gymId(), memberId, membershipId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipIssuanceService.IssuedMembershipView issue(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long memberId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody IssueMembershipRequest request
    ) {
        return service.issue(
                principal.gymId(), principal.accountId(), memberId, idempotencyKey.trim(), request.toCommand());
    }

    public record IssueMembershipRequest(
            @Positive long productId,
            @Positive Long promotionId,
            @NotNull LocalDate startDate,
            ChargeIssuer.PlanType planType,
            @NotNull LocalDate firstDueOn,
            List<@Valid InstallmentRequest> installments
    ) {
        MembershipIssuanceService.IssueCommand toCommand() {
            return new MembershipIssuanceService.IssueCommand(productId, promotionId, startDate, planType,
                    firstDueOn, installments == null ? List.of() : installments.stream()
                    .map(InstallmentRequest::toCommand).toList());
        }
    }

    public record InstallmentRequest(
            @Positive int installmentNo,
            @NotNull LocalDate dueOn,
            @Positive long amountWon
    ) {
        MembershipIssuanceService.InstallmentCommand toCommand() {
            return new MembershipIssuanceService.InstallmentCommand(installmentNo, dueOn, amountWon);
        }
    }
}

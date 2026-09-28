package com.gym.management.membership.internal;

import com.gym.management.auth.AuditTrail;
import com.gym.management.auth.AuthenticatedStaff;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

interface MembershipProductRepository extends JpaRepository<MembershipProductEntity, Long> {
    @Query("""
            select product from MembershipProductEntity product
             where product.gymId = :gymId
               and (:productType is null or product.productType = :productType)
               and (:saleStatus is null or product.saleStatus = :saleStatus)
             order by product.saleStatus asc, product.name asc, product.id asc
            """)
    List<MembershipProductEntity> search(
            @Param("gymId") long gymId,
            @Param("productType") ProductType productType,
            @Param("saleStatus") SaleStatus saleStatus
    );

    Optional<MembershipProductEntity> findByIdAndGymId(Long id, Long gymId);

    List<MembershipProductEntity> findAllByIdInAndGymId(Set<Long> ids, Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select product from MembershipProductEntity product
             where product.gymId = :gymId and product.id in :ids
             order by product.id
            """)
    List<MembershipProductEntity> findAllForPromotionValidation(
            @Param("ids") Set<Long> ids, @Param("gymId") long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from MembershipProductEntity product where product.id = :productId and product.gymId = :gymId")
    Optional<MembershipProductEntity> findForUpdateByIdAndGymId(
            @Param("productId") long productId, @Param("gymId") long gymId);

    @Query("""
            select (count(product) > 0) from MembershipProductEntity product
             where product.gymId = :gymId
               and lower(product.name) = lower(:name)
               and (:excludedId is null or product.id <> :excludedId)
            """)
    boolean existsDuplicateName(
            @Param("gymId") long gymId, @Param("name") String name, @Param("excludedId") Long excludedId);

    @Query("""
            select (count(promotion) > 0)
              from PromotionProductEntity link, PromotionEntity promotion
             where link.id.productId = :productId
               and promotion.id = link.id.promotionId
               and promotion.discountType = com.gym.management.membership.internal.DiscountType.AMOUNT
               and promotion.discountValue >= :newPrice
            """)
    boolean conflictsWithAmountPromotion(@Param("productId") long productId, @Param("newPrice") long newPrice);
}

interface PromotionRepository extends JpaRepository<PromotionEntity, Long> {
    List<PromotionEntity> findAllByGymIdOrderByStartsOnDescIdDesc(Long gymId);

    Optional<PromotionEntity> findByIdAndGymId(Long id, Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select promotion from PromotionEntity promotion where promotion.id = :promotionId and promotion.gymId = :gymId")
    Optional<PromotionEntity> findForUpdateByIdAndGymId(
            @Param("promotionId") long promotionId, @Param("gymId") long gymId);

    @Query("""
            select (count(promotion) > 0) from PromotionEntity promotion
             where promotion.gymId = :gymId
               and lower(promotion.name) = lower(:name)
               and (:excludedId is null or promotion.id <> :excludedId)
            """)
    boolean existsDuplicateName(
            @Param("gymId") long gymId, @Param("name") String name, @Param("excludedId") Long excludedId);
}

interface PromotionProductRepository extends JpaRepository<PromotionProductEntity, PromotionProductId> {
    @Query("select link.id.productId from PromotionProductEntity link where link.id.promotionId = :promotionId order by link.id.productId")
    List<Long> findProductIdsForPromotion(@Param("promotionId") long promotionId);

    @Modifying
    @Query("delete from PromotionProductEntity link where link.id.promotionId = :promotionId")
    void deleteAllForPromotion(@Param("promotionId") long promotionId);
}

@Service
class MembershipCatalogService {
    private final MembershipProductRepository products;
    private final PromotionRepository promotions;
    private final PromotionProductRepository promotionProducts;
    private final AuditTrail auditTrail;
    private final Clock clock;

    MembershipCatalogService(
            MembershipProductRepository products,
            PromotionRepository promotions,
            PromotionProductRepository promotionProducts,
            AuditTrail auditTrail
    ) {
        this.products = products;
        this.promotions = promotions;
        this.promotionProducts = promotionProducts;
        this.auditTrail = auditTrail;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    List<ProductView> listProducts(long gymId, ProductType productType, SaleStatus saleStatus) {
        return products.search(gymId, productType, saleStatus).stream().map(MembershipCatalogService::toView).toList();
    }

    @Transactional(readOnly = true)
    ProductView getProduct(long gymId, long productId) {
        return toView(products.findByIdAndGymId(productId, gymId).orElseThrow(MembershipCatalogService::productNotFound));
    }

    @Transactional
    ProductView createProduct(long gymId, long actorAccountId, ProductCommand rawCommand) {
        var command = normalizeAndValidate(rawCommand);
        assertProductNameAvailable(gymId, command.name(), null);
        var now = clock.instant();
        MembershipProductEntity product;
        try {
            product = products.saveAndFlush(createProductEntity(gymId, actorAccountId, command, now));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateProductName();
        }
        auditTrail.record(gymId, actorAccountId, "MEMBERSHIP", "PRODUCT_CREATED", "MEMBERSHIP_PRODUCT",
                Long.toString(product.getId()), null, null, productAuditValues(product), now);
        return toView(product);
    }

    @Transactional
    ProductView updateProduct(long gymId, long actorAccountId, long productId, ProductCommand rawCommand) {
        var command = normalizeAndValidate(rawCommand);
        var product = products.findForUpdateByIdAndGymId(productId, gymId)
                .orElseThrow(MembershipCatalogService::productNotFound);
        assertProductNameAvailable(gymId, command.name(), productId);
        if (products.conflictsWithAmountPromotion(productId, command.listPriceWon())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_PRICE_CONFLICTS_WITH_PROMOTION");
        }
        var before = productAuditValues(product);
        var now = clock.instant();
        applyProduct(product, command, actorAccountId, now);
        try {
            products.flush();
        } catch (DataIntegrityViolationException exception) {
            throw duplicateProductName();
        }
        auditTrail.record(gymId, actorAccountId, "MEMBERSHIP", "PRODUCT_UPDATED", "MEMBERSHIP_PRODUCT",
                Long.toString(productId), null, before, productAuditValues(product), now);
        return toView(product);
    }

    @Transactional(readOnly = true)
    List<PromotionView> listPromotions(long gymId) {
        return promotions.findAllByGymIdOrderByStartsOnDescIdDesc(gymId).stream()
                .map(promotion -> toView(promotion, linkedProducts(gymId, promotion.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    PromotionView getPromotion(long gymId, long promotionId) {
        var promotion = promotions.findByIdAndGymId(promotionId, gymId)
                .orElseThrow(MembershipCatalogService::promotionNotFound);
        return toView(promotion, linkedProducts(gymId, promotionId));
    }

    @Transactional
    PromotionView createPromotion(long gymId, long actorAccountId, PromotionCommand command) {
        var selectedProducts = validatePromotion(gymId, command);
        assertPromotionNameAvailable(gymId, command.name(), null);
        var now = clock.instant();
        PromotionEntity promotion;
        try {
            promotion = promotions.saveAndFlush(PromotionEntity.create(
                    gymId, command.name().trim(), command.startsOn(), command.endsOn(), command.discountType(),
                    command.discountValue(), command.status(), clean(command.adminMemo()), actorAccountId, now));
        } catch (DataIntegrityViolationException exception) {
            throw duplicatePromotionName();
        }
        savePromotionProducts(promotion.getId(), selectedProducts, actorAccountId, now);
        auditTrail.record(gymId, actorAccountId, "MEMBERSHIP", "PROMOTION_CREATED", "PROMOTION",
                Long.toString(promotion.getId()), null, null, promotionAuditValues(promotion, command.productIds()), now);
        return toView(promotion, selectedProducts);
    }

    @Transactional
    PromotionView updatePromotion(
            long gymId, long actorAccountId, long promotionId, PromotionCommand command
    ) {
        var selectedProducts = validatePromotion(gymId, command);
        var promotion = promotions.findForUpdateByIdAndGymId(promotionId, gymId)
                .orElseThrow(MembershipCatalogService::promotionNotFound);
        assertPromotionNameAvailable(gymId, command.name(), promotionId);
        var oldProductIds = Set.copyOf(promotionProducts.findProductIdsForPromotion(promotionId));
        var before = promotionAuditValues(promotion, oldProductIds);
        var now = clock.instant();
        promotion.update(command.name().trim(), command.startsOn(), command.endsOn(), command.discountType(),
                command.discountValue(), command.status(), clean(command.adminMemo()), actorAccountId, now);
        promotionProducts.deleteAllForPromotion(promotionId);
        promotionProducts.flush();
        savePromotionProducts(promotionId, selectedProducts, actorAccountId, now);
        try {
            promotions.flush();
        } catch (DataIntegrityViolationException exception) {
            throw duplicatePromotionName();
        }
        auditTrail.record(gymId, actorAccountId, "MEMBERSHIP", "PROMOTION_UPDATED", "PROMOTION",
                Long.toString(promotionId), null, before, promotionAuditValues(promotion, command.productIds()), now);
        return toView(promotion, selectedProducts);
    }

    private List<MembershipProductEntity> validatePromotion(long gymId, PromotionCommand command) {
        if (command.startsOn().isAfter(command.endsOn())) badRequest("PROMOTION_PERIOD_INVALID");
        if (command.discountValue() <= 0) badRequest("DISCOUNT_VALUE_MUST_BE_POSITIVE");
        if (command.discountType() == DiscountType.RATE && command.discountValue() > 100) {
            badRequest("DISCOUNT_RATE_EXCEEDS_100");
        }
        var uniqueIds = Set.copyOf(command.productIds());
        if (uniqueIds.size() != command.productIds().size()) badRequest("DUPLICATE_PROMOTION_PRODUCT");
        var selectedProducts = products.findAllForPromotionValidation(uniqueIds, gymId);
        if (selectedProducts.size() != uniqueIds.size()) badRequest("PROMOTION_PRODUCT_NOT_FOUND");
        if (command.discountType() == DiscountType.AMOUNT && selectedProducts.stream()
                .anyMatch(product -> command.discountValue() >= product.getListPriceWon())) {
            badRequest("DISCOUNT_AMOUNT_MUST_BE_LESS_THAN_PRODUCT_PRICE");
        }
        return selectedProducts.stream()
                .sorted(java.util.Comparator.comparing(MembershipProductEntity::getName)
                        .thenComparing(MembershipProductEntity::getId))
                .toList();
    }

    static ProductCommand normalizeAndValidate(ProductCommand command) {
        Integer durationValue = null;
        PeriodUnit durationUnit = null;
        Integer validityValue = null;
        PeriodUnit validityUnit = null;
        Integer totalCount = null;
        switch (command.productType()) {
            case PERIOD -> {
                requirePositivePeriod(command.durationValue(), command.durationUnit(), "DURATION_REQUIRED");
                durationValue = command.durationValue();
                durationUnit = command.durationUnit();
            }
            case COUNT -> {
                requirePositivePeriod(command.validityValue(), command.validityUnit(), "VALIDITY_REQUIRED");
                requirePositive(command.totalCount(), "TOTAL_COUNT_REQUIRED");
                validityValue = command.validityValue();
                validityUnit = command.validityUnit();
                totalCount = command.totalCount();
            }
            case HYBRID -> {
                requirePositivePeriod(command.durationValue(), command.durationUnit(), "DURATION_REQUIRED");
                requirePositive(command.totalCount(), "TOTAL_COUNT_REQUIRED");
                durationValue = command.durationValue();
                durationUnit = command.durationUnit();
                totalCount = command.totalCount();
            }
        }
        if (command.listPriceWon() < 0) badRequest("LIST_PRICE_MUST_NOT_BE_NEGATIVE");

        var paymentPlan = PaymentPlan.LUMP_SUM;
        Integer maxInstallmentCount = null;
        InitialPaymentType initialPaymentType = null;
        Long initialPaymentValue = null;
        var useBeforeFullPaymentAllowed = false;
        if (command.partialPaymentAllowed()) {
            if (command.defaultPaymentPlan() == null) badRequest("DEFAULT_PAYMENT_PLAN_REQUIRED");
            paymentPlan = command.defaultPaymentPlan();
            if (command.maxInstallmentCount() == null || command.maxInstallmentCount() < 2) {
                badRequest("MAX_INSTALLMENT_COUNT_INVALID");
            }
            if (command.minimumInitialPaymentType() == null || command.minimumInitialPaymentValue() == null
                    || command.minimumInitialPaymentValue() <= 0) {
                badRequest("MINIMUM_INITIAL_PAYMENT_REQUIRED");
            }
            if (command.minimumInitialPaymentType() == InitialPaymentType.RATE
                    && command.minimumInitialPaymentValue() > 100) {
                badRequest("MINIMUM_INITIAL_RATE_EXCEEDS_100");
            }
            if (command.minimumInitialPaymentType() == InitialPaymentType.AMOUNT
                    && command.minimumInitialPaymentValue() > command.listPriceWon()) {
                badRequest("MINIMUM_INITIAL_AMOUNT_EXCEEDS_PRICE");
            }
            maxInstallmentCount = command.maxInstallmentCount();
            initialPaymentType = command.minimumInitialPaymentType();
            initialPaymentValue = command.minimumInitialPaymentValue();
            useBeforeFullPaymentAllowed = command.useBeforeFullPaymentAllowed();
        }

        Integer maxPauseCount = null;
        Integer maxPauseDaysPerPause = null;
        Integer maxTotalPauseDays = null;
        Integer minimumUseDaysBeforePause = null;
        var extendExpiryOnPause = false;
        if (command.pauseAllowed()) {
            requirePositive(command.maxPauseCount(), "MAX_PAUSE_COUNT_REQUIRED");
            requirePositive(command.maxPauseDaysPerPause(), "MAX_PAUSE_DAYS_REQUIRED");
            requirePositive(command.maxTotalPauseDays(), "MAX_TOTAL_PAUSE_DAYS_REQUIRED");
            if (command.maxPauseDaysPerPause() > command.maxTotalPauseDays()) {
                badRequest("PAUSE_DAYS_EXCEED_TOTAL");
            }
            if (command.minimumUseDaysBeforePause() == null || command.minimumUseDaysBeforePause() < 0) {
                badRequest("MINIMUM_USE_DAYS_INVALID");
            }
            maxPauseCount = command.maxPauseCount();
            maxPauseDaysPerPause = command.maxPauseDaysPerPause();
            maxTotalPauseDays = command.maxTotalPauseDays();
            minimumUseDaysBeforePause = command.minimumUseDaysBeforePause();
            extendExpiryOnPause = command.extendExpiryOnPause();
        }
        return new ProductCommand(command.name().trim(), command.productType(), durationValue, durationUnit,
                validityValue, validityUnit, totalCount, command.listPriceWon(), command.partialPaymentAllowed(),
                paymentPlan, maxInstallmentCount, initialPaymentType, initialPaymentValue,
                useBeforeFullPaymentAllowed, command.overdueAttendancePolicy(), command.pauseAllowed(),
                maxPauseCount, maxPauseDaysPerPause, maxTotalPauseDays, minimumUseDaysBeforePause,
                extendExpiryOnPause, command.saleStatus());
    }

    private void assertProductNameAvailable(long gymId, String name, Long excludedId) {
        if (products.existsDuplicateName(gymId, name.trim(), excludedId)) throw duplicateProductName();
    }

    private void assertPromotionNameAvailable(long gymId, String name, Long excludedId) {
        if (promotions.existsDuplicateName(gymId, name.trim(), excludedId)) throw duplicatePromotionName();
    }

    private MembershipProductEntity createProductEntity(
            long gymId, long actorAccountId, ProductCommand command, Instant now
    ) {
        return MembershipProductEntity.create(gymId, command.name(), command.productType(), command.durationValue(),
                command.durationUnit(), command.validityValue(), command.validityUnit(), command.totalCount(),
                command.listPriceWon(), command.partialPaymentAllowed(), command.defaultPaymentPlan(),
                command.maxInstallmentCount(), command.minimumInitialPaymentType(),
                command.minimumInitialPaymentValue(), command.useBeforeFullPaymentAllowed(),
                command.overdueAttendancePolicy(), command.pauseAllowed(), command.maxPauseCount(),
                command.maxPauseDaysPerPause(), command.maxTotalPauseDays(), command.minimumUseDaysBeforePause(),
                command.extendExpiryOnPause(), command.saleStatus(), actorAccountId, now);
    }

    private void applyProduct(
            MembershipProductEntity product, ProductCommand command, long actorAccountId, Instant now
    ) {
        product.update(command.name(), command.productType(), command.durationValue(), command.durationUnit(),
                command.validityValue(), command.validityUnit(), command.totalCount(), command.listPriceWon(),
                command.partialPaymentAllowed(), command.defaultPaymentPlan(), command.maxInstallmentCount(),
                command.minimumInitialPaymentType(), command.minimumInitialPaymentValue(),
                command.useBeforeFullPaymentAllowed(), command.overdueAttendancePolicy(), command.pauseAllowed(),
                command.maxPauseCount(), command.maxPauseDaysPerPause(), command.maxTotalPauseDays(),
                command.minimumUseDaysBeforePause(), command.extendExpiryOnPause(), command.saleStatus(),
                actorAccountId, now);
    }

    private void savePromotionProducts(
            long promotionId, List<MembershipProductEntity> selectedProducts, long actorAccountId, Instant now
    ) {
        promotionProducts.saveAll(selectedProducts.stream().map(product -> PromotionProductEntity.create(
                promotionId, product.getId(), actorAccountId, now)).toList());
    }

    private List<MembershipProductEntity> linkedProducts(long gymId, long promotionId) {
        var productIds = Set.copyOf(promotionProducts.findProductIdsForPromotion(promotionId));
        if (productIds.isEmpty()) return List.of();
        return products.findAllByIdInAndGymId(productIds, gymId).stream()
                .sorted(java.util.Comparator.comparing(MembershipProductEntity::getName)
                        .thenComparing(MembershipProductEntity::getId))
                .toList();
    }

    private static ProductView toView(MembershipProductEntity product) {
        return new ProductView(product.getId(), product.getName(), product.getProductType(),
                product.getDurationValue(), product.getDurationUnit(), product.getValidityValue(),
                product.getValidityUnit(), product.getTotalCount(), product.getListPriceWon(),
                product.isPartialPaymentAllowed(), product.getDefaultPaymentPlan(), product.getMaxInstallmentCount(),
                product.getMinimumInitialPaymentType(), product.getMinimumInitialPaymentValue(),
                product.isUseBeforeFullPaymentAllowed(), product.getOverdueAttendancePolicy(),
                product.isPauseAllowed(), product.getMaxPauseCount(), product.getMaxPauseDaysPerPause(),
                product.getMaxTotalPauseDays(), product.getMinimumUseDaysBeforePause(),
                product.isExtendExpiryOnPause(), product.getSaleStatus(), product.getCreatedAt(),
                product.getUpdatedAt());
    }

    private static PromotionView toView(PromotionEntity promotion, List<MembershipProductEntity> products) {
        var productViews = products.stream().map(product -> new PromotionProductView(product.getId(),
                product.getName(), product.getListPriceWon(), discountedPrice(product, promotion))).toList();
        return new PromotionView(promotion.getId(), promotion.getName(), promotion.getStartsOn(),
                promotion.getEndsOn(), promotion.getDiscountType(), promotion.getDiscountValue(),
                promotion.getStatus(), promotion.getAdminMemo(), productViews, promotion.getCreatedAt(),
                promotion.getUpdatedAt());
    }

    private static long discountedPrice(MembershipProductEntity product, PromotionEntity promotion) {
        if (promotion.getDiscountType() == DiscountType.AMOUNT) {
            return product.getListPriceWon() - promotion.getDiscountValue();
        }
        return product.getListPriceWon() - (product.getListPriceWon() * promotion.getDiscountValue() / 100);
    }

    private static Map<String, Object> productAuditValues(MembershipProductEntity product) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name", product.getName());
        values.put("productType", product.getProductType().name());
        values.put("listPriceWon", product.getListPriceWon());
        values.put("partialPaymentAllowed", product.isPartialPaymentAllowed());
        values.put("useBeforeFullPaymentAllowed", product.isUseBeforeFullPaymentAllowed());
        values.put("pauseAllowed", product.isPauseAllowed());
        values.put("saleStatus", product.getSaleStatus().name());
        return values;
    }

    private static Map<String, Object> promotionAuditValues(PromotionEntity promotion, Set<Long> productIds) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name", promotion.getName());
        values.put("startsOn", promotion.getStartsOn().toString());
        values.put("endsOn", promotion.getEndsOn().toString());
        values.put("discountType", promotion.getDiscountType().name());
        values.put("discountValue", promotion.getDiscountValue());
        values.put("status", promotion.getStatus().name());
        values.put("productIds", productIds.stream().sorted().toList());
        return values;
    }

    private static void requirePositivePeriod(Integer value, PeriodUnit unit, String code) {
        if (value == null || value <= 0 || unit == null) badRequest(code);
    }

    private static void requirePositive(Integer value, String code) {
        if (value == null || value <= 0) badRequest(code);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException productNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBERSHIP_PRODUCT_NOT_FOUND");
    }

    private static ResponseStatusException promotionNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND");
    }

    private static ResponseStatusException duplicateProductName() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MEMBERSHIP_PRODUCT_NAME_ALREADY_EXISTS");
    }

    private static ResponseStatusException duplicatePromotionName() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "PROMOTION_NAME_ALREADY_EXISTS");
    }

    record ProductCommand(
            String name, ProductType productType, Integer durationValue, PeriodUnit durationUnit,
            Integer validityValue, PeriodUnit validityUnit, Integer totalCount, long listPriceWon,
            boolean partialPaymentAllowed, PaymentPlan defaultPaymentPlan, Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed, AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed, Integer maxPauseCount, Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays, Integer minimumUseDaysBeforePause, boolean extendExpiryOnPause,
            SaleStatus saleStatus
    ) {
    }

    record PromotionCommand(
            String name, LocalDate startsOn, LocalDate endsOn, DiscountType discountType,
            long discountValue, PromotionStatus status, String adminMemo, Set<Long> productIds
    ) {
    }

    record ProductView(
            long id, String name, ProductType productType, Integer durationValue, PeriodUnit durationUnit,
            Integer validityValue, PeriodUnit validityUnit, Integer totalCount, long listPriceWon,
            boolean partialPaymentAllowed, PaymentPlan defaultPaymentPlan, Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType, Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed, AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed, Integer maxPauseCount, Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays, Integer minimumUseDaysBeforePause, boolean extendExpiryOnPause,
            SaleStatus saleStatus, Instant createdAt, Instant updatedAt
    ) {
    }

    record PromotionProductView(long id, String name, long listPriceWon, long expectedSalePriceWon) {
    }

    record PromotionView(
            long id, String name, LocalDate startsOn, LocalDate endsOn, DiscountType discountType,
            long discountValue, PromotionStatus status, String adminMemo, List<PromotionProductView> products,
            Instant createdAt, Instant updatedAt
    ) {
    }
}

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class MembershipCatalogController {
    private final MembershipCatalogService service;

    MembershipCatalogController(MembershipCatalogService service) {
        this.service = service;
    }

    @GetMapping("/api/membership-products")
    public List<MembershipCatalogService.ProductView> listProducts(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) SaleStatus saleStatus
    ) {
        return service.listProducts(principal.gymId(), productType, saleStatus);
    }

    @GetMapping("/api/membership-products/{productId}")
    public MembershipCatalogService.ProductView getProduct(
            @AuthenticationPrincipal AuthenticatedStaff principal, @PathVariable long productId
    ) {
        return service.getProduct(principal.gymId(), productId);
    }

    @PostMapping("/api/membership-products")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipCatalogService.ProductView createProduct(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody ProductRequest request
    ) {
        return service.createProduct(principal.gymId(), principal.accountId(), request.toCommand());
    }

    @PutMapping("/api/membership-products/{productId}")
    public MembershipCatalogService.ProductView updateProduct(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long productId,
            @Valid @RequestBody ProductRequest request
    ) {
        return service.updateProduct(principal.gymId(), principal.accountId(), productId, request.toCommand());
    }

    @GetMapping("/api/promotions")
    public List<MembershipCatalogService.PromotionView> listPromotions(
            @AuthenticationPrincipal AuthenticatedStaff principal
    ) {
        return service.listPromotions(principal.gymId());
    }

    @GetMapping("/api/promotions/{promotionId}")
    public MembershipCatalogService.PromotionView getPromotion(
            @AuthenticationPrincipal AuthenticatedStaff principal, @PathVariable long promotionId
    ) {
        return service.getPromotion(principal.gymId(), promotionId);
    }

    @PostMapping("/api/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipCatalogService.PromotionView createPromotion(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody PromotionRequest request
    ) {
        return service.createPromotion(principal.gymId(), principal.accountId(), request.toCommand());
    }

    @PutMapping("/api/promotions/{promotionId}")
    public MembershipCatalogService.PromotionView updatePromotion(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long promotionId,
            @Valid @RequestBody PromotionRequest request
    ) {
        return service.updatePromotion(
                principal.gymId(), principal.accountId(), promotionId, request.toCommand());
    }

    public record ProductRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull ProductType productType,
            Integer durationValue,
            PeriodUnit durationUnit,
            Integer validityValue,
            PeriodUnit validityUnit,
            Integer totalCount,
            @PositiveOrZero long listPriceWon,
            boolean partialPaymentAllowed,
            PaymentPlan defaultPaymentPlan,
            Integer maxInstallmentCount,
            InitialPaymentType minimumInitialPaymentType,
            Long minimumInitialPaymentValue,
            boolean useBeforeFullPaymentAllowed,
            @NotNull AttendancePaymentPolicy overdueAttendancePolicy,
            boolean pauseAllowed,
            Integer maxPauseCount,
            Integer maxPauseDaysPerPause,
            Integer maxTotalPauseDays,
            Integer minimumUseDaysBeforePause,
            boolean extendExpiryOnPause,
            @NotNull SaleStatus saleStatus
    ) {
        MembershipCatalogService.ProductCommand toCommand() {
            return new MembershipCatalogService.ProductCommand(name, productType, durationValue, durationUnit,
                    validityValue, validityUnit, totalCount, listPriceWon, partialPaymentAllowed,
                    defaultPaymentPlan, maxInstallmentCount, minimumInitialPaymentType,
                    minimumInitialPaymentValue, useBeforeFullPaymentAllowed, overdueAttendancePolicy,
                    pauseAllowed, maxPauseCount, maxPauseDaysPerPause, maxTotalPauseDays,
                    minimumUseDaysBeforePause, extendExpiryOnPause, saleStatus);
        }
    }

    public record PromotionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            @NotNull DiscountType discountType,
            @Positive long discountValue,
            @NotNull PromotionStatus status,
            @Size(max = 2000) String adminMemo,
            @NotEmpty Set<@Positive Long> productIds
    ) {
        MembershipCatalogService.PromotionCommand toCommand() {
            return new MembershipCatalogService.PromotionCommand(name, startsOn, endsOn, discountType,
                    discountValue, status, adminMemo, productIds);
        }
    }
}

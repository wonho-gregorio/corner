package com.gym.management.auth.internal;

import com.gym.management.auth.AuthenticatedStaff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/gym")
@PreAuthorize("hasRole('ADMIN')")
public class GymSettingsController {
    private final GymSettingsService gymSettingsService;

    GymSettingsController(GymSettingsService gymSettingsService) {
        this.gymSettingsService = gymSettingsService;
    }

    @GetMapping
    public GymSettingsView get(@AuthenticationPrincipal AuthenticatedStaff principal) {
        return gymSettingsService.get(principal.gymId());
    }

    @PutMapping
    public GymSettingsView update(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody GymSettingsRequest request
    ) {
        return gymSettingsService.update(
                principal.gymId(), principal.accountId(), request.name(), request.representativePhone(),
                request.address(), request.addressDetail(), request.checkoutEnabled());
    }

    public record GymSettingsRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String representativePhone,
            @Size(max = 300) String address,
            @Size(max = 200) String addressDetail,
            boolean checkoutEnabled
    ) {
    }

    public record GymSettingsView(
            long id,
            String name,
            String representativePhone,
            String address,
            String addressDetail,
            boolean checkoutEnabled,
            Instant updatedAt
    ) {
    }
}

@Service
class GymSettingsService {
    private final GymRepository gyms;
    private final AuditLogRepository auditLogs;
    private final Clock clock;

    GymSettingsService(GymRepository gyms, AuditLogRepository auditLogs) {
        this.gyms = gyms;
        this.auditLogs = auditLogs;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    GymSettingsController.GymSettingsView get(long gymId) {
        return toView(gyms.findById(gymId).orElseThrow(GymSettingsService::notFound));
    }

    @Transactional
    GymSettingsController.GymSettingsView update(
            long gymId,
            long actorAccountId,
            String name,
            String representativePhone,
            String address,
            String addressDetail,
            boolean checkoutEnabled
    ) {
        var gym = gyms.findForUpdateById(gymId).orElseThrow(GymSettingsService::notFound);
        var before = auditValues(gym);
        var now = clock.instant();
        gym.updateSettings(
                name.trim(), nullableTrim(representativePhone), nullableTrim(address), nullableTrim(addressDetail),
                checkoutEnabled, actorAccountId, now);
        auditLogs.save(AuditLogEntity.create(
                gymId, actorAccountId, "GYM_SETTINGS_UPDATED", "GYM", Long.toString(gymId),
                before, auditValues(gym), now));
        return toView(gym);
    }

    private static Map<String, Object> auditValues(GymEntity gym) {
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("name", gym.getName());
        values.put("representativePhone", gym.getRepresentativePhone());
        values.put("address", gym.getAddress());
        values.put("addressDetail", gym.getAddressDetail());
        values.put("checkoutEnabled", gym.isCheckoutEnabled());
        return values;
    }

    private static GymSettingsController.GymSettingsView toView(GymEntity gym) {
        return new GymSettingsController.GymSettingsView(
                gym.getId(), gym.getName(), gym.getRepresentativePhone(), gym.getAddress(), gym.getAddressDetail(),
                gym.isCheckoutEnabled(), gym.getUpdatedAt());
    }

    private static String nullableTrim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "GYM_NOT_FOUND");
    }
}

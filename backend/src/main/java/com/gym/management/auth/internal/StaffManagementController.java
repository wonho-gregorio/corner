package com.gym.management.auth.internal;

import com.gym.management.auth.AuthenticatedStaff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasRole('ADMIN')")
public class StaffManagementController {
    private final StaffManagementService staffManagementService;

    StaffManagementController(StaffManagementService staffManagementService) {
        this.staffManagementService = staffManagementService;
    }

    @GetMapping
    public List<StaffManagementService.StaffView> list(@AuthenticationPrincipal AuthenticatedStaff principal) {
        return staffManagementService.list(principal.gymId());
    }

    @GetMapping("/{accountId}")
    public StaffManagementService.StaffView get(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long accountId
    ) {
        return staffManagementService.get(principal.gymId(), accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffManagementService.CreatedStaff create(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody StaffRequest request
    ) {
        return staffManagementService.create(principal.gymId(), principal.accountId(), request.toCommand());
    }

    @PutMapping("/{accountId}")
    public StaffManagementService.StaffView update(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long accountId,
            @Valid @RequestBody StaffRequest request
    ) {
        return staffManagementService.update(
                principal.gymId(), principal.accountId(), accountId, request.toCommand());
    }

    @PostMapping("/{accountId}/temporary-password")
    public StaffManagementService.TemporaryPasswordResult reissueTemporaryPassword(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @PathVariable long accountId
    ) {
        return staffManagementService.reissueTemporaryPassword(
                principal.gymId(), principal.accountId(), accountId);
    }

    record StaffRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$") String loginId,
            @NotBlank @Size(max = 100) String name,
            @NotNull StaffRole role,
            @NotNull StaffStatus status,
            @NotNull Set<StaffPermission> permissions
    ) {
        StaffManagementService.StaffCommand toCommand() {
            return new StaffManagementService.StaffCommand(loginId, name, role, status, permissions);
        }
    }
}

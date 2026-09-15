package com.gym.management.auth.internal;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffManagementTests {
    @Test
    void generatedTemporaryPasswordIsOneTimeQualityInput() {
        var first = StaffManagementService.generateTemporaryPassword();
        var second = StaffManagementService.generateTemporaryPassword();

        assertThat(first).hasSize(16).matches("^[A-Za-z2-9]+$");
        assertThat(second).hasSize(16).isNotEqualTo(first);
    }

    @Test
    void deactivationInvalidatesTheAccountSessionVersion() {
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var account = StaffAccountEntity.create(
                1L, "staff01", "hash", "직원", StaffRole.STAFF, StaffStatus.ACTIVE, 1L, now);

        account.updateProfile("staff01", "직원", StaffRole.STAFF, StaffStatus.INACTIVE, 1L, now.plusSeconds(1));

        assertThat(account.getSessionVersion()).isEqualTo(1L);
        assertThat(account.isActive()).isFalse();
    }

    @Test
    void cannotRemoveTheLastActiveAdministrator() {
        var gyms = mock(GymRepository.class);
        var accounts = mock(StaffAccountRepository.class);
        var permissions = mock(StaffPermissionRepository.class);
        var refreshSessions = mock(AuthRefreshSessionRepository.class);
        var auditLogs = mock(AuditLogRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var service = new StaffManagementService(
                gyms, accounts, permissions, refreshSessions, auditLogs, passwordEncoder);
        var now = Instant.parse("2026-09-15T00:00:00Z");
        var administrator = StaffAccountEntity.createInitialAdmin(1L, "admin", "hash", "관리자", now);
        when(gyms.findForUpdateById(1L)).thenReturn(Optional.of(GymEntity.create("도장", null, null, null, now)));
        when(accounts.findForUpdateByIdAndGymId(1L, 1L)).thenReturn(Optional.of(administrator));
        when(accounts.existsDuplicateLoginId(1L, "admin", 1L)).thenReturn(false);
        when(accounts.countActiveAdministrators(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(
                1L,
                1L,
                1L,
                new StaffManagementService.StaffCommand(
                        "admin", "관리자", StaffRole.STAFF, StaffStatus.ACTIVE, Set.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LAST_ACTIVE_ADMIN_REQUIRED");
    }
}

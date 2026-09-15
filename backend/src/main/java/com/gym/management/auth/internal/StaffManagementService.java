package com.gym.management.auth.internal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class StaffManagementService {
    private static final char[] TEMPORARY_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int TEMPORARY_PASSWORD_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final GymRepository gyms;
    private final StaffAccountRepository accounts;
    private final StaffPermissionRepository permissions;
    private final AuthRefreshSessionRepository refreshSessions;
    private final AuditLogRepository auditLogs;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    StaffManagementService(
            GymRepository gyms,
            StaffAccountRepository accounts,
            StaffPermissionRepository permissions,
            AuthRefreshSessionRepository refreshSessions,
            AuditLogRepository auditLogs,
            PasswordEncoder passwordEncoder
    ) {
        this.gyms = gyms;
        this.accounts = accounts;
        this.permissions = permissions;
        this.refreshSessions = refreshSessions;
        this.auditLogs = auditLogs;
        this.passwordEncoder = passwordEncoder;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    List<StaffView> list(long gymId) {
        var staff = accounts.findAllByGymIdOrderByNameAscIdAsc(gymId);
        var permissionsByAccount = permissionMap(staff.stream().map(StaffAccountEntity::getId).toList());
        return staff.stream()
                .map(account -> toView(account, permissionsByAccount.getOrDefault(account.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    StaffView get(long gymId, long accountId) {
        var account = findAccount(gymId, accountId);
        return toView(account, permissionNames(account));
    }

    @Transactional
    CreatedStaff create(long gymId, long actorAccountId, StaffCommand command) {
        gyms.findForUpdateById(gymId).orElseThrow(StaffManagementService::notFound);
        validatePermissions(command.role(), command.permissions());
        assertLoginIdAvailable(gymId, command.loginId(), null);

        var now = clock.instant();
        var temporaryPassword = generateTemporaryPassword();
        try {
            var account = accounts.saveAndFlush(StaffAccountEntity.create(
                    gymId, command.loginId().trim(), passwordEncoder.encode(temporaryPassword), command.name().trim(),
                    command.role(), command.status(), actorAccountId, now));
            replacePermissions(account, command.permissions(), actorAccountId, now);
            auditLogs.save(AuditLogEntity.create(
                    gymId, actorAccountId, "STAFF_CREATED", "STAFF_ACCOUNT", account.getId().toString(), null,
                    auditValues(account, permissionNamesForRole(account.getRole(), command.permissions())), now));
            return new CreatedStaff(toView(account, permissionNamesForRole(account.getRole(), command.permissions())), temporaryPassword);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateLoginId();
        }
    }

    @Transactional
    StaffView update(long gymId, long actorAccountId, long accountId, StaffCommand command) {
        gyms.findForUpdateById(gymId).orElseThrow(StaffManagementService::notFound);
        var account = accounts.findForUpdateByIdAndGymId(accountId, gymId)
                .orElseThrow(StaffManagementService::notFound);
        validatePermissions(command.role(), command.permissions());
        assertLoginIdAvailable(gymId, command.loginId(), accountId);
        protectLastActiveAdministrator(gymId, account, command.role(), command.status());

        var beforePermissions = permissionNames(account);
        var before = auditValues(account, beforePermissions);
        var wasActive = account.isActive();
        var now = clock.instant();
        account.updateProfile(
                command.loginId().trim(), command.name().trim(), command.role(), command.status(), actorAccountId, now);
        try {
            accounts.flush();
        } catch (DataIntegrityViolationException exception) {
            throw duplicateLoginId();
        }
        replacePermissions(account, command.permissions(), actorAccountId, now);
        var effectivePermissions = permissionNamesForRole(account.getRole(), command.permissions());
        if (wasActive && !account.isActive()) {
            refreshSessions.revokeAllByAccountId(accountId, now);
        }
        auditLogs.save(AuditLogEntity.create(
                gymId, actorAccountId, "STAFF_UPDATED", "STAFF_ACCOUNT", Long.toString(accountId), before,
                auditValues(account, effectivePermissions), now));
        return toView(account, effectivePermissions);
    }

    @Transactional
    TemporaryPasswordResult reissueTemporaryPassword(long gymId, long actorAccountId, long accountId) {
        var account = accounts.findForUpdateByIdAndGymId(accountId, gymId)
                .orElseThrow(StaffManagementService::notFound);
        var temporaryPassword = generateTemporaryPassword();
        var now = clock.instant();
        var previousSessionVersion = account.getSessionVersion();
        var previousMustChangePassword = account.isMustChangePassword();
        account.reissueTemporaryPassword(passwordEncoder.encode(temporaryPassword), actorAccountId, now);
        refreshSessions.revokeAllByAccountId(accountId, now);
        auditLogs.save(AuditLogEntity.create(
                gymId, actorAccountId, "TEMPORARY_PASSWORD_REISSUED", "STAFF_ACCOUNT", Long.toString(accountId),
                Map.of("mustChangePassword", previousMustChangePassword, "sessionVersion", previousSessionVersion),
                Map.of("mustChangePassword", true, "sessionVersion", account.getSessionVersion()), now));
        return new TemporaryPasswordResult(accountId, temporaryPassword, true);
    }

    private void protectLastActiveAdministrator(
            long gymId,
            StaffAccountEntity current,
            StaffRole newRole,
            StaffStatus newStatus
    ) {
        var removesActiveAdministrator = current.getRole() == StaffRole.ADMIN
                && current.getStatus() == StaffStatus.ACTIVE
                && (newRole != StaffRole.ADMIN || newStatus != StaffStatus.ACTIVE);
        if (removesActiveAdministrator && accounts.countActiveAdministrators(gymId) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LAST_ACTIVE_ADMIN_REQUIRED");
        }
    }

    private void replacePermissions(
            StaffAccountEntity account,
            Set<StaffPermission> requested,
            long actorAccountId,
            Instant now
    ) {
        permissions.deleteAllByAccountId(account.getId());
        if (account.getRole() == StaffRole.STAFF) {
            permissions.saveAll(requested.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .map(permission -> StaffPermissionEntity.grant(account.getId(), permission, actorAccountId, now))
                    .toList());
        }
    }

    private Map<Long, List<String>> permissionMap(Collection<Long> accountIds) {
        var result = new LinkedHashMap<Long, List<String>>();
        if (accountIds.isEmpty()) {
            return result;
        }
        permissions.findAllByIdStaffAccountIdIn(accountIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        permission -> permission.getId().getStaffAccountId(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                permission -> permission.getId().getPermission().name(),
                                java.util.stream.Collectors.toList())))
                .forEach((accountId, names) -> result.put(accountId, names.stream().sorted().toList()));
        return result;
    }

    private List<String> permissionNames(StaffAccountEntity account) {
        if (account.getRole() == StaffRole.ADMIN) {
            return allPermissionNames();
        }
        return permissions.findAllByIdStaffAccountId(account.getId()).stream()
                .map(permission -> permission.getId().getPermission().name())
                .sorted()
                .toList();
    }

    private static List<String> permissionNamesForRole(StaffRole role, Set<StaffPermission> requested) {
        if (role == StaffRole.ADMIN) {
            return allPermissionNames();
        }
        return requested.stream().map(Enum::name).sorted().toList();
    }

    private static List<String> allPermissionNames() {
        return EnumSet.allOf(StaffPermission.class).stream().map(Enum::name).sorted().toList();
    }

    private static void validatePermissions(StaffRole role, Set<StaffPermission> requested) {
        if (requested == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PERMISSIONS_REQUIRED");
        }
        if (role == StaffRole.ADMIN
                && !requested.isEmpty()
                && !requested.equals(EnumSet.allOf(StaffPermission.class))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN_PERMISSIONS_ARE_FIXED");
        }
    }

    private void assertLoginIdAvailable(long gymId, String loginId, Long excludedId) {
        if (accounts.existsDuplicateLoginId(gymId, loginId.trim(), excludedId)) {
            throw duplicateLoginId();
        }
    }

    private StaffAccountEntity findAccount(long gymId, long accountId) {
        return accounts.findByIdAndGymId(accountId, gymId).orElseThrow(StaffManagementService::notFound);
    }

    private static Map<String, Object> auditValues(StaffAccountEntity account, List<String> effectivePermissions) {
        return Map.of(
                "loginId", account.getLoginId(),
                "name", account.getName(),
                "role", account.getRole().name(),
                "status", account.getStatus().name(),
                "permissions", effectivePermissions,
                "mustChangePassword", account.isMustChangePassword(),
                "sessionVersion", account.getSessionVersion());
    }

    private static StaffView toView(StaffAccountEntity account, List<String> effectivePermissions) {
        return new StaffView(
                account.getId(), account.getLoginId(), account.getName(), account.getRole(), account.getStatus(),
                effectivePermissions, account.isMustChangePassword(), account.getLastLoginAt(), account.getCreatedAt(),
                account.getUpdatedAt());
    }

    static String generateTemporaryPassword() {
        var value = new char[TEMPORARY_PASSWORD_LENGTH];
        for (var index = 0; index < value.length; index++) {
            value[index] = TEMPORARY_PASSWORD_ALPHABET[SECURE_RANDOM.nextInt(TEMPORARY_PASSWORD_ALPHABET.length)];
        }
        return new String(value);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "STAFF_ACCOUNT_NOT_FOUND");
    }

    private static ResponseStatusException duplicateLoginId() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "LOGIN_ID_ALREADY_EXISTS");
    }

    record StaffCommand(
            String loginId,
            String name,
            StaffRole role,
            StaffStatus status,
            Set<StaffPermission> permissions
    ) {
    }

    record StaffView(
            long id,
            String loginId,
            String name,
            StaffRole role,
            StaffStatus status,
            List<String> permissions,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record CreatedStaff(StaffView staff, String temporaryPassword) {
    }

    record TemporaryPasswordResult(long accountId, String temporaryPassword, boolean mustChangePassword) {
    }
}

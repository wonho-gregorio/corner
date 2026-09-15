package com.gym.management.auth.internal;

import com.gym.management.auth.AuthenticatedStaff;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
class AuthService {
    private static final long SETUP_ADVISORY_LOCK_KEY = 72_951_483L;
    private static final List<String> ADMIN_PERMISSIONS = List.of(
            StaffPermission.MEMBER_MANAGE.name(),
            StaffPermission.ATTENDANCE_PROCESS.name(),
            StaffPermission.PAYMENT_REGISTER.name()
    );

    private final GymRepository gyms;
    private final StaffAccountRepository accounts;
    private final StaffPermissionRepository permissions;
    private final AuthRefreshSessionRepository refreshSessions;
    private final AuditLogRepository auditLogs;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthProperties properties;
    private final EntityManager entityManager;
    private final Clock clock;

    AuthService(
            GymRepository gyms,
            StaffAccountRepository accounts,
            StaffPermissionRepository permissions,
            AuthRefreshSessionRepository refreshSessions,
            AuditLogRepository auditLogs,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            AuthProperties properties,
            EntityManager entityManager
    ) {
        this.gyms = gyms;
        this.accounts = accounts;
        this.permissions = permissions;
        this.refreshSessions = refreshSessions;
        this.auditLogs = auditLogs;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.properties = properties;
        this.entityManager = entityManager;
        this.clock = Clock.systemUTC();
    }

    boolean isInitialized() {
        return accounts.count() > 0;
    }

    @Transactional
    AuthResult setup(SetupCommand command, ClientMetadata client) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", SETUP_ADVISORY_LOCK_KEY)
                .getSingleResult();
        if (gyms.count() > 0 || accounts.count() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SETUP_ALREADY_COMPLETED");
        }

        var now = clock.instant();
        var gym = gyms.save(GymEntity.create(
                command.gymName().trim(), normalizedNullable(command.representativePhone()),
                normalizedNullable(command.address()), normalizedNullable(command.addressDetail()), now));
        var account = accounts.save(StaffAccountEntity.createInitialAdmin(
                gym.getId(), command.loginId().trim(), passwordEncoder.encode(command.password()),
                command.adminName().trim(), now));
        gym.assignInitialAdministrator(account.getId(), now);
        account.assignInitialAuditActor(account.getId(), now);

        auditLogs.save(AuditLogEntity.create(
                gym.getId(), account.getId(), "INITIAL_SETUP", "STAFF_ACCOUNT", account.getId().toString(),
                null,
                Map.of("gymId", gym.getId(), "loginId", account.getLoginId(), "role", account.getRole().name()),
                now));
        return issueTokens(account, ADMIN_PERMISSIONS, client, now);
    }

    @Transactional
    AuthResult login(String loginId, String password, ClientMetadata client) {
        var matches = accounts.findByLoginIdIgnoringCase(loginId.trim());
        if (matches.size() != 1) {
            throw invalidCredentials();
        }
        var account = matches.getFirst();
        if (!account.isActive() || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw invalidCredentials();
        }

        var now = clock.instant();
        account.recordLogin(now);
        return issueTokens(account, effectivePermissions(account), client, now);
    }

    @Transactional
    AuthResult refresh(String rawRefreshToken, ClientMetadata client) {
        var now = clock.instant();
        var session = refreshSessions.findForUpdateByTokenHash(tokenService.hashRefreshToken(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (!session.isUsableAt(now)) {
            throw invalidRefreshToken();
        }
        var account = accounts.findById(session.getStaffAccountId()).orElseThrow(AuthService::invalidRefreshToken);
        if (!account.isActive() || session.getSessionVersion() != account.getSessionVersion()) {
            session.revoke(now);
            throw invalidRefreshToken();
        }

        session.revoke(now);
        return issueTokens(account, effectivePermissions(account), client, now);
    }

    @Transactional
    void logout(String rawRefreshToken) {
        var hash = tokenService.hashRefreshToken(rawRefreshToken);
        refreshSessions.findByTokenHash(hash).ifPresent(session -> session.revoke(clock.instant()));
    }

    @Transactional
    AuthResult changePassword(long accountId, String currentPassword, String newPassword, ClientMetadata client) {
        var account = accounts.findById(accountId).orElseThrow(AuthService::invalidCredentials);
        if (!account.isActive() || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NEW_PASSWORD_MUST_DIFFER");
        }

        var now = clock.instant();
        var previousSessionVersion = account.getSessionVersion();
        var previousMustChangePassword = account.isMustChangePassword();
        account.changePassword(passwordEncoder.encode(newPassword), now);
        refreshSessions.revokeAllByAccountId(accountId, now);
        auditLogs.save(AuditLogEntity.create(
                account.getGymId(), accountId, "PASSWORD_CHANGED", "STAFF_ACCOUNT", Long.toString(accountId),
                Map.of("mustChangePassword", previousMustChangePassword, "sessionVersion", previousSessionVersion),
                Map.of("mustChangePassword", false, "sessionVersion", account.getSessionVersion()),
                now));
        return issueTokens(account, effectivePermissions(account), client, now);
    }

    AuthenticatedStaff current(AuthenticatedStaff principal) {
        return principal;
    }

    private AuthResult issueTokens(
            StaffAccountEntity account,
            List<String> effectivePermissions,
            ClientMetadata client,
            Instant now
    ) {
        var rawRefreshToken = tokenService.createRefreshToken();
        var refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshSessions.save(AuthRefreshSessionEntity.create(
                account.getId(), tokenService.hashRefreshToken(rawRefreshToken), account.getSessionVersion(),
                refreshExpiresAt, now, client.ipAddress(), client.userAgent()));
        var accessToken = tokenService.createAccessToken(account, effectivePermissions, now);
        return new AuthResult(
                accessToken,
                rawRefreshToken,
                "Bearer",
                properties.accessTokenTtl().toSeconds(),
                refreshExpiresAt,
                new AccountView(account.getId(), account.getGymId(), account.getLoginId(), account.getName(),
                        account.getRole().name(), effectivePermissions, account.isMustChangePassword()));
    }

    private List<String> effectivePermissions(StaffAccountEntity account) {
        if (account.getRole() == StaffRole.ADMIN) {
            return ADMIN_PERMISSIONS;
        }
        return permissions.findAllByIdStaffAccountId(account.getId()).stream()
                .map(permission -> permission.getId().getPermission().name())
                .sorted()
                .toList();
    }

    private static String normalizedNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    }

    private static ResponseStatusException invalidRefreshToken() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
    }

    record SetupCommand(
            String gymName,
            String representativePhone,
            String address,
            String addressDetail,
            String adminName,
            String loginId,
            String password
    ) {
    }

    record ClientMetadata(String ipAddress, String userAgent) {
    }

    record AccountView(
            long id,
            long gymId,
            String loginId,
            String name,
            String role,
            List<String> permissions,
            boolean mustChangePassword
    ) {
    }

    record AuthResult(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            Instant refreshExpiresAt,
            AccountView account
    ) {
    }
}

package com.gym.management.auth.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Getter
@Entity
@Table(name = "gyms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class GymEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String representativePhone;
    private String address;
    private String addressDetail;
    private String memberNumberFormat;
    private boolean checkoutEnabled;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static GymEntity create(String name, String representativePhone, String address, String addressDetail, Instant now) {
        var gym = new GymEntity();
        gym.name = name;
        gym.representativePhone = representativePhone;
        gym.address = address;
        gym.addressDetail = addressDetail;
        gym.memberNumberFormat = "YYYYMM-####";
        gym.checkoutEnabled = false;
        gym.createdAt = now;
        gym.updatedAt = now;
        return gym;
    }

    void assignInitialAdministrator(long accountId, Instant now) {
        createdBy = accountId;
        updatedBy = accountId;
        updatedAt = now;
    }
}

enum StaffRole { ADMIN, STAFF }
enum StaffStatus { ACTIVE, INACTIVE }
enum StaffPermission { MEMBER_MANAGE, ATTENDANCE_PROCESS, PAYMENT_REGISTER }

@Getter
@Entity
@Table(name = "staff_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StaffAccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String loginId;
    private String passwordHash;
    private String name;
    @Enumerated(EnumType.STRING) private StaffRole role;
    @Enumerated(EnumType.STRING) private StaffStatus status;
    private boolean mustChangePassword;
    private long sessionVersion;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version private long version;

    static StaffAccountEntity createInitialAdmin(long gymId, String loginId, String passwordHash, String name, Instant now) {
        var account = new StaffAccountEntity();
        account.gymId = gymId;
        account.loginId = loginId;
        account.passwordHash = passwordHash;
        account.name = name;
        account.role = StaffRole.ADMIN;
        account.status = StaffStatus.ACTIVE;
        account.mustChangePassword = false;
        account.sessionVersion = 0;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    static StaffAccountEntity create(
            long gymId,
            String loginId,
            String passwordHash,
            String name,
            StaffRole role,
            StaffStatus status,
            long createdBy,
            Instant now
    ) {
        var account = new StaffAccountEntity();
        account.gymId = gymId;
        account.loginId = loginId;
        account.passwordHash = passwordHash;
        account.name = name;
        account.role = role;
        account.status = status;
        account.mustChangePassword = true;
        account.sessionVersion = 0;
        account.createdAt = now;
        account.createdBy = createdBy;
        account.updatedAt = now;
        account.updatedBy = createdBy;
        return account;
    }

    void assignInitialAuditActor(long accountId, Instant now) {
        createdBy = accountId;
        updatedBy = accountId;
        updatedAt = now;
    }

    boolean isActive() {
        return status == StaffStatus.ACTIVE;
    }

    void recordLogin(Instant now) {
        lastLoginAt = now;
        updatedAt = now;
    }

    void changePassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        mustChangePassword = false;
        sessionVersion++;
        updatedAt = now;
        updatedBy = id;
    }

    void updateProfile(
            String loginId,
            String name,
            StaffRole role,
            StaffStatus status,
            long updatedBy,
            Instant now
    ) {
        if (this.status == StaffStatus.ACTIVE && status == StaffStatus.INACTIVE) {
            sessionVersion++;
        }
        this.loginId = loginId;
        this.name = name;
        this.role = role;
        this.status = status;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    void reissueTemporaryPassword(String temporaryPasswordHash, long updatedBy, Instant now) {
        passwordHash = temporaryPasswordHash;
        mustChangePassword = true;
        sessionVersion++;
        this.updatedBy = updatedBy;
        updatedAt = now;
    }
}

@Getter
@Entity
@Table(name = "staff_permissions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StaffPermissionEntity {
    @EmbeddedId
    private StaffPermissionId id;
    private Instant grantedAt;
    private Long grantedBy;

    static StaffPermissionEntity grant(long accountId, StaffPermission permission, long grantedBy, Instant now) {
        var entity = new StaffPermissionEntity();
        entity.id = StaffPermissionId.of(accountId, permission);
        entity.grantedAt = now;
        entity.grantedBy = grantedBy;
        return entity;
    }
}

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StaffPermissionId implements Serializable {
    private Long staffAccountId;
    @Enumerated(EnumType.STRING)
    @Column(name = "permission_code")
    private StaffPermission permission;

    static StaffPermissionId of(long accountId, StaffPermission permission) {
        var id = new StaffPermissionId();
        id.staffAccountId = accountId;
        id.permission = permission;
        return id;
    }
}

@Getter
@Entity
@Table(name = "auth_refresh_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AuthRefreshSessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long staffAccountId;
    private String tokenHash;
    private long sessionVersion;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;
    private String createdIp;
    private String userAgent;

    static AuthRefreshSessionEntity create(
            long accountId,
            String tokenHash,
            long sessionVersion,
            Instant expiresAt,
            Instant now,
            String createdIp,
            String userAgent
    ) {
        var session = new AuthRefreshSessionEntity();
        session.staffAccountId = accountId;
        session.tokenHash = tokenHash;
        session.sessionVersion = sessionVersion;
        session.expiresAt = expiresAt;
        session.createdAt = now;
        session.createdIp = createdIp;
        session.userAgent = userAgent;
        return session;
    }

    boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}

@Getter
@Entity
@Table(name = "audit_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AuditLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private Long actorAccountId;
    private String module;
    private String action;
    private String subjectType;
    private String subjectId;
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> beforeValues;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> afterValues;
    private Instant occurredAt;

    static AuditLogEntity create(
            long gymId,
            Long actorAccountId,
            String action,
            String subjectType,
            String subjectId,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues,
            Instant now
    ) {
        var log = new AuditLogEntity();
        log.gymId = gymId;
        log.actorAccountId = actorAccountId;
        log.module = "AUTH";
        log.action = action;
        log.subjectType = subjectType;
        log.subjectId = subjectId;
        log.beforeValues = beforeValues;
        log.afterValues = afterValues;
        log.occurredAt = now;
        return log;
    }
}

@Getter
@Entity
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OutboxEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gymId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;
    private Instant occurredAt;
    private Instant publishedAt;
    private int attemptCount;
    private String lastError;
}

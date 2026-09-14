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

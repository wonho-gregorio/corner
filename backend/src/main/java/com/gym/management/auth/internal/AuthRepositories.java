package com.gym.management.auth.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

interface GymRepository extends JpaRepository<GymEntity, Long> {
}

interface StaffAccountRepository extends JpaRepository<StaffAccountEntity, Long> {
    @Query("select account from StaffAccountEntity account where lower(account.loginId) = lower(:loginId)")
    List<StaffAccountEntity> findByLoginIdIgnoringCase(@Param("loginId") String loginId);
}

interface StaffPermissionRepository extends JpaRepository<StaffPermissionEntity, StaffPermissionId> {
    List<StaffPermissionEntity> findAllByIdStaffAccountId(Long staffAccountId);
}

interface AuthRefreshSessionRepository extends JpaRepository<AuthRefreshSessionEntity, Long> {
    Optional<AuthRefreshSessionEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthRefreshSessionEntity session where session.tokenHash = :tokenHash")
    Optional<AuthRefreshSessionEntity> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("""
            update AuthRefreshSessionEntity session
               set session.revokedAt = :revokedAt
             where session.staffAccountId = :accountId
               and session.revokedAt is null
            """)
    int revokeAllByAccountId(@Param("accountId") long accountId, @Param("revokedAt") Instant revokedAt);
}

interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
}

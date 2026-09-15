package com.gym.management.auth.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

import jakarta.persistence.LockModeType;

interface GymRepository extends JpaRepository<GymEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gym from GymEntity gym where gym.id = :gymId")
    Optional<GymEntity> findForUpdateById(@Param("gymId") long gymId);
}

interface StaffAccountRepository extends JpaRepository<StaffAccountEntity, Long> {
    @Query("select account from StaffAccountEntity account where lower(account.loginId) = lower(:loginId)")
    List<StaffAccountEntity> findByLoginIdIgnoringCase(@Param("loginId") String loginId);

    List<StaffAccountEntity> findAllByGymIdOrderByNameAscIdAsc(Long gymId);

    Optional<StaffAccountEntity> findByIdAndGymId(Long id, Long gymId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from StaffAccountEntity account where account.id = :accountId and account.gymId = :gymId")
    Optional<StaffAccountEntity> findForUpdateByIdAndGymId(
            @Param("accountId") long accountId,
            @Param("gymId") long gymId
    );

    @Query("""
            select (count(account) > 0) from StaffAccountEntity account
             where account.gymId = :gymId
               and lower(account.loginId) = lower(:loginId)
               and (:excludedId is null or account.id <> :excludedId)
            """)
    boolean existsDuplicateLoginId(
            @Param("gymId") long gymId,
            @Param("loginId") String loginId,
            @Param("excludedId") Long excludedId
    );

    @Query("""
            select count(account) from StaffAccountEntity account
             where account.gymId = :gymId
               and account.role = com.gym.management.auth.internal.StaffRole.ADMIN
               and account.status = com.gym.management.auth.internal.StaffStatus.ACTIVE
            """)
    long countActiveAdministrators(@Param("gymId") long gymId);
}

interface StaffPermissionRepository extends JpaRepository<StaffPermissionEntity, StaffPermissionId> {
    List<StaffPermissionEntity> findAllByIdStaffAccountId(Long staffAccountId);
    List<StaffPermissionEntity> findAllByIdStaffAccountIdIn(Collection<Long> staffAccountIds);

    @Modifying
    @Query("delete from StaffPermissionEntity permission where permission.id.staffAccountId = :accountId")
    int deleteAllByAccountId(@Param("accountId") long accountId);
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

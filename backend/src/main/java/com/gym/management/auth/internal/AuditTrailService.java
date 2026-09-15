package com.gym.management.auth.internal;

import com.gym.management.auth.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
class AuditTrailService implements AuditTrail {
    private final AuditLogRepository auditLogs;

    AuditTrailService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            long gymId,
            Long actorAccountId,
            String module,
            String action,
            String subjectType,
            String subjectId,
            String reason,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues,
            Instant occurredAt
    ) {
        auditLogs.save(AuditLogEntity.create(
                gymId, actorAccountId, module, action, subjectType, subjectId, reason,
                beforeValues, afterValues, occurredAt));
    }
}

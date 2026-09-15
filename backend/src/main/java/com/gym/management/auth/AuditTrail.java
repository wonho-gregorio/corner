package com.gym.management.auth;

import java.time.Instant;
import java.util.Map;

public interface AuditTrail {
    void record(
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
    );
}

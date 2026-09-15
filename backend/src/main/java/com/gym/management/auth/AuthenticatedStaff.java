package com.gym.management.auth;

import java.util.List;

public record AuthenticatedStaff(
        long accountId,
        long gymId,
        String loginId,
        String name,
        String role,
        List<String> permissions,
        boolean mustChangePassword
) {
}

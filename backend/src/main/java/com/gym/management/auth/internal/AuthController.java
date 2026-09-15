package com.gym.management.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SetupController {
    private final AuthService authService;

    SetupController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/setup/status")
    SetupStatus setupStatus() {
        return new SetupStatus(authService.isInitialized());
    }

    @PostMapping("/api/setup")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.AuthResult setup(@Valid @RequestBody SetupRequest request, HttpServletRequest httpRequest) {
        return authService.setup(
                new AuthService.SetupCommand(
                        request.gymName(), request.representativePhone(), request.address(), request.addressDetail(),
                        request.adminName(), request.loginId(), request.password()),
                clientMetadata(httpRequest));
    }

    record SetupStatus(boolean initialized) {
    }

    record SetupRequest(
            @NotBlank @Size(max = 100) String gymName,
            @Size(max = 30) String representativePhone,
            @Size(max = 300) String address,
            @Size(max = 200) String addressDetail,
            @NotBlank @Size(max = 100) String adminName,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$") String loginId,
            @NotBlank @Size(min = 10, max = 72) String password
    ) {
    }

    static AuthService.ClientMetadata clientMetadata(HttpServletRequest request) {
        var userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 500) {
            userAgent = userAgent.substring(0, 500);
        }
        return new AuthService.ClientMetadata(request.getRemoteAddr(), userAgent);
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    AuthService.AuthResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request.loginId(), request.password(), SetupController.clientMetadata(httpRequest));
    }

    @PostMapping("/refresh")
    AuthService.AuthResult refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), SetupController.clientMetadata(httpRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    AuthenticatedStaff me(@AuthenticationPrincipal AuthenticatedStaff principal) {
        return authService.current(principal);
    }

    @PostMapping("/change-password")
    AuthService.AuthResult changePassword(
            @AuthenticationPrincipal AuthenticatedStaff principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        return authService.changePassword(
                principal.accountId(), request.currentPassword(), request.newPassword(),
                SetupController.clientMetadata(httpRequest));
    }

    record LoginRequest(
            @NotBlank @Size(max = 50) String loginId,
            @NotBlank @Size(max = 72) String password
    ) {
    }

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    record ChangePasswordRequest(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 10, max = 72) String newPassword
    ) {
    }
}

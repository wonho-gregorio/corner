package com.gym.management.auth.internal;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey jwtSecretKey(AuthProperties properties) {
        var secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secretKey, AuthProperties properties) {
        var decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccessTokenFilter accessTokenFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/setup", "/api/setup/status", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, 401, "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, 403, "ACCESS_DENIED")))
                .addFilterBefore(accessTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

record AuthenticatedStaff(
        long accountId,
        long gymId,
        String loginId,
        String name,
        StaffRole role,
        List<String> permissions,
        boolean mustChangePassword
) {
}

@Component
class AccessTokenFilter extends OncePerRequestFilter {
    private static final List<String> PASSWORD_CHANGE_ALLOWED_PATHS = List.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout"
    );

    private final JwtDecoder jwtDecoder;
    private final StaffAccountRepository accounts;
    private final StaffPermissionRepository permissions;

    AccessTokenFilter(JwtDecoder jwtDecoder, StaffAccountRepository accounts, StaffPermissionRepository permissions) {
        this.jwtDecoder = jwtDecoder;
        this.accounts = accounts;
        this.permissions = permissions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            var jwt = jwtDecoder.decode(header.substring(7));
            var accountId = Long.parseLong(jwt.getSubject());
            var account = accounts.findById(accountId).orElse(null);
            var sessionVersionClaim = jwt.getClaim("session_version");
            if (!(sessionVersionClaim instanceof Number number)) {
                throw new JwtException("Missing session version");
            }
            var tokenSessionVersion = number.longValue();
            if (account == null || !account.isActive() || account.getSessionVersion() != tokenSessionVersion) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            var permissionNames = effectivePermissions(account);
            var authorities = new ArrayList<SimpleGrantedAuthority>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()));
            permissionNames.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
            var principal = new AuthenticatedStaff(
                    account.getId(), account.getGymId(), account.getLoginId(), account.getName(), account.getRole(),
                    permissionNames, account.isMustChangePassword());
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));

            if (account.isMustChangePassword() && !isPasswordChangeAllowed(request)) {
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
                return;
            }
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private List<String> effectivePermissions(StaffAccountEntity account) {
        if (account.getRole() == StaffRole.ADMIN) {
            return List.of(StaffPermission.MEMBER_MANAGE.name(), StaffPermission.ATTENDANCE_PROCESS.name(),
                    StaffPermission.PAYMENT_REGISTER.name());
        }
        return permissions.findAllByIdStaffAccountId(account.getId()).stream()
                .map(permission -> permission.getId().getPermission().name())
                .sorted()
                .toList();
    }

    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || PASSWORD_CHANGE_ALLOWED_PATHS.contains(request.getRequestURI());
    }
}

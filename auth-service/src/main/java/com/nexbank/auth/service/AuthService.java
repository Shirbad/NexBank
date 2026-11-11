package com.nexbank.auth.service;

import com.nexbank.auth.dto.AuthDtos.Credentials;
import com.nexbank.auth.dto.AuthDtos.TokenResponse;
import com.nexbank.auth.entity.BankUser;
import com.nexbank.auth.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private static final long ACCESS_TTL_SECONDS = 900;
    private static final long REFRESH_TTL_SECONDS = 2_592_000;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final byte[] secret;

    public AuthService(UserRepository users, PasswordEncoder passwords, StringRedisTemplate redis, JdbcTemplate jdbc,
                       @Value("${jwt.secret:}") String secret) {
        this.users = users;
        this.passwords = passwords;
        this.redis = redis;
        this.jdbc = jdbc;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public TokenResponse register(Credentials request) {
        if (users.existsByEmailIgnoreCase(request.email())) throw new IllegalArgumentException("Email already registered");
        BankUser user = users.save(new BankUser(request.email().trim().toLowerCase(), passwords.encode(request.password())));
        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(Credentials request) {
        BankUser user = users.findByEmailIgnoreCase(request.email()).filter(BankUser::isEnabled)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid email or password"));
        if (!passwords.matches(request.password(), user.getPasswordHash()))
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid email or password");
        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        String redisKey = refreshKey(refreshToken);
        String userId = redis.opsForValue().getAndDelete(redisKey);
        if (userId == null) throw new org.springframework.security.authentication.BadCredentialsException("Refresh token is invalid or already used");
        BankUser user = users.findById(UUID.fromString(userId)).filter(BankUser::isEnabled)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("User is unavailable"));
        return issue(user);
    }

    public void logout(String authorization, String refreshToken) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new org.springframework.security.authentication.BadCredentialsException("Bearer token is required");
        Claims claims = parseAccessToken(authorization.substring(7));
        Date expiry = claims.getExpiration();
        long remaining = Math.max(1, (expiry.getTime() - System.currentTimeMillis()) / 1000);
        redis.opsForValue().set("jwt:blacklist:" + claims.getId(), "1", Duration.ofSeconds(remaining));
        redis.delete(refreshKey(refreshToken));
    }

    @Transactional
    public com.nexbank.auth.dto.AuthDtos.UserView createStaff(String actorHeader, com.nexbank.auth.dto.AuthDtos.StaffUserRequest request) {
        UUID actor = UUID.fromString(actorHeader);
        if (users.existsByEmailIgnoreCase(request.email())) throw new IllegalArgumentException("Email already registered");
        BankUser user = users.save(new BankUser(request.email().trim().toLowerCase(), passwords.encode(request.password()), request.role()));
        audit(actor, user.getId(), "STAFF_USER_CREATED", request.role().name());
        return new com.nexbank.auth.dto.AuthDtos.UserView(user.getId(), user.getEmail(), user.getRole().name());
    }

    @Transactional
    public com.nexbank.auth.dto.AuthDtos.UserView changeRole(String actorHeader, UUID userId, com.nexbank.auth.entity.UserRole role) {
        UUID actor = UUID.fromString(actorHeader);
        BankUser user = users.findById(userId).orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("User not found"));
        user.setRole(role);
        users.save(user);
        audit(actor, user.getId(), "USER_ROLE_CHANGED", role.name());
        return new com.nexbank.auth.dto.AuthDtos.UserView(user.getId(), user.getEmail(), user.getRole().name());
    }

    private void audit(UUID actor, UUID subject, String action, String role) {
        String details = "{\"role\":\"" + role + "\"}";
        jdbc.update("insert into staff_audit_log(id,actor_user_id,subject_user_id,action,details) values (?,?,?,?,cast(? as jsonb))",
                UUID.randomUUID(), actor, subject, action, details);
    }

    private TokenResponse issue(BankUser user) {
        if (secret.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        Instant now = Instant.now();
        String jwtId = UUID.randomUUID().toString();
        String jwt = Jwts.builder().id(jwtId).subject(user.getId().toString())
                .claim("role", user.getRole().name()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ACCESS_TTL_SECONDS)))
                .signWith(Keys.hmacShaKeyFor(secret)).compact();
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        redis.opsForValue().set(refreshKey(refreshToken), user.getId().toString(), Duration.ofSeconds(REFRESH_TTL_SECONDS));
        return new TokenResponse(jwt, refreshToken, "Bearer", user.getId(), user.getRole().name(), ACCESS_TTL_SECONDS);
    }

    private Claims parseAccessToken(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret)).build().parseSignedClaims(token).getPayload();
    }

    private String refreshKey(String token) { return "auth:refresh:" + sha256(token); }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}

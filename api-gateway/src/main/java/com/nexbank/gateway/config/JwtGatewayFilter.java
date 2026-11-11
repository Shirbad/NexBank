package com.nexbank.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/register", "/api/auth/login", "/api/auth/refresh");
    private static final Set<String> PUBLIC_DOC_PATHS = Set.of(
            "/swagger-ui.html", "/auth/v3/api-docs", "/account/v3/api-docs",
            "/fraud/v3/api-docs", "/notification/v3/api-docs", "/actuator/health");
    private final byte[] secret;
    private final ReactiveStringRedisTemplate redis;

    public JwtGatewayFilter(@Value("${jwt.secret:}") String secret, ReactiveStringRedisTemplate redis) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.redis = redis;
    }

    @Override public int getOrder() { return -100; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (PUBLIC_DOC_PATHS.contains(path) || path.startsWith("/swagger-ui/") || path.startsWith("/v3/api-docs"))
            return chain.filter(exchange);
        if (PUBLIC_AUTH_PATHS.contains(path)) {
            int limit = path.equals("/api/auth/login") ? 5 : 20;
            return rateLimit(exchange, chain, "ip:" + clientIp(exchange), limit);
        }

        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        final Claims claims;
        try {
            if (secret.length < 32 || header == null || !header.startsWith("Bearer ")) throw new JwtException("Missing token");
            claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret)).build()
                    .parseSignedClaims(header.substring(7)).getPayload();
            if (claims.getId() == null || claims.getSubject() == null || claims.get("role", String.class) == null)
                throw new JwtException("Required claims are missing");
        } catch (RuntimeException ex) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        return redis.hasKey("jwt:blacklist:" + claims.getId()).flatMap(revoked -> {
            if (Boolean.TRUE.equals(revoked)) return reject(exchange, HttpStatus.UNAUTHORIZED);
            var request = exchange.getRequest().mutate()
                    .headers(headers -> { headers.remove("X-User-Id"); headers.remove("X-User-Role"); })
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Role", claims.get("role", String.class)).build();
            ServerWebExchange enriched = exchange.mutate().request(request).build();
            return rateLimit(enriched, chain, "user:" + claims.getSubject(), 100);
        }).onErrorResume(ex -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Mono<Void> rateLimit(ServerWebExchange exchange, GatewayFilterChain chain, String identity, int limit) {
        String key = "rate:minute:" + identity;
        return redis.opsForValue().increment(key).flatMap(count -> {
            Mono<Boolean> expiry = count == 1 ? redis.expire(key, Duration.ofMinutes(1)) : Mono.just(true);
            return expiry.then(Mono.defer(() -> count > limit
                    ? reject(exchange, HttpStatus.TOO_MANY_REQUESTS)
                    : chain.filter(exchange)));
        }).onErrorResume(ex -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}

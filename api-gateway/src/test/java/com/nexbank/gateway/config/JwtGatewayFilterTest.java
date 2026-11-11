package com.nexbank.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtGatewayFilterTest {
    private static final String SECRET="0123456789abcdef0123456789abcdef";
    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String,String> values;
    private JwtGatewayFilter filter;

    @BeforeEach @SuppressWarnings("unchecked") void setUp(){
        redis=mock(ReactiveStringRedisTemplate.class);
        values=mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        filter=new JwtGatewayFilter(SECRET,redis);
    }

    @Test void rejectsRequestWithoutToken(){
        MockServerWebExchange exchange=exchange("/api/accounts",null);
        GatewayFilterChain chain=mock(GatewayFilterChain.class);

        filter.filter(exchange,chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED,exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test void rejectsLoggedOutToken(){
        String token=token();
        when(redis.hasKey(startsWith("jwt:blacklist:"))).thenReturn(Mono.just(true));
        MockServerWebExchange exchange=exchange("/api/accounts",token);
        GatewayFilterChain chain=mock(GatewayFilterChain.class);

        filter.filter(exchange,chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED,exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test void rateLimitsAuthenticatedUsersAtOneHundredRequests(){
        when(redis.hasKey(startsWith("jwt:blacklist:"))).thenReturn(Mono.just(false));
        when(values.increment(startsWith("rate:minute:user:"))).thenReturn(Mono.just(101L));
        MockServerWebExchange exchange=exchange("/api/accounts",token());
        GatewayFilterChain chain=mock(GatewayFilterChain.class);

        filter.filter(exchange,chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS,exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test void replacesSpoofedIdentityHeadersWithVerifiedClaims(){
        when(redis.hasKey(startsWith("jwt:blacklist:"))).thenReturn(Mono.just(false));
        when(values.increment(startsWith("rate:minute:user:"))).thenReturn(Mono.just(1L));
        when(redis.expire(anyString(),any(Duration.class))).thenReturn(Mono.just(true));
        AtomicReference<ServerWebExchange> forwarded=new AtomicReference<>();
        GatewayFilterChain chain=exchange->{forwarded.set(exchange);return Mono.empty();};
        MockServerWebExchange exchange=exchange("/api/accounts",token());

        filter.filter(exchange,chain).block();

        assertNotNull(forwarded.get());
        assertEquals("user-123",forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("CUSTOMER",forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
    }

    @Test void rateLimitsLoginByClientIp(){
        when(values.increment("rate:minute:ip:127.0.0.1")).thenReturn(Mono.just(6L));
        MockServerWebExchange exchange=exchange("/api/auth/login",null);
        GatewayFilterChain chain=mock(GatewayFilterChain.class);

        filter.filter(exchange,chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS,exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    private MockServerWebExchange exchange(String path,String token){
        MockServerHttpRequest.BaseBuilder<?> request=MockServerHttpRequest.get(path).remoteAddress(new InetSocketAddress("127.0.0.1",12345));
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.header("X-User-Id","attacker").header("X-User-Role","ADMIN");
        return MockServerWebExchange.from(request);
    }

    private String token(){
        return Jwts.builder().id(UUID.randomUUID().toString()).subject("user-123").claim("role","CUSTOMER")
                .expiration(new Date(System.currentTimeMillis()+60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}

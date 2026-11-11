package com.nexbank.auth.service;

import com.nexbank.auth.dto.AuthDtos.Credentials;
import com.nexbank.auth.dto.AuthDtos.StaffUserRequest;
import com.nexbank.auth.entity.BankUser;
import com.nexbank.auth.entity.UserRole;
import com.nexbank.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    @Test void registerAlwaysCreatesCustomerAndReturnsSignedToken() {
        UserRepository users=mock(UserRepository.class); PasswordEncoder encoder=mock(PasswordEncoder.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class); @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(users.existsByEmailIgnoreCase("a@example.com")).thenReturn(false);
        when(encoder.encode("long-enough-password")).thenReturn("bcrypt-hash");
        when(users.save(any(BankUser.class))).thenAnswer(invocation->invocation.getArgument(0));
        AuthService service=new AuthService(users,encoder,redis,mock(JdbcTemplate.class),"01234567890123456789012345678901");
        var token=service.register(new Credentials("a@example.com","long-enough-password"));
        assertEquals("CUSTOMER",token.role()); assertEquals("Bearer",token.tokenType()); assertNotNull(token.accessToken());
        verify(encoder).encode("long-enough-password");
    }

    @Test void loginRejectsIncorrectPassword() {
        UserRepository users=mock(UserRepository.class); PasswordEncoder encoder=mock(PasswordEncoder.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        BankUser user=new BankUser("a@example.com","bcrypt-hash");
        when(users.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong-password","bcrypt-hash")).thenReturn(false);
        AuthService service=new AuthService(users,encoder,redis,mock(JdbcTemplate.class),"01234567890123456789012345678901");
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                ()->service.login(new Credentials("a@example.com","wrong-password")));
    }

    @Test void adminCreatesTellerAndAuditsTheAction(){
        UserRepository users=mock(UserRepository.class);PasswordEncoder encoder=mock(PasswordEncoder.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class);JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(users.existsByEmailIgnoreCase("teller@example.com")).thenReturn(false);
        when(encoder.encode("teller-password")).thenReturn("encoded-password");
        when(users.save(any(BankUser.class))).thenAnswer(invocation->invocation.getArgument(0));
        AuthService service=new AuthService(users,encoder,redis,jdbc,"01234567890123456789012345678901");
        var view=service.createStaff(UUID.randomUUID().toString(),new StaffUserRequest("teller@example.com","teller-password",UserRole.TELLER));

        assertEquals("TELLER",view.role());
        verify(jdbc).update(contains("staff_audit_log"),any(),any(),any(),eq("STAFF_USER_CREATED"),contains("TELLER"));
    }

    @Test void refreshTokenRotatesAndCannotBeReused() {
        UserRepository users=mock(UserRepository.class); PasswordEncoder encoder=mock(PasswordEncoder.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class); @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        BankUser user=new BankUser("a@example.com","bcrypt-hash");
        when(values.getAndDelete(anyString())).thenReturn(user.getId().toString());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        AuthService service=new AuthService(users,encoder,redis,mock(JdbcTemplate.class),"01234567890123456789012345678901");

        var rotated=service.refresh("single-use-refresh-token");

        assertEquals("CUSTOMER",rotated.role());
        assertNotEquals("single-use-refresh-token",rotated.refreshToken());
        verify(values).getAndDelete(startsWith("auth:refresh:"));
        verify(values).set(startsWith("auth:refresh:"),eq(user.getId().toString()),any(Duration.class));
    }

    @Test void logoutBlacklistsAccessTokenAndDeletesRefreshToken() {
        UserRepository users=mock(UserRepository.class); PasswordEncoder encoder=mock(PasswordEncoder.class);
        StringRedisTemplate redis=mock(StringRedisTemplate.class); @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        BankUser user=new BankUser("a@example.com","bcrypt-hash");
        when(users.existsByEmailIgnoreCase("a@example.com")).thenReturn(false);
        when(users.save(any(BankUser.class))).thenAnswer(invocation->invocation.getArgument(0));
        when(encoder.encode("long-enough-password")).thenReturn("bcrypt-hash");
        AuthService service=new AuthService(users,encoder,redis,mock(JdbcTemplate.class),"01234567890123456789012345678901");
        var issued=service.register(new Credentials("a@example.com","long-enough-password"));

        service.logout("Bearer "+issued.accessToken(),issued.refreshToken());

        verify(values).set(startsWith("jwt:blacklist:"),eq("1"),any(Duration.class));
        verify(redis).delete(startsWith("auth:refresh:"));
    }
}

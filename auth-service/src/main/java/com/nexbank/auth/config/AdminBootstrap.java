package com.nexbank.auth.config;

import com.nexbank.auth.entity.BankUser;
import com.nexbank.auth.entity.UserRole;
import com.nexbank.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwords,
                          @Value("${ADMIN_EMAIL:}") String email,
                          @Value("${ADMIN_PASSWORD:}") String password) {
        this.users=users; this.passwords=passwords; this.email=email; this.password=password;
    }

    @Override public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.isBlank() || password.length() < 12)
            throw new IllegalStateException("Set both ADMIN_EMAIL and an ADMIN_PASSWORD with at least 12 characters");
        if (!users.existsByEmailIgnoreCase(email))
            users.save(new BankUser(email.trim().toLowerCase(), passwords.encode(password), UserRole.ADMIN));
    }
}

package com.nexbank.auth.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="users") public class BankUser {
 @Id private UUID id; @Column(nullable=false,unique=true) private String email; @Column(name="password_hash",nullable=false) private String passwordHash; @Enumerated(EnumType.STRING) @Column(nullable=false) private UserRole role; @Column(nullable=false) private boolean enabled; @Column(name="created_at",nullable=false) private Instant createdAt;
 protected BankUser(){} public BankUser(String email,String passwordHash){this(email,passwordHash,UserRole.CUSTOMER);} public BankUser(String email,String passwordHash,UserRole role){id=UUID.randomUUID();this.email=email;this.passwordHash=passwordHash;this.role=role;enabled=true;createdAt=Instant.now();}
 public UUID getId(){return id;} public String getEmail(){return email;} public String getPasswordHash(){return passwordHash;} public UserRole getRole(){return role;} public boolean isEnabled(){return enabled;} public void setRole(UserRole role){this.role=role;}
}

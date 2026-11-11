package com.nexbank.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="accounts")
public class Account {
    @Id private UUID id;
    @Column(name="account_number", nullable=false, unique=true) private String accountNumber;
    @Column(name="owner_user_id", nullable=false) private UUID ownerUserId;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal balance;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private AccountStatus status;
    @Version private long version;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected Account() {}
    public Account(UUID owner) { id=UUID.randomUUID(); ownerUserId=owner; accountNumber="NB"+id.toString().replace("-", "").substring(0,18).toUpperCase(); balance=BigDecimal.ZERO.setScale(2); status=AccountStatus.ACTIVE; createdAt=Instant.now(); }
    public UUID getId(){return id;} public String getAccountNumber(){return accountNumber;} public UUID getOwnerUserId(){return ownerUserId;} public BigDecimal getBalance(){return balance;} public AccountStatus getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
    public void credit(BigDecimal amount){balance=balance.add(amount);} public void debit(BigDecimal amount){balance=balance.subtract(amount);} public void freeze(){status=AccountStatus.FROZEN;} public void unfreeze(){status=AccountStatus.ACTIVE;}
}

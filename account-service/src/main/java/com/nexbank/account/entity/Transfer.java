package com.nexbank.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="transfers")
public class Transfer {
    @Id private UUID id;
    @Column(name="from_account_id", nullable=false) private UUID fromAccountId;
    @Column(name="to_account_id", nullable=false) private UUID toAccountId;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal amount;
    @Column(nullable=false) private String status;
    @Column(name="idempotency_key", nullable=false, unique=true) private String idempotencyKey;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected Transfer() {}
    public Transfer(UUID from,UUID to,BigDecimal amount,String key){id=UUID.randomUUID();fromAccountId=from;toAccountId=to;this.amount=amount;idempotencyKey=key;status="COMPLETED";createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getFromAccountId(){return fromAccountId;} public UUID getToAccountId(){return toAccountId;} public BigDecimal getAmount(){return amount;} public String getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
}

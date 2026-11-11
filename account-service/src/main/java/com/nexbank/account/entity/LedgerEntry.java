package com.nexbank.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ledger_entries")
public class LedgerEntry {
    @Id private UUID id;
    @Column(name="account_id", nullable=false) private UUID accountId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private EntryType type;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal amount;
    @Column(name="balance_after", nullable=false, precision=19, scale=2) private BigDecimal balanceAfter;
    @Column(name="transfer_id") private UUID transferId;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected LedgerEntry() {}
    public LedgerEntry(UUID accountId, EntryType type, BigDecimal amount, BigDecimal balanceAfter, UUID transferId){this.id=UUID.randomUUID();this.accountId=accountId;this.type=type;this.amount=amount;this.balanceAfter=balanceAfter;this.transferId=transferId;this.createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getAccountId(){return accountId;} public EntryType getType(){return type;} public BigDecimal getAmount(){return amount;} public BigDecimal getBalanceAfter(){return balanceAfter;} public UUID getTransferId(){return transferId;} public Instant getCreatedAt(){return createdAt;}
    public enum EntryType { DEBIT, CREDIT }
}

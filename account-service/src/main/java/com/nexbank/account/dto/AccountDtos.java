package com.nexbank.account.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public final class AccountDtos {
    private AccountDtos() {}
    public record OpenAccountRequest() {}
    public record MoneyRequest(@NotNull @DecimalMin(value="0.01") @Digits(integer=17,fraction=2) BigDecimal amount) {}
    public record TransferRequest(@NotNull UUID fromAccountId,@NotNull UUID toAccountId,@NotNull @DecimalMin(value="0.01") @Digits(integer=17,fraction=2) BigDecimal amount) {}
    public record AccountView(UUID id,String accountNumber,UUID ownerUserId,BigDecimal balance,String status,Instant createdAt) {}
    public record LedgerView(UUID id,String type,BigDecimal amount,BigDecimal balanceAfter,UUID transferId,Instant createdAt) {}
    public record TransferView(UUID id,UUID fromAccountId,UUID toAccountId,BigDecimal amount,String status,Instant createdAt) {}
}

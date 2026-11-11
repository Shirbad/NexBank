package com.nexbank.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.dto.AccountDtos.TransferRequest;
import com.nexbank.account.entity.Account;
import com.nexbank.account.entity.LedgerEntry;
import com.nexbank.account.entity.Transfer;
import com.nexbank.account.repository.AccountRepository;
import com.nexbank.account.repository.LedgerRepository;
import com.nexbank.account.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class BankingServiceTest {
    private AccountRepository accounts;
    private LedgerRepository ledger;
    private TransferRepository transfers;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> redisValues;
    private BankingService banking;
    private UUID owner;
    private Account source;
    private Account destination;

    @BeforeEach void setUp() {
        accounts=mock(AccountRepository.class);
        ledger=mock(LedgerRepository.class);
        transfers=mock(TransferRepository.class);
        jdbc=mock(JdbcTemplate.class);
        redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> operations=mock(ValueOperations.class);
        redisValues=operations;
        when(redis.opsForValue()).thenReturn(redisValues);
        banking=new BankingService(accounts,ledger,transfers,jdbc,new ObjectMapper().findAndRegisterModules(),redis);
        owner=UUID.randomUUID();
        source=new Account(owner); destination=new Account(owner);
        source.credit(new BigDecimal("1000.00"));
    }

    @Test void transfer_movesMoneyAndCreatesBothLedgerEntriesAndOutboxEvent() {
        when(transfers.findByIdempotencyKey("transfer-1")).thenReturn(Optional.empty());
        when(accounts.lockAll(anyCollection())).thenReturn(List.of(source,destination));
        when(transfers.saveAndFlush(any(Transfer.class))).thenAnswer(call->call.getArgument(0));

        Transfer result=banking.transfer(owner,new TransferRequest(source.getId(),destination.getId(),new BigDecimal("125.00")),"transfer-1");

        assertEquals("COMPLETED",result.getStatus());
        assertEquals(new BigDecimal("875.00"),source.getBalance());
        assertEquals(new BigDecimal("125.00"),destination.getBalance());
        verify(ledger,times(2)).save(any(LedgerEntry.class));
        verify(jdbc).update(contains("outbox_events"),any(),any(),any(),any());
        verify(redisValues).set(startsWith("nexbank:transfer:idempotency:"),eq(result.getId().toString()),eq(Duration.ofHours(24)));
    }

    @Test void transfer_rejectsSameAccount() {
        assertThrows(IllegalArgumentException.class,()->banking.transfer(owner,
                new TransferRequest(source.getId(),source.getId(),new BigDecimal("1.00")),"transfer-2"));
        verifyNoInteractions(transfers,ledger,jdbc);
    }

    @Test void transfer_rejectsInsufficientFundsWithoutChangingEitherBalance() {
        when(transfers.findByIdempotencyKey("transfer-3")).thenReturn(Optional.empty());
        when(accounts.lockAll(anyCollection())).thenReturn(List.of(source,destination));

        assertThrows(IllegalArgumentException.class,()->banking.transfer(owner,
                new TransferRequest(source.getId(),destination.getId(),new BigDecimal("1001.00")),"transfer-3"));
        assertEquals(new BigDecimal("1000.00"),source.getBalance());
        assertEquals(BigDecimal.ZERO.setScale(2),destination.getBalance());
        verifyNoInteractions(ledger);
    }

    @Test void transfer_rejectsFrozenSource() {
        source.freeze();
        when(transfers.findByIdempotencyKey("transfer-4")).thenReturn(Optional.empty());
        when(accounts.lockAll(anyCollection())).thenReturn(List.of(source,destination));

        assertThrows(IllegalStateException.class,()->banking.transfer(owner,
                new TransferRequest(source.getId(),destination.getId(),new BigDecimal("10.00")),"transfer-4"));
        verifyNoInteractions(ledger);
    }

    @Test void transfer_rejectsAccountNotOwnedByCustomer() {
        UUID other=UUID.randomUUID();
        when(transfers.findByIdempotencyKey("transfer-5")).thenReturn(Optional.empty());
        when(accounts.lockAll(anyCollection())).thenReturn(List.of(source,destination));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->banking.transfer(other,
                new TransferRequest(source.getId(),destination.getId(),new BigDecimal("10.00")),"transfer-5"));
        assertEquals(new BigDecimal("1000.00"),source.getBalance());
        verifyNoInteractions(ledger);
    }

    @Test void transfer_replayReturnsStoredResultWithoutWritingAgain() {
        Transfer prior=new Transfer(source.getId(),destination.getId(),new BigDecimal("25.00"),"transfer-6");
        when(transfers.findByIdempotencyKey("transfer-6")).thenReturn(Optional.of(prior));
        when(accounts.findById(source.getId())).thenReturn(Optional.of(source));

        Transfer replay=banking.transfer(owner,new TransferRequest(source.getId(),destination.getId(),new BigDecimal("25.00")),"transfer-6");

        assertSame(prior,replay);
        verify(accounts,never()).lockAll(anyCollection());
        verifyNoInteractions(ledger);
    }

    @Test void transfer_usesRedisCachedResultOnRetry() {
        Transfer prior=new Transfer(source.getId(),destination.getId(),new BigDecimal("25.00"),"transfer-cache-1");
        when(redisValues.get(anyString())).thenReturn(prior.getId().toString());
        when(transfers.findById(prior.getId())).thenReturn(Optional.of(prior));
        when(accounts.findById(source.getId())).thenReturn(Optional.of(source));

        Transfer replay=banking.transfer(owner,new TransferRequest(source.getId(),destination.getId(),new BigDecimal("25.00")),"transfer-cache-1");

        assertSame(prior,replay);
        verify(transfers,never()).findByIdempotencyKey(anyString());
        verify(transfers,never()).saveAndFlush(any());
        verify(jdbc,never()).update(contains("outbox_events"),any(),any(),any(),any());
    }

    @Test void transfer_rejectsReusedKeyForDifferentRequest() {
        Transfer prior=new Transfer(source.getId(),destination.getId(),new BigDecimal("25.00"),"transfer-7");
        when(transfers.findByIdempotencyKey("transfer-7")).thenReturn(Optional.of(prior));

        assertThrows(IllegalArgumentException.class,()->banking.transfer(owner,
                new TransferRequest(source.getId(),destination.getId(),new BigDecimal("30.00")),"transfer-7"));
        verify(accounts,never()).lockAll(anyCollection());
    }

    @Test void deposit_creditsBalanceAndWritesCreditLedger() {
        when(accounts.findLocked(source.getId())).thenReturn(Optional.of(source));
        var updated=banking.deposit(source.getId(),owner,false,new BigDecimal("50.00"));
        assertEquals(new BigDecimal("1050.00"),updated.getBalance());
        verify(ledger).save(argThat(entry->entry.getType()==LedgerEntry.EntryType.CREDIT && entry.getAmount().equals(new BigDecimal("50.00"))));
    }

    @Test void withdraw_debitsBalanceAndWritesDebitLedger() {
        when(accounts.findLocked(source.getId())).thenReturn(Optional.of(source));
        var updated=banking.withdraw(source.getId(),owner,new BigDecimal("40.00"));
        assertEquals(new BigDecimal("960.00"),updated.getBalance());
        verify(ledger).save(argThat(entry->entry.getType()==LedgerEntry.EntryType.DEBIT && entry.getAmount().equals(new BigDecimal("40.00"))));
    }

    @Test void withdraw_rejectsInsufficientFundsWithoutChangingBalance() {
        when(accounts.findLocked(source.getId())).thenReturn(Optional.of(source));
        assertThrows(IllegalArgumentException.class,()->banking.withdraw(source.getId(),owner,new BigDecimal("1001.00")));
        assertEquals(new BigDecimal("1000.00"),source.getBalance());
        verifyNoInteractions(ledger);
    }
}

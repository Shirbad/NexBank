package com.nexbank.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.dto.AccountDtos.*;
import com.nexbank.account.entity.Account;
import com.nexbank.account.entity.Transfer;
import com.nexbank.account.service.BankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountControllerCacheTest {
    private BankingService banking;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> values;
    private AccountController controller;

    @BeforeEach @SuppressWarnings("unchecked") void setUp(){
        banking=mock(BankingService.class);redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        controller=new AccountController(banking,redis,new ObjectMapper().findAndRegisterModules());
    }

    @Test void returnsCachedAccountForItsOwner(){
        UUID accountId=UUID.randomUUID(),owner=UUID.randomUUID();
        AccountView cached=new AccountView(accountId,"NB123",owner,new BigDecimal("100.00"),"ACTIVE",Instant.parse("2026-01-01T00:00:00Z"));
        when(values.get("nexbank:account:"+accountId)).thenReturn(write(cached));

        AccountView result=controller.get(accountId,owner,"CUSTOMER");

        assertEquals(cached,result);
        verifyNoInteractions(banking);
    }

    @Test void neverReturnsCachedAccountToAnotherCustomer(){
        UUID accountId=UUID.randomUUID();
        AccountView cached=new AccountView(accountId,"NB123",UUID.randomUUID(),BigDecimal.ZERO,"ACTIVE",Instant.now());
        when(values.get("nexbank:account:"+accountId)).thenReturn(write(cached));

        assertThrows(AccessDeniedException.class,()->controller.get(accountId,UUID.randomUUID(),"CUSTOMER"));
        verifyNoInteractions(banking);
    }

    @Test void evictsAccountCacheAfterDeposit(){
        UUID owner=UUID.randomUUID();Account account=new Account(owner);
        when(banking.deposit(account.getId(),owner,false,new BigDecimal("10.00"))).thenReturn(account);

        controller.deposit(account.getId(),owner,"CUSTOMER",new MoneyRequest(new BigDecimal("10.00")));

        verify(redis).delete("nexbank:account:"+account.getId());
    }

    @Test void evictsBothAccountCachesAfterTransfer(){
        UUID owner=UUID.randomUUID();UUID source=UUID.randomUUID();UUID destination=UUID.randomUUID();
        Transfer transfer=new Transfer(source,destination,new BigDecimal("10.00"),"cache-eviction");
        when(banking.transfer(eq(owner),any(TransferRequest.class),eq("cache-eviction"))).thenReturn(transfer);

        controller.transfer(owner,"CUSTOMER","cache-eviction",new TransferRequest(source,destination,new BigDecimal("10.00")));

        verify(redis).delete("nexbank:account:"+source);
        verify(redis).delete("nexbank:account:"+destination);
    }

    private String write(AccountView view){try{return new ObjectMapper().findAndRegisterModules().writeValueAsString(view);}catch(Exception e){throw new AssertionError(e);}}
}

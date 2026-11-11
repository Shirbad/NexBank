package com.nexbank.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.entity.Account;
import com.nexbank.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudDetectedConsumerTest {
    private AccountRepository accounts;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private FraudDetectedConsumer consumer;
    private Account account;
    private UUID eventId;

    @BeforeEach void setUp(){
        accounts=mock(AccountRepository.class);jdbc=mock(JdbcTemplate.class);redis=mock(StringRedisTemplate.class);
        consumer=new FraudDetectedConsumer(accounts,jdbc,new ObjectMapper(),redis);
        account=new Account(UUID.randomUUID());eventId=UUID.randomUUID();
        when(jdbc.queryForObject(contains("processed_events"),eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(accounts.findLocked(account.getId())).thenReturn(Optional.of(account));
    }

    @Test void highScoreFraudAlertFreezesAccountAndPublishesOutboxEvent(){
        consumer.consumeFraudAlert(event(90));

        assertEquals("FROZEN",account.getStatus().name());
        verify(accounts).save(account);
        verify(redis).delete("nexbank:account:"+account.getId());
        verify(jdbc).update(contains("outbox_events"),any(),eq(account.getId()),eq("account-frozen"),anyString());
        verify(jdbc).update(contains("insert into processed_events"),eq(eventId));
    }

    @Test void lowScoreFraudAlertIsRecordedWithoutFreezing(){
        consumer.consumeFraudAlert(event(30));

        assertEquals("ACTIVE",account.getStatus().name());
        verify(accounts,never()).findLocked(any());
        verify(accounts,never()).save(any());
        verify(redis,never()).delete(anyString());
        verify(jdbc).update(contains("insert into processed_events"),eq(eventId));
    }

    @Test void complianceFreezeRequestFreezesRegardlessOfScore(){
        consumer.consumeFreezeRequest(event(null));

        assertEquals("FROZEN",account.getStatus().name());
        verify(accounts).save(account);
        verify(jdbc).update(contains("outbox_events"),any(),eq(account.getId()),eq("account-frozen"),anyString());
    }

    @Test void duplicateEventIsIgnored(){
        when(jdbc.queryForObject(contains("processed_events"),eq(Integer.class),any(Object[].class))).thenReturn(1);

        consumer.consumeFraudAlert(event(100));

        verifyNoInteractions(accounts,redis);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    private String event(Integer score){
        String scoreField=score==null?"":"\"score\":"+score+",";
        return "{\"eventId\":\""+eventId+"\",\"accountId\":\""+account.getId()+"\","+scoreField+"\"eventType\":\"fraud-detected\"}";
    }
}

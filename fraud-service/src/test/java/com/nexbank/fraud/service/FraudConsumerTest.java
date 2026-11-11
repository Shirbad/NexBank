package com.nexbank.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.fraud.entity.FraudAlert;
import com.nexbank.fraud.repository.FraudAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudConsumerTest {
    private JdbcTemplate jdbc;
    private FraudAlertRepository alerts;
    private StringRedisTemplate redis;
    private ValueOperations<String,String> values;
    private FraudExplanationClient explanations;
    private FraudConsumer consumer;
    private UUID source;
    private UUID destination;
    private UUID transfer;
    private UUID user;
    private UUID event;

    @BeforeEach @SuppressWarnings("unchecked") void setUp() {
        jdbc=mock(JdbcTemplate.class); alerts=mock(FraudAlertRepository.class);
        redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class);
        explanations=mock(FraudExplanationClient.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(jdbc.queryForObject(contains("processed_events"),eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("known_recipients"),eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("observed_transfers"),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("observed_transfers"),eq(BigDecimal.class),any(Object[].class))).thenReturn(null);
        when(alerts.existsByTransferId(any())).thenReturn(false);
        consumer=new FraudConsumer(new ObjectMapper(),alerts,jdbc,redis,explanations,new BigDecimal("100000"));
        source=UUID.randomUUID(); destination=UUID.randomUUID(); transfer=UUID.randomUUID(); user=UUID.randomUUID(); event=UUID.randomUUID();
    }

    @Test void consume_flagsHighValueAndFirstRecipientAndWritesOutbox() {
        when(explanations.explain(any(),anyInt(),anyList())).thenReturn(null);
        consumer.consume(payload(new BigDecimal("125000.00"),"2026-11-11T14:00:00Z"));

        var captor=org.mockito.ArgumentCaptor.forClass(FraudAlert.class);
        verify(alerts).save(captor.capture());
        FraudAlert alert=captor.getValue();
        assertEquals(transfer,alert.getTransferId());
        assertTrue(alert.getReasons().contains("high-value"));
        verify(jdbc).update(contains("fraud_outbox_events"),any(),eq("fraud-detected"),eq(source),anyString());
    }

    @Test void consume_doesNotFlagKnownRecipientAtNormalAmountAndHour() {
        when(jdbc.queryForObject(contains("known_recipients"),eq(Integer.class),any(Object[].class))).thenReturn(1);
        consumer.consume(payload(new BigDecimal("25.00"),"2026-11-11T14:00:00Z"));

        verify(alerts,never()).save(any());
        verify(jdbc,never()).update(contains("fraud_outbox_events"),any(),any(),any(),any());
    }

    private String payload(BigDecimal amount,String timestamp) {
        return "{\"eventId\":\""+event+"\",\"eventType\":\"transfer-completed\",\"transferId\":\""+transfer+
                "\",\"fromAccountId\":\""+source+"\",\"toAccountId\":\""+destination+"\",\"userId\":\""+user+
                "\",\"amount\":"+amount.toPlainString()+",\"timestamp\":\""+timestamp+"\"}";
    }
}

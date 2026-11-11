package com.nexbank.fraud.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudOutboxPublisherTest {
    @Test void publishesAndMarksAcknowledgedFraudEvents(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        UUID eventId=UUID.randomUUID();String accountId=UUID.randomUUID().toString();String payload="{}";
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",eventId,"aggregate_id",accountId,"event_type","fraud-detected","payload",payload)));
        when(kafka.send("fraud-detected",accountId,payload)).thenReturn(CompletableFuture.completedFuture(null));

        new FraudOutboxPublisher(jdbc,kafka).publish();

        verify(jdbc).update(contains("set published=true"),eq(eventId));
    }

    @Test void propagatesSendFailureSoTheRowCanBeRetried(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        UUID eventId=UUID.randomUUID();String accountId=UUID.randomUUID().toString();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",eventId,"aggregate_id",accountId,"event_type","fraud-detected","payload","{}")));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThrows(IllegalStateException.class,()->new FraudOutboxPublisher(jdbc,kafka).publish());
        verify(jdbc,never()).update(contains("set published=true"),eq(eventId));
    }
}

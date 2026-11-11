package com.nexbank.account.service;

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

class OutboxPublisherTest {
    @Test void publishesPendingRowsAndMarksThemPublished() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        UUID eventId=UUID.randomUUID();
        String aggregateId=UUID.randomUUID().toString();
        String payload="{\"eventType\":\"transfer-completed\"}";
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",eventId,"aggregate_id",aggregateId,"event_type","transfer-completed","payload",payload)));
        when(kafka.send("transfer-completed",aggregateId,payload)).thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(jdbc,kafka).publish();

        verify(kafka).send("transfer-completed",aggregateId,payload);
        verify(jdbc).update(contains("set published=true"),eq(eventId));
    }

    @Test void leavesOutboxUnpublishedWhenKafkaSendFails() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        UUID eventId=UUID.randomUUID();
        String aggregateId=UUID.randomUUID().toString();
        String payload="{}";
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",eventId,"aggregate_id",aggregateId,"event_type","transfer-completed","payload",payload)));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThrows(IllegalStateException.class,()->new OutboxPublisher(jdbc,kafka).publish());
        verify(jdbc,never()).update(contains("set published=true"),eq(eventId));
    }
}

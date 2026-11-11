package com.nexbank.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationConsumerTest {
    @Test void consume_duplicateEventIsRecordedOnlyOnce() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        UUID event=UUID.randomUUID(), user=UUID.randomUUID();
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(event))).thenReturn(0,1);
        NotificationConsumer consumer=new NotificationConsumer(new ObjectMapper(),jdbc);
        String payload="{\"eventId\":\""+event+"\",\"eventType\":\"transfer-completed\",\"userId\":\""+user+"\"}";

        consumer.consume(payload);
        consumer.consume(payload);

        verify(jdbc,times(1)).update(contains("insert into notifications"),any(),eq(user),anyString());
        verify(jdbc,times(1)).update(contains("insert into audit_log"),any(),eq("transfer-completed"),eq(payload));
        verify(jdbc,times(1)).update(contains("insert into processed_events"),eq(event));
    }
}

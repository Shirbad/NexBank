package com.nexbank.fraud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.fraud.entity.FraudAlert;
import com.nexbank.fraud.repository.FraudAlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class FraudConsumer {
    private final ObjectMapper mapper;
    private final FraudAlertRepository alerts;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final FraudExplanationClient explanations;
    private final BigDecimal largeAmountThreshold;

    public FraudConsumer(ObjectMapper mapper, FraudAlertRepository alerts, JdbcTemplate jdbc,
                         StringRedisTemplate redis, FraudExplanationClient explanations,
                         @Value("${fraud.large-amount-threshold:100000}") BigDecimal largeAmountThreshold) {
        this.mapper=mapper; this.alerts=alerts; this.jdbc=jdbc; this.redis=redis;
        this.explanations=explanations; this.largeAmountThreshold=largeAmountThreshold;
    }

    @KafkaListener(topics="transfer-completed", groupId="fraud-service")
    @Transactional
    public void consume(String payload) {
        try {
            JsonNode event=mapper.readTree(payload);
            UUID eventId=UUID.fromString(event.path("eventId").asText());
            Integer processedCount=jdbc.queryForObject("select count(*) from processed_events where event_id=?",Integer.class,eventId);
            if (processedCount!=null && processedCount>0) return;

            UUID transferId=UUID.fromString(event.path("transferId").asText());
            UUID source=UUID.fromString(event.path("fromAccountId").asText());
            UUID destination=UUID.fromString(event.path("toAccountId").asText());
            UUID userId=UUID.fromString(event.path("userId").asText());
            BigDecimal amount=event.path("amount").decimalValue();
            Instant occurredAt=Instant.parse(event.path("timestamp").asText());

            Long previousCount=jdbc.queryForObject("select count(*) from observed_transfers where source_account_id=?",Long.class,source);
            long priorCount=previousCount==null?0L:previousCount;
            BigDecimal priorAverage=jdbc.queryForObject("select avg(amount) from observed_transfers where source_account_id=?",BigDecimal.class,source);
            Long countLastMinute=redis.opsForValue().increment("fraud:account:"+source+":minute");
            if (countLastMinute!=null && countLastMinute==1L) redis.expire("fraud:account:"+source+":minute",Duration.ofSeconds(60));

            Integer recipientCount=jdbc.queryForObject("select count(*) from known_recipients where source_account_id=? and destination_account_id=?",Integer.class,source,destination);
            int recipientExists=recipientCount==null?0:recipientCount;
            boolean newRecipient=recipientExists==0;
            jdbc.update("insert into known_recipients(source_account_id,destination_account_id,first_seen_at) values (?,?,?) on conflict do nothing",source,destination,occurredAt);

            List<String> reasons=new ArrayList<>();
            if (amount.compareTo(largeAmountThreshold)>=0) reasons.add("Transfer is at or above the high-value threshold");
            if (countLastMinute!=null && countLastMinute>5) reasons.add("More than five transfers were attempted in the last minute");
            if (newRecipient) reasons.add("This is the first observed transfer to this recipient");
            LocalTime utcTime=LocalTime.ofInstant(occurredAt,ZoneOffset.UTC);
            if (utcTime.isBefore(LocalTime.of(6,0)) || !utcTime.isBefore(LocalTime.of(22,0))) reasons.add("Transfer occurred during an unusual UTC hour");
            if (priorCount>=3 && priorAverage!=null && amount.compareTo(priorAverage.multiply(new BigDecimal("3")))>0)
                reasons.add("Amount is more than three times the customer's prior average");

            jdbc.update("insert into observed_transfers(event_id,source_account_id,destination_account_id,amount,occurred_at) values (?,?,?,?,?)",eventId,source,destination,amount,occurredAt);
            if (!reasons.isEmpty() && !alerts.existsByTransferId(transferId)) {
                int score=Math.min(100,reasons.size()*20);
                String joined=String.join("; ",reasons);
                String explanation=explanations.explain(amount,score,reasons);
                alerts.save(new FraudAlert(transferId,source,userId,score,joined,explanation));
                UUID fraudEventId=UUID.randomUUID();
                String fraudPayload=mapper.writeValueAsString(Map.of("eventId",fraudEventId,"eventType","fraud-detected","transferId",transferId,"accountId",source,"userId",userId,"score",score,"reasons",joined,"aiExplanation",explanation==null?"":explanation,"timestamp",Instant.now().toString()));
                jdbc.update("insert into fraud_outbox_events(id,event_type,aggregate_id,payload) values (?,?,?,cast(? as jsonb))",fraudEventId,"fraud-detected",source,fraudPayload);
            }
            jdbc.update("insert into processed_events(event_id) values (?)",eventId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not process transfer event",e);
        }
    }
}

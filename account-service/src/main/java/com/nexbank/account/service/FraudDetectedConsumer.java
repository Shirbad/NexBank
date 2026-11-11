package com.nexbank.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.entity.Account;
import com.nexbank.account.repository.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class FraudDetectedConsumer {
    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    public FraudDetectedConsumer(AccountRepository accounts,JdbcTemplate jdbc,ObjectMapper mapper,StringRedisTemplate redis){this.accounts=accounts;this.jdbc=jdbc;this.mapper=mapper;this.redis=redis;}

    @KafkaListener(topics="fraud-detected",groupId="account-service")
    @Transactional
    public void consumeFraudAlert(String payload){ process(payload,false); }

    @KafkaListener(topics="account-freeze-requested",groupId="account-service")
    @Transactional
    public void consumeFreezeRequest(String payload){ process(payload,true); }

    private void process(String payload,boolean forceFreeze){
        try{
            JsonNode event=mapper.readTree(payload);
            UUID eventId=UUID.fromString(event.path("eventId").asText());
            if(jdbc.queryForObject("select count(*) from processed_events where event_id=?",Integer.class,eventId)>0)return;
            if(!forceFreeze && event.path("score").asInt()<80){jdbc.update("insert into processed_events(event_id) values (?)",eventId);return;}
            UUID accountId=UUID.fromString(event.path("accountId").asText());
            Account account=accounts.findLocked(accountId).orElseThrow(()->new IllegalArgumentException("Fraud event references an unknown account"));
            if(account.getStatus()!=com.nexbank.account.entity.AccountStatus.FROZEN){
                account.freeze(); accounts.save(account);
                evictAccountCacheAfterCommit(accountId);
                UUID frozenEventId=UUID.randomUUID();
                String body=mapper.writeValueAsString(Map.of("eventId",frozenEventId,"eventType","account-frozen","accountId",accountId,"userId",account.getOwnerUserId(),"reason","fraud-review","timestamp",Instant.now().toString()));
                jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,published) values (?,?,?,cast(? as jsonb),false)",frozenEventId,accountId,"account-frozen",body);
            }
            jdbc.update("insert into processed_events(event_id) values (?)",eventId);
        }catch(Exception e){throw new IllegalArgumentException("Could not process fraud event",e);}
    }

    private void evictAccountCacheAfterCommit(UUID accountId){
        Runnable eviction=()->{try{redis.delete("nexbank:account:"+accountId);}catch(RuntimeException ignored){/* The database remains authoritative. */}};
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){eviction.run();}});else eviction.run();
    }
}

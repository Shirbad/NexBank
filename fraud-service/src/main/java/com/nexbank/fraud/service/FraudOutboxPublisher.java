package com.nexbank.fraud.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Component
public class FraudOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    public FraudOutboxPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka){this.jdbc=jdbc;this.kafka=kafka;}

    @Scheduled(fixedDelayString="${OUTBOX_POLL_MS:1000}")
    @Transactional
    public void publish(){
        List<Map<String,Object>> batch=jdbc.queryForList("select id,aggregate_id,event_type,payload::text as payload from fraud_outbox_events where published=false order by created_at limit 50 for update skip locked");
        for(Map<String,Object> row:batch){
            try{
                kafka.send((String)row.get("event_type"),row.get("aggregate_id").toString(),(String)row.get("payload")).get();
                jdbc.update("update fraud_outbox_events set published=true where id=?",row.get("id"));
            }catch(Exception e){throw new IllegalStateException("Fraud outbox publish failed; event will be retried",e);}
        }
    }
}

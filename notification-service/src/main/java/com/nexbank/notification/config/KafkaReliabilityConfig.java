package com.nexbank.notification.config;
import org.apache.kafka.common.TopicPartition;import org.apache.kafka.clients.admin.NewTopic;import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;import org.springframework.kafka.config.TopicBuilder;import org.springframework.kafka.core.KafkaTemplate;import org.springframework.kafka.listener.CommonErrorHandler;import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;import org.springframework.kafka.listener.DefaultErrorHandler;import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
@Configuration public class KafkaReliabilityConfig {
 @Bean CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object,Object> template){var backoff=new ExponentialBackOffWithMaxRetries(3);backoff.setInitialInterval(1000);backoff.setMultiplier(2);backoff.setMaxInterval(10000);var recoverer=new DeadLetterPublishingRecoverer(template,(record,error)->new TopicPartition(record.topic()+".DLT",record.partition()));return new DefaultErrorHandler(recoverer,backoff);}
 @Bean NewTopic transferCompletedDlt(){return TopicBuilder.name("transfer-completed.DLT").partitions(1).replicas(1).build();}
 @Bean NewTopic fraudDetectedDlt(){return TopicBuilder.name("fraud-detected.DLT").partitions(1).replicas(1).build();}
 @Bean NewTopic accountFrozenDlt(){return TopicBuilder.name("account-frozen.DLT").partitions(1).replicas(1).build();}
 @Bean NewTopic accountFreezeRequestedDlt(){return TopicBuilder.name("account-freeze-requested.DLT").partitions(1).replicas(1).build();}
}

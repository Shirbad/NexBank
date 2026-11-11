package com.nexbank.account.service;

import com.nexbank.account.AccountServiceApplication;

import com.nexbank.account.dto.AccountDtos.TransferRequest;
import com.nexbank.account.entity.Account;
import com.nexbank.account.entity.Transfer;
import com.nexbank.account.repository.AccountRepository;
import com.nexbank.account.repository.LedgerRepository;
import com.nexbank.account.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(classes=AccountServiceApplication.class,properties={
        "spring.kafka.listener.auto-startup=false", "OUTBOX_POLL_MS=600000", "spring.data.redis.repositories.enabled=false"})
class BankingServicePostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("account_db").withUsername("nexbank").withPassword("nexbank-test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers",()->"localhost:9092");
    }

    @Autowired BankingService banking;
    @Autowired AccountRepository accounts;
    @Autowired LedgerRepository ledger;
    @Autowired TransferRepository transfers;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void cleanDatabase(){jdbc.execute("TRUNCATE TABLE staff_action_audit, outbox_events, ledger_entries, transfers, accounts RESTART IDENTITY CASCADE");}

    @Test void transfer_isIdempotentWithRealPostgres() {
        UUID owner=UUID.randomUUID(); Account source=banking.open(owner); Account destination=banking.open(owner);
        banking.deposit(source.getId(),owner,false,new BigDecimal("1000.00"));
        TransferRequest request=new TransferRequest(source.getId(),destination.getId(),new BigDecimal("100.00"));

        Transfer first=banking.transfer(owner,request,"tc-idempotency-1");
        Transfer retry=banking.transfer(owner,request,"tc-idempotency-1");

        assertEquals(first.getId(),retry.getId());
        assertEquals(new BigDecimal("900.00"),accounts.findById(source.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("100.00"),accounts.findById(destination.getId()).orElseThrow().getBalance());
        assertEquals(2,ledger.count());
        assertEquals(1,transfers.count());
    }

    @Test void transfer_rollsBackAllRowsWhenOutboxInsertFails() {
        UUID owner=UUID.randomUUID(); Account source=banking.open(owner); Account destination=banking.open(owner);
        banking.deposit(source.getId(),owner,false,new BigDecimal("1000.00"));
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_outbox_insert() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'forced test failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");
        try {
            assertThrows(RuntimeException.class,()->banking.transfer(owner,
                    new TransferRequest(source.getId(),destination.getId(),new BigDecimal("100.00")),"tc-rollback-1"));
            assertEquals(new BigDecimal("1000.00"),accounts.findById(source.getId()).orElseThrow().getBalance());
            assertEquals(BigDecimal.ZERO.setScale(2),accounts.findById(destination.getId()).orElseThrow().getBalance());
            assertEquals(0,transfers.count());
            assertEquals(1,ledger.count());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_outbox ON outbox_events");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_outbox_insert()");
        }
    }

    @Test void fiftyConcurrentTransfersNeverMakeBalanceNegative() throws Exception {
        UUID owner=UUID.randomUUID(); Account source=banking.open(owner); Account destination=banking.open(owner);
        banking.deposit(source.getId(),owner,false,new BigDecimal("1000.00"));
        int workers=50; CountDownLatch ready=new CountDownLatch(workers); CountDownLatch start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(workers); List<Future<Boolean>> futures=new ArrayList<>();
        for(int i=0;i<workers;i++){
            final int n=i; futures.add(pool.submit(()->{ready.countDown();start.await();try{banking.transfer(owner,
                    new TransferRequest(source.getId(),destination.getId(),new BigDecimal("100.00")),"tc-concurrent-"+n);return true;}
                catch(IllegalArgumentException insufficient){return false;}}));
        }
        assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown(); int successful=0;
        try {for(Future<Boolean> future:futures)if(future.get(60,TimeUnit.SECONDS))successful++;}
        finally {pool.shutdownNow();}
        assertEquals(10,successful);
        assertEquals(BigDecimal.ZERO.setScale(2),accounts.findById(source.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("1000.00"),accounts.findById(destination.getId()).orElseThrow().getBalance());
    }

    @Test void oppositeTransfersCompleteWithoutDeadlock() throws Exception {
        UUID owner=UUID.randomUUID(); Account left=banking.open(owner); Account right=banking.open(owner);
        banking.deposit(left.getId(),owner,false,new BigDecimal("500.00"));
        banking.deposit(right.getId(),owner,false,new BigDecimal("500.00"));
        CountDownLatch start=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Transfer> one=pool.submit(()->{start.await();return banking.transfer(owner,new TransferRequest(left.getId(),right.getId(),new BigDecimal("10.00")),"tc-opposite-1");});
            Future<Transfer> two=pool.submit(()->{start.await();return banking.transfer(owner,new TransferRequest(right.getId(),left.getId(),new BigDecimal("20.00")),"tc-opposite-2");});
            start.countDown(); one.get(20,TimeUnit.SECONDS); two.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(new BigDecimal("1000.00"),accounts.findById(left.getId()).orElseThrow().getBalance().add(accounts.findById(right.getId()).orElseThrow().getBalance()));
        assertEquals(2,transfers.count());
    }
}

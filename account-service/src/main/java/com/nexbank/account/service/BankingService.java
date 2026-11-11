package com.nexbank.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.dto.AccountDtos.*;
import com.nexbank.account.entity.*;
import com.nexbank.account.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

@Service
public class BankingService {
    private static final Duration IDEMPOTENCY_CACHE_TTL=Duration.ofHours(24);
    private final AccountRepository accounts; private final LedgerRepository ledger; private final TransferRepository transfers; private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final StringRedisTemplate redis;
    public BankingService(AccountRepository a,LedgerRepository l,TransferRepository t,JdbcTemplate j,ObjectMapper m,StringRedisTemplate r){accounts=a;ledger=l;transfers=t;jdbc=j;mapper=m;redis=r;}
    @Transactional public Account open(UUID owner){return accounts.save(new Account(owner));}
    @Transactional(readOnly=true) public Account get(UUID id, UUID actor, boolean staff){Account a=accounts.findById(id).orElseThrow(()->new EntityNotFoundException("Account not found"));own(a,actor,staff);return a;}
    @Transactional public Account deposit(UUID id,UUID actor,boolean staff,BigDecimal amount){positive(amount);Account a=accounts.findLocked(id).orElseThrow(()->new EntityNotFoundException("Account not found"));own(a,actor,staff);active(a);a.credit(amount);accounts.save(a);ledger.save(new LedgerEntry(id,LedgerEntry.EntryType.CREDIT,amount,a.getBalance(),null));return a;}
    @Transactional public Account withdraw(UUID id,UUID actor,BigDecimal amount){positive(amount);Account a=accounts.findLocked(id).orElseThrow(()->new EntityNotFoundException("Account not found"));own(a,actor,false);active(a);if(a.getBalance().compareTo(amount)<0)throw new IllegalArgumentException("Insufficient funds");a.debit(amount);accounts.save(a);ledger.save(new LedgerEntry(id,LedgerEntry.EntryType.DEBIT,amount,a.getBalance(),null));return a;}
    @Transactional public Transfer transfer(UUID actor,TransferRequest r,String key){
        positive(r.amount());if(r.fromAccountId().equals(r.toAccountId()))throw new IllegalArgumentException("Source and destination must differ");
        if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("A valid Idempotency-Key header is required");
        lockIdempotencyKey(key);
        Optional<Transfer> replay=cachedTransfer(key).or(()->transfers.findByIdempotencyKey(key));if(replay.isPresent()){Transfer old=replay.get();validateReplay(old,r,actor);cacheAfterCommit(key,old.getId());return old;}
        List<Account> locked=accounts.lockAll(List.of(r.fromAccountId(),r.toAccountId()));if(locked.size()!=2)throw new EntityNotFoundException("Account not found");
        Account from=locked.stream().filter(a->a.getId().equals(r.fromAccountId())).findFirst().orElseThrow();Account to=locked.stream().filter(a->a.getId().equals(r.toAccountId())).findFirst().orElseThrow();
        own(from,actor,false);active(from);active(to);if(from.getBalance().compareTo(r.amount())<0)throw new IllegalArgumentException("Insufficient funds");
        from.debit(r.amount());to.credit(r.amount());Transfer tx=transfers.saveAndFlush(new Transfer(from.getId(),to.getId(),r.amount(),key));
        ledger.save(new LedgerEntry(from.getId(),LedgerEntry.EntryType.DEBIT,r.amount(),from.getBalance(),tx.getId()));ledger.save(new LedgerEntry(to.getId(),LedgerEntry.EntryType.CREDIT,r.amount(),to.getBalance(),tx.getId()));
        accounts.saveAll(locked);writeOutbox(tx,from.getOwnerUserId());cacheAfterCommit(key,tx.getId());return tx;
    }
    @Transactional(readOnly=true) public Page<LedgerEntry> history(UUID id,UUID actor,boolean staff,Pageable page){get(id,actor,staff);return ledger.findByAccountIdOrderByCreatedAtDesc(id,page);}
    @Transactional public void freeze(UUID id,boolean frozen,UUID actor){Account a=accounts.findLocked(id).orElseThrow(()->new EntityNotFoundException("Account not found"));if(frozen)a.freeze();else a.unfreeze();accounts.save(a);jdbc.update("insert into staff_action_audit(id,actor_user_id,account_id,action) values (?,?,?,?)",UUID.randomUUID(),actor,id,frozen?"ACCOUNT_FROZEN":"ACCOUNT_UNFROZEN");}
    private void writeOutbox(Transfer tx,UUID ownerUserId){try{UUID eventId=UUID.randomUUID();String json=mapper.writeValueAsString(Map.of("eventId",eventId,"eventType","transfer-completed","transferId",tx.getId(),"fromAccountId",tx.getFromAccountId(),"toAccountId",tx.getToAccountId(),"userId",ownerUserId,"amount",tx.getAmount(),"currency","INR","timestamp",tx.getCreatedAt().toString()));jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,published) values (?,?,?,cast(? as jsonb),false)",eventId,tx.getFromAccountId(),"transfer-completed",json);}catch(JsonProcessingException e){throw new IllegalStateException("Could not serialize transfer event",e);}}
    private void lockIdempotencyKey(String key){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",ps->ps.setString(1,key),rs->{while(rs.next()){}return null;});}
    private Optional<Transfer> cachedTransfer(String key){try{String id=redis.opsForValue().get(cacheKey(key));return id==null?Optional.empty():transfers.findById(UUID.fromString(id));}catch(RuntimeException e){return Optional.empty();}}
    private void cacheAfterCommit(String key,UUID transferId){Runnable cache=()->{try{redis.opsForValue().set(cacheKey(key),transferId.toString(),IDEMPOTENCY_CACHE_TTL);}catch(RuntimeException ignored){/* PostgreSQL remains the source of truth. */}};if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){cache.run();}});else cache.run();}
    private String cacheKey(String key){try{return "nexbank:transfer:idempotency:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void validateReplay(Transfer old,TransferRequest request,UUID actor){if(!old.getFromAccountId().equals(request.fromAccountId())||!old.getToAccountId().equals(request.toAccountId())||old.getAmount().compareTo(request.amount())!=0)throw new IllegalArgumentException("Idempotency-Key was already used for a different request");Account owner=accounts.findById(old.getFromAccountId()).orElseThrow(()->new EntityNotFoundException("Account not found"));own(owner,actor,false);}
    private void own(Account a,UUID actor,boolean staff){if(!staff&&!a.getOwnerUserId().equals(actor))throw new org.springframework.security.access.AccessDeniedException("You do not own this account");}
    private void active(Account a){if(a.getStatus()!=AccountStatus.ACTIVE)throw new IllegalStateException("Account is frozen");}
    private void positive(BigDecimal amount){if(amount==null||amount.signum()<=0||amount.scale()>2||amount.precision()>19)throw new IllegalArgumentException("Amount must be positive with at most two decimal places");}
}

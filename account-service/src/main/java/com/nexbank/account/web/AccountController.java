package com.nexbank.account.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexbank.account.dto.AccountDtos.*;
import com.nexbank.account.entity.*;
import com.nexbank.account.service.BankingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AccountController {
    private static final Duration ACCOUNT_CACHE_TTL=Duration.ofMinutes(5);
    private final BankingService banking;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AccountController(BankingService banking,StringRedisTemplate redis,ObjectMapper mapper){
        this.banking=banking;this.redis=redis;this.mapper=mapper;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountView> open(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="") String role,
            @Valid @RequestBody OpenAccountRequest request){
        require(role,"CUSTOMER");
        return ResponseEntity.status(201).body(view(banking.open(actor)));
    }

    @GetMapping("/accounts/{id}")
    public AccountView get(@PathVariable UUID id,@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="CUSTOMER") String role){
        requireAny(role,"CUSTOMER","TELLER","ADMIN");
        AccountView cached=readAccountCache(id,actor,role);
        if(cached!=null)return cached;
        Account account=banking.get(id,actor,isStaff(role));
        AccountView result=view(account);
        cacheAccount(result);
        return result;
    }

    @PostMapping("/accounts/{id}/deposit")
    public AccountView deposit(@PathVariable UUID id,@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="CUSTOMER") String role,
            @Valid @RequestBody MoneyRequest request){
        requireAny(role,"CUSTOMER","TELLER");
        AccountView result=view(banking.deposit(id,actor,isStaff(role),request.amount()));
        evictAccount(id);
        return result;
    }

    @PostMapping("/accounts/{id}/withdraw")
    public AccountView withdraw(@PathVariable UUID id,@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="") String role,
            @Valid @RequestBody MoneyRequest request){
        require(role,"CUSTOMER");
        AccountView result=view(banking.withdraw(id,actor,request.amount()));
        evictAccount(id);
        return result;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferView> transfer(@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="") String role,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody TransferRequest request){
        require(role,"CUSTOMER");
        Transfer result=banking.transfer(actor,request,key);
        evictAccount(request.fromAccountId());
        evictAccount(request.toAccountId());
        return ResponseEntity.status(201).body(view(result));
    }

    @GetMapping("/accounts/{id}/transactions")
    public Page<LedgerView> history(@PathVariable UUID id,@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="CUSTOMER") String role,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        requireAny(role,"CUSTOMER","TELLER","ADMIN");
        Pageable pageable=PageRequest.of(page,Math.min(size,100),Sort.by("createdAt").descending());
        return banking.history(id,actor,isStaff(role),pageable).map(e->
                new LedgerView(e.getId(),e.getType().name(),e.getAmount(),e.getBalanceAfter(),e.getTransferId(),e.getCreatedAt()));
    }

    @PutMapping("/admin/accounts/{id}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void freeze(@PathVariable UUID id,@RequestHeader("X-User-Id") UUID actor,
            @RequestHeader(value="X-User-Role",defaultValue="") String role,@RequestParam boolean frozen){
        require(role,"ADMIN");
        banking.freeze(id,frozen,actor);
        evictAccount(id);
    }

    private AccountView readAccountCache(UUID id,UUID actor,String role){
        String json;
        try{json=redis.opsForValue().get(accountCacheKey(id));}catch(RuntimeException unavailable){return null;}
        if(json==null)return null;
        try{
            AccountView cached=mapper.readValue(json,AccountView.class);
            if(!isStaff(role)&&!cached.ownerUserId().equals(actor))
                throw new org.springframework.security.access.AccessDeniedException("You do not own this account");
            return cached;
        }catch(JsonProcessingException invalid){evictAccount(id);return null;}
    }

    private void cacheAccount(AccountView account){
        try{redis.opsForValue().set(accountCacheKey(account.id()),mapper.writeValueAsString(account),ACCOUNT_CACHE_TTL);}
        catch(JsonProcessingException|RuntimeException ignored){/* Cache failures never affect account reads. */}
    }

    private void evictAccount(UUID id){try{redis.delete(accountCacheKey(id));}catch(RuntimeException ignored){/* PostgreSQL remains authoritative. */}}
    private String accountCacheKey(UUID id){return "nexbank:account:"+id;}
    private boolean isStaff(String role){return role.equals("ADMIN")||role.equals("TELLER");}
    private void require(String actual,String expected){if(!actual.equals(expected))throw new org.springframework.security.access.AccessDeniedException(expected+" role required");}
    private void requireAny(String actual,String... expected){if(Arrays.stream(expected).noneMatch(actual::equals))throw new org.springframework.security.access.AccessDeniedException("Insufficient role");}
    private AccountView view(Account account){return new AccountView(account.getId(),account.getAccountNumber(),account.getOwnerUserId(),account.getBalance(),account.getStatus().name(),account.getCreatedAt());}
    private TransferView view(Transfer transfer){return new TransferView(transfer.getId(),transfer.getFromAccountId(),transfer.getToAccountId(),transfer.getAmount(),transfer.getStatus(),transfer.getCreatedAt());}
}

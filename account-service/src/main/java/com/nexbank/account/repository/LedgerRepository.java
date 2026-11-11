package com.nexbank.account.repository;
import com.nexbank.account.entity.LedgerEntry;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface LedgerRepository extends JpaRepository<LedgerEntry,UUID> { Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable); }

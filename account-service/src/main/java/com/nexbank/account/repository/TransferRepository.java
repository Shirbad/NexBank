package com.nexbank.account.repository;
import com.nexbank.account.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TransferRepository extends JpaRepository<Transfer,UUID> { Optional<Transfer> findByIdempotencyKey(String key); }

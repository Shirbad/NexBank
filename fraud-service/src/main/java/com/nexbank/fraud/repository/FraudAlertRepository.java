package com.nexbank.fraud.repository;
import com.nexbank.fraud.entity.FraudAlert;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface FraudAlertRepository extends JpaRepository<FraudAlert,UUID>{boolean existsByTransferId(UUID transferId);List<FraudAlert> findByStatusOrderByCreatedAtDesc(String status);}

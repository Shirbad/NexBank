package com.nexbank.auth.repository;
import com.nexbank.auth.entity.BankUser; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface UserRepository extends JpaRepository<BankUser,UUID>{Optional<BankUser> findByEmailIgnoreCase(String email);boolean existsByEmailIgnoreCase(String email);}

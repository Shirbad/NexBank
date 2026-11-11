package com.nexbank.account.repository;
import com.nexbank.account.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.util.stream.Stream;
public interface AccountRepository extends JpaRepository<Account,UUID> {
    List<Account> findByOwnerUserId(UUID ownerUserId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Account a where a.id=:id") Optional<Account> findLocked(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Account a where a.id in :ids order by a.id") List<Account> lockAll(@Param("ids") Collection<UUID> ids);
}

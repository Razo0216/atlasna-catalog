package com.atlasna.catalog.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Every query is scoped to the owner: another user's address is simply not found (404, never 403). */
public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByUserIdOrderByIsDefaultDescIdAsc(Long userId);
    Optional<Address> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Address a SET a.isDefault = false WHERE a.userId = :userId AND a.isDefault = true")
    int clearDefault(Long userId);
}

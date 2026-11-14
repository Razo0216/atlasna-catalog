package com.atlasna.catalog.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliverySlotRepository extends JpaRepository<DeliverySlot, Long> {
    List<DeliverySlot> findByActiveTrueOrderByStartTimeAscIdAsc();
    List<DeliverySlot> findAllByOrderByStartTimeAscIdAsc();
}

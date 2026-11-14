package com.atlasna.catalog.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, Long> {
    List<DeliveryZone> findByActiveTrueOrderBySortOrderAscIdAsc();
    List<DeliveryZone> findAllByOrderBySortOrderAscIdAsc();
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
}

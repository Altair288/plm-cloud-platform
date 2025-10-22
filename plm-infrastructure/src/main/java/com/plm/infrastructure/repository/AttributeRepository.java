package com.plm.infrastructure.repository;

import com.plm.common.domain.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttributeRepository extends JpaRepository<Attribute, UUID> {
    boolean existsByCategoryIdAndCode(UUID categoryId, String code);
    List<Attribute> findByCategoryIdOrderBySortOrderAsc(UUID categoryId);
    Optional<Attribute> findByCategoryIdAndCode(UUID categoryId, String code);
}

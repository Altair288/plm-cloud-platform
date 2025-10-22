package com.plm.infrastructure.repository;

import com.plm.common.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    boolean existsByCode(String code);
    Optional<Category> findByCode(String code);
}

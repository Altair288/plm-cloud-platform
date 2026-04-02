package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaWorkbookImportSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaWorkbookImportSnapshotRepository extends JpaRepository<MetaWorkbookImportSnapshot, UUID> {

    Optional<MetaWorkbookImportSnapshot> findByImportSessionId(String importSessionId);

    Optional<MetaWorkbookImportSnapshot> findByImportSessionIdAndExpiresAtAfter(String importSessionId, OffsetDateTime now);

    Optional<MetaWorkbookImportSnapshot> findByDryRunJobIdAndExpiresAtAfter(String dryRunJobId, OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from MetaWorkbookImportSnapshot s
            where s.expiresAt < :now
            """)
    int deleteByExpiresAtBefore(@Param("now") OffsetDateTime now);
}
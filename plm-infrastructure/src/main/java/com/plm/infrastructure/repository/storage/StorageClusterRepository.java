package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.StorageCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StorageClusterRepository extends JpaRepository<StorageCluster, UUID> {
    @Query("select sc from StorageCluster sc where lower(sc.clusterCode) = lower(:clusterCode)")
    Optional<StorageCluster> findByClusterCodeIgnoreCase(@Param("clusterCode") String clusterCode);

    Optional<StorageCluster> findByStatus(String status);

    @Query("select sc from StorageCluster sc order by sc.createdAt desc")
    List<StorageCluster> findAllOrderByCreatedAtDesc();

    @Modifying
    @Query("update StorageCluster sc set sc.status = :inactiveStatus, sc.updatedBy = :updatedBy, sc.updatedAt = CURRENT_TIMESTAMP " +
            "where sc.id <> :clusterId and sc.status = :activeStatus")
    int deactivateOtherActiveClusters(@Param("clusterId") UUID clusterId,
                                      @Param("activeStatus") String activeStatus,
                                      @Param("inactiveStatus") String inactiveStatus,
                                      @Param("updatedBy") String updatedBy);
}
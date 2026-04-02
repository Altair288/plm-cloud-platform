package com.plm.common.version.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_workbook_import_snapshot", schema = "plm_meta")
@Getter
@Setter
public class MetaWorkbookImportSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "import_session_id", nullable = false, length = 64)
    private String importSessionId;

    @Column(name = "dry_run_job_id", length = 64)
    private String dryRunJobId;

    @Column(name = "operator_name", length = 64)
    private String operatorName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", nullable = false)
    private String optionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false)
    private String responseJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories_json", nullable = false)
    private String categoriesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes_json", nullable = false)
    private String attributesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_options_json", nullable = false)
    private String enumOptionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "existing_data_json", nullable = false)
    private String existingDataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_plan_json", nullable = false)
    private String executionPlanJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "staged_execution_plan_json")
    private String stagedExecutionPlanJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
package com.plm.common.version.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meta_dictionary_def", schema = "plm_meta")
@Getter
@Setter
public class MetaDictionaryDef {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dict_code", nullable = false, length = 64)
    private String dictCode;

    @Column(name = "dict_name", nullable = false, length = 128)
    private String dictName;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (sourceType == null) {
            sourceType = "DB";
        }
        if (locale == null) {
            locale = "zh-CN";
        }
        if (status == null) {
            status = "active";
        }
        if (versionNo == null) {
            versionNo = 1;
        }
    }
}

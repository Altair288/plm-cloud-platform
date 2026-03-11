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
@Table(name = "meta_dictionary_scene", schema = "plm_meta")
@Getter
@Setter
public class MetaDictionaryScene {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scene_code", nullable = false, length = 64)
    private String sceneCode;

    @Column(name = "scene_name", nullable = false, length = 128)
    private String sceneName;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "dictionary_codes", nullable = false)
    private String dictionaryCodes;

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
        if (locale == null) {
            locale = "zh-CN";
        }
        if (status == null) {
            status = "active";
        }
    }
}

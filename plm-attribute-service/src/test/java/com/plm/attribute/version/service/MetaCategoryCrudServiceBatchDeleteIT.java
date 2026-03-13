package com.plm.attribute.version.service;

import com.plm.common.api.dto.MetaCategoryBatchDeleteRequestDto;
import com.plm.common.api.dto.MetaCategoryBatchDeleteResponseDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaCategoryCrudServiceBatchDeleteIT {

    @Autowired
    private MetaCategoryCrudService crudService;

    @Autowired
    private MetaCategoryDefRepository defRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void batchDelete_nonAtomic_shouldKeepSuccessfulItemsWhenOneFails() {
        MetaCategoryDef existing = createActiveCategoryDef();
        UUID missingId = UUID.randomUUID();

        MetaCategoryBatchDeleteRequestDto request = new MetaCategoryBatchDeleteRequestDto();
        request.setIds(List.of(existing.getId(), missingId));
        request.setCascade(false);
        request.setConfirm(false);
        request.setAtomic(false);
        request.setDryRun(false);
        request.setOperator("it-test");

        MetaCategoryBatchDeleteResponseDto response = crudService.batchDelete(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(1, response.getSuccessCount());
        Assertions.assertEquals(1, response.getFailureCount());
        Assertions.assertEquals(1, response.getDeletedCount());

        MetaCategoryDef reloaded = defRepository.findById(existing.getId()).orElseThrow();
        Assertions.assertEquals("deleted", reloaded.getStatus());
    }

    @Test
    void batchDelete_atomic_shouldRollbackAllWhenAnyItemFails() {
        MetaCategoryDef existing = createActiveCategoryDef();
        UUID missingId = UUID.randomUUID();

        MetaCategoryBatchDeleteRequestDto request = new MetaCategoryBatchDeleteRequestDto();
        request.setIds(List.of(existing.getId(), missingId));
        request.setCascade(false);
        request.setConfirm(false);
        request.setAtomic(true);
        request.setDryRun(false);
        request.setOperator("it-test");

        MetaCategoryBatchDeleteResponseDto response = crudService.batchDelete(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(0, response.getSuccessCount());
        Assertions.assertEquals(2, response.getFailureCount());
        Assertions.assertEquals(0, response.getDeletedCount());

        MetaCategoryDef reloaded = defRepository.findById(existing.getId()).orElseThrow();
        Assertions.assertEquals("active", reloaded.getStatus());
    }

    private MetaCategoryDef createActiveCategoryDef() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate template = new TransactionTemplate(transactionManager, definition);
        return template.execute(status -> {
            MetaCategoryDef def = new MetaCategoryDef();
            def.setBusinessDomain("MATERIAL");
            def.setCodeKey("IT-BATCH-" + UUID.randomUUID());
            def.setStatus("active");
            def.setSortOrder(1);
            def.setIsLeaf(true);
            def.setCreatedBy("it-test");
            return defRepository.save(def);
        });
    }
}

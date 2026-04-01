package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.dictionary.MetaDictionaryBatchRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
public class MetaDictionaryControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dictionaryEndpoints_shouldReturnSeededData() throws Exception {
        MetaDictionaryBatchRequestDto batchRequest = new MetaDictionaryBatchRequestDto();
        batchRequest.setCodes(List.of("meta_category_business_domain", "meta_category_status"));
        batchRequest.setLang("zh-CN");
        batchRequest.setIncludeDisabled(Boolean.FALSE);

        mockMvc.perform(post("/api/meta/dictionaries:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].code").value("META_CATEGORY_BUSINESS_DOMAIN"))
                .andExpect(jsonPath("$.items[1].code").value("META_CATEGORY_STATUS"));

        mockMvc.perform(get("/api/meta/dictionaries/{code}", "meta_category_status")
                        .param("lang", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("META_CATEGORY_STATUS"))
                .andExpect(jsonPath("$.entries.length()").isNumber())
                .andExpect(jsonPath("$.entries[0].label").isNotEmpty());

        mockMvc.perform(get("/api/meta/dictionary-scenes/{sceneCode}", "category-admin")
                        .param("lang", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].code").value("META_CATEGORY_BUSINESS_DOMAIN"))
                .andExpect(jsonPath("$.items[1].code").value("META_CATEGORY_STATUS"));
    }

    @Test
    void batchEndpoint_shouldRejectEmptyCodes() throws Exception {
        MetaDictionaryBatchRequestDto batchRequest = new MetaDictionaryBatchRequestDto();
        batchRequest.setCodes(List.of());

        mockMvc.perform(post("/api/meta/dictionaries:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(batchRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(containsString("codes is required")));
    }
}
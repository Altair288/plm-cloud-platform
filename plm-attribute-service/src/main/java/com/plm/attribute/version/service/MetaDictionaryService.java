package com.plm.attribute.version.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.MetaDictionaryBatchRequestDto;
import com.plm.common.api.dto.MetaDictionaryBatchResponseDto;
import com.plm.common.api.dto.MetaDictionaryDto;
import com.plm.common.api.dto.MetaDictionaryEntryDto;
import com.plm.common.version.domain.MetaDictionaryDef;
import com.plm.common.version.domain.MetaDictionaryItem;
import com.plm.common.version.domain.MetaDictionaryScene;
import com.plm.infrastructure.version.repository.MetaDictionaryDefRepository;
import com.plm.infrastructure.version.repository.MetaDictionaryItemRepository;
import com.plm.infrastructure.version.repository.MetaDictionarySceneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MetaDictionaryService {

    private static final String DEFAULT_LOCALE = "zh-CN";

    private final MetaDictionaryDefRepository dictionaryDefRepository;
    private final MetaDictionaryItemRepository dictionaryItemRepository;
    private final MetaDictionarySceneRepository dictionarySceneRepository;
    private final ObjectMapper objectMapper;

    public MetaDictionaryService(MetaDictionaryDefRepository dictionaryDefRepository,
                                 MetaDictionaryItemRepository dictionaryItemRepository,
                                 MetaDictionarySceneRepository dictionarySceneRepository,
                                 ObjectMapper objectMapper) {
        this.dictionaryDefRepository = dictionaryDefRepository;
        this.dictionaryItemRepository = dictionaryItemRepository;
        this.dictionarySceneRepository = dictionarySceneRepository;
        this.objectMapper = objectMapper;
    }

    public MetaDictionaryBatchResponseDto batch(MetaDictionaryBatchRequestDto request) {
        if (request == null || request.getCodes() == null || request.getCodes().isEmpty()) {
            throw new IllegalArgumentException("codes is required");
        }

        String locale = normalizeLocale(request.getLang());
        boolean includeDisabled = Boolean.TRUE.equals(request.getIncludeDisabled());

        Set<String> uniqueCodes = new LinkedHashSet<>();
        for (String code : request.getCodes()) {
            String normalized = normalizeCode(code);
            if (normalized != null) {
                uniqueCodes.add(normalized);
            }
        }
        if (uniqueCodes.isEmpty()) {
            throw new IllegalArgumentException("codes is required");
        }

        MetaDictionaryBatchResponseDto response = new MetaDictionaryBatchResponseDto();
        List<MetaDictionaryDto> items = new ArrayList<>();
        for (String code : uniqueCodes) {
            items.add(loadSingle(code, locale, includeDisabled));
        }
        response.setItems(items);
        return response;
    }

    public MetaDictionaryDto getByCode(String code, String lang, boolean includeDisabled) {
        return loadSingle(normalizeCode(code), normalizeLocale(lang), includeDisabled);
    }

    public MetaDictionaryBatchResponseDto getByScene(String sceneCode, String lang, boolean includeDisabled) {
        String normalizedSceneCode = normalizeSceneCode(sceneCode);
        String locale = normalizeLocale(lang);

        MetaDictionaryScene scene = findSceneByLocaleWithFallback(normalizedSceneCode, locale);
        List<String> dictionaryCodes = parseDictionaryCodes(scene.getDictionaryCodes());

        MetaDictionaryBatchRequestDto request = new MetaDictionaryBatchRequestDto();
        request.setCodes(dictionaryCodes);
        request.setLang(locale);
        request.setIncludeDisabled(includeDisabled);
        return batch(request);
    }

    private MetaDictionaryDto loadSingle(String code, String locale, boolean includeDisabled) {
        MetaDictionaryDef def = findDefByLocaleWithFallback(code, locale);

        MetaDictionaryDto dto = new MetaDictionaryDto();
        dto.setCode(def.getDictCode());
        dto.setName(def.getDictName());
        dto.setVersion(def.getVersionNo());
        dto.setSource(def.getSourceType());
        dto.setLocale(def.getLocale());

        List<MetaDictionaryItem> entityItems = dictionaryItemRepository
                .findByDictionaryDefIdOrderBySort(def.getId(), includeDisabled);
        List<MetaDictionaryEntryDto> entries = new ArrayList<>(entityItems.size());
        for (MetaDictionaryItem entity : entityItems) {
            MetaDictionaryEntryDto entry = new MetaDictionaryEntryDto();
            entry.setKey(entity.getItemKey());
            entry.setValue(entity.getItemValue());
            entry.setLabel(entity.getLabel());
            entry.setOrder(entity.getSortOrder());
            entry.setEnabled(entity.getEnabled());
            entry.setExtra(parseExtra(entity.getExtraJson()));
            entries.add(entry);
        }
        dto.setEntries(entries);
        return dto;
    }

    private MetaDictionaryDef findDefByLocaleWithFallback(String code, String locale) {
        String normalizedLocale = normalizeLocale(locale);
        return dictionaryDefRepository.findActiveByDictCodeAndLocale(code, normalizedLocale)
                .or(() -> DEFAULT_LOCALE.equalsIgnoreCase(normalizedLocale)
                        ? java.util.Optional.empty()
                        : dictionaryDefRepository.findActiveByDictCodeAndLocale(code, DEFAULT_LOCALE))
                .orElseThrow(() -> new IllegalArgumentException("dictionary not found: code=" + code + ", locale=" + normalizedLocale));
    }

    private MetaDictionaryScene findSceneByLocaleWithFallback(String sceneCode, String locale) {
        String normalizedLocale = normalizeLocale(locale);
        return dictionarySceneRepository.findActiveBySceneCodeAndLocale(sceneCode, normalizedLocale)
                .or(() -> DEFAULT_LOCALE.equalsIgnoreCase(normalizedLocale)
                        ? java.util.Optional.empty()
                        : dictionarySceneRepository.findActiveBySceneCodeAndLocale(sceneCode, DEFAULT_LOCALE))
                .orElseThrow(() -> new IllegalArgumentException("dictionary scene not found: sceneCode=" + sceneCode + ", locale=" + normalizedLocale));
    }

    private Map<String, Object> parseExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(extraJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid dictionary extra_json: " + ex.getMessage(), ex);
        }
    }

    private List<String> parseDictionaryCodes(String dictionaryCodesJson) {
        if (dictionaryCodesJson == null || dictionaryCodesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(dictionaryCodesJson, new TypeReference<>() {
            });
            Set<String> codes = new LinkedHashSet<>();
            for (String code : raw) {
                String normalized = normalizeCode(code);
                if (normalized != null) {
                    codes.add(normalized);
                }
            }
            return new ArrayList<>(codes);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid dictionary scene configuration");
        }
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSceneCode(String sceneCode) {
        if (sceneCode == null || sceneCode.isBlank()) {
            throw new IllegalArgumentException("sceneCode is required");
        }
        return sceneCode.trim();
    }

    private String normalizeLocale(String lang) {
        if (lang == null || lang.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = lang.trim();
        return normalized.isEmpty() ? DEFAULT_LOCALE : normalized;
    }
}

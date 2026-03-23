package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaDictionaryService;
import com.plm.common.api.dto.dictionary.MetaDictionaryBatchRequestDto;
import com.plm.common.api.dto.dictionary.MetaDictionaryBatchResponseDto;
import com.plm.common.api.dto.dictionary.MetaDictionaryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class MetaDictionaryController {

    private final MetaDictionaryService dictionaryService;

    public MetaDictionaryController(MetaDictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @PostMapping("/dictionaries:batch")
    public MetaDictionaryBatchResponseDto batch(@RequestBody MetaDictionaryBatchRequestDto request) {
        return dictionaryService.batch(request);
    }

    @GetMapping("/dictionaries/{code}")
    public MetaDictionaryDto getByCode(
            @PathVariable("code") String code,
            @RequestParam(value = "lang", required = false) String lang,
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return dictionaryService.getByCode(code, lang, includeDisabled);
    }

    @GetMapping("/dictionary-scenes/{sceneCode}")
    public MetaDictionaryBatchResponseDto getByScene(
            @PathVariable("sceneCode") String sceneCode,
            @RequestParam(value = "lang", required = false) String lang,
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return dictionaryService.getByScene(sceneCode, lang, includeDisabled);
    }
}

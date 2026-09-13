package com.loanops.api;

import com.loanops.api.dto.ApiDtos.DataChangeResponse;
import com.loanops.api.dto.ApiDtos.PageResponse;
import com.loanops.api.dto.ApiDtos.PublicationDetailResponse;
import com.loanops.api.dto.ApiDtos.PublicationSummaryResponse;
import com.loanops.api.dto.ApiDtos.QualityIssueResponse;
import com.loanops.publication.domain.DataChangeType;
import com.loanops.publication.domain.QualityRecordType;
import com.loanops.publication.domain.QualityRuleCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operational History")
public class OperationalHistoryController {

    private final OperationalReadService readService;

    public OperationalHistoryController(OperationalReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/publications")
    @Operation(summary = "List final publication decisions")
    public PageResponse<PublicationSummaryResponse> publications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.publications(page, size);
    }

    @GetMapping("/publications/{id}")
    @Operation(summary = "Return a publication decision with its quality and change evidence")
    public PublicationDetailResponse publication(@PathVariable long id) {
        return readService.publication(id);
    }

    @GetMapping("/quality-issues")
    @Operation(summary = "List persisted quality issues")
    public PageResponse<QualityIssueResponse> qualityIssues(
            @RequestParam(required = false) Long sourceRunId,
            @RequestParam(required = false) QualityRecordType recordType,
            @RequestParam(required = false) QualityRuleCode ruleCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.qualityIssues(sourceRunId, recordType, ruleCode, page, size);
    }

    @GetMapping("/data-changes")
    @Operation(summary = "List deterministic serving-data changes")
    public PageResponse<DataChangeResponse> dataChanges(
            @RequestParam(required = false) Long sourceRunId,
            @RequestParam(required = false) QualityRecordType recordType,
            @RequestParam(required = false) DataChangeType changeType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.dataChanges(sourceRunId, recordType, changeType, page, size);
    }
}

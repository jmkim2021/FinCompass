package com.loanops.api;

import com.loanops.api.dto.ApiDtos.PageResponse;
import com.loanops.api.dto.ApiDtos.SourceRunDetailResponse;
import com.loanops.api.dto.ApiDtos.SourceRunSummaryResponse;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRunStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/source-runs")
@Tag(name = "Source Runs")
public class SourceRunController {

    private final OperationalReadService readService;

    public SourceRunController(OperationalReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    @Operation(summary = "List source executions with deterministic pagination")
    public PageResponse<SourceRunSummaryResponse> sourceRuns(
            @RequestParam(required = false) DataSourceCode source,
            @RequestParam(required = false) SourceRunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.sourceRuns(source, status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Return source execution details without raw payload content")
    public SourceRunDetailResponse sourceRun(@PathVariable long id) {
        return readService.sourceRun(id);
    }
}

package com.loanops.api;

import com.loanops.api.dto.ApiDtos.OverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Overview")
public class OverviewController {

    private final OperationalReadService readService;

    public OverviewController(OperationalReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/overview")
    @Operation(summary = "Return current LoanOps operational summary")
    public OverviewResponse overview() {
        return readService.overview();
    }
}

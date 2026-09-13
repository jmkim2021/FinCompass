package com.loanops.api;

import com.loanops.api.dto.ApiDtos.PageResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanProductDetailResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanProductResponse;
import com.loanops.api.dto.ApiDtos.ServingLoanRateObservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/serving")
@Tag(name = "Serving Data")
public class ServingController {

    private final OperationalReadService readService;

    public ServingController(OperationalReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/loan-products")
    @Operation(summary = "List current FSS serving loan products")
    public PageResponse<ServingLoanProductResponse> loanProducts(
            @RequestParam(required = false) String institution,
            @RequestParam(required = false) String productName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.servingLoanProducts(institution, productName, page, size);
    }

    @GetMapping("/loan-products/{id}")
    @Operation(summary = "Return a current FSS serving product and its current terms")
    public ServingLoanProductDetailResponse loanProduct(@PathVariable long id) {
        return readService.servingLoanProduct(id);
    }

    @GetMapping("/loan-rate-observations")
    @Operation(summary = "List current Seoul policy-linked commercial-bank loan execution statistics")
    public PageResponse<ServingLoanRateObservationResponse> loanRateObservations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return readService.servingLoanRateObservations(page, size);
    }
}

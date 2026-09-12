package com.loanops.source.service;

import com.loanops.source.domain.SourceRunErrorType;

public record SourceFailure(
        SourceRunErrorType errorType,
        String errorMessage,
        Integer httpStatus
) {
}

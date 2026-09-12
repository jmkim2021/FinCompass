package com.loanops.source.client;

public record RawSourceResponse(
        String contentType,
        String rawPayload,
        int httpStatus
) {
}

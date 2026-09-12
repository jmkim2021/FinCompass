package com.loanops.source.client;

public record SourceResponseValidation(boolean successful, String resultCode) {

    public static SourceResponseValidation success(String resultCode) {
        return new SourceResponseValidation(true, resultCode);
    }

    public static SourceResponseValidation failure(String resultCode) {
        return new SourceResponseValidation(false, resultCode);
    }
}

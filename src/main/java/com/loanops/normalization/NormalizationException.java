package com.loanops.normalization;

public class NormalizationException extends RuntimeException {

    public NormalizationException(String message) {
        super(message);
    }

    public NormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.loanops.api;

public class InvalidApiRequestException extends RuntimeException {

    public InvalidApiRequestException(String message) {
        super(message);
    }
}

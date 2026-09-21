package com.loanops.personal.canonical;

public final class PersonalFinanceException extends RuntimeException {
    public enum Code { NOT_FOUND, CONSENT_DENIED, INVALID_REQUEST, SOURCE_NOT_READY, INVALID_SOURCE_DATA, STORAGE_ERROR }
    private final Code code;
    public PersonalFinanceException(Code code) { super("Personal finance operation failed: " + code.name()); this.code=code; }
    public Code code() { return code; }
}

package com.loanops.source.service;

import com.loanops.source.domain.SourceRunErrorType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

@Component
public class SourceErrorClassifier {

    public SourceFailure classify(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return new SourceFailure(
                    SourceRunErrorType.HTTP_ERROR,
                    "External API returned HTTP status " + status,
                    status
            );
        }

        if (exception instanceof ResourceAccessException) {
            if (hasCause(exception, HttpTimeoutException.class)
                    || hasCause(exception, SocketTimeoutException.class)) {
                return new SourceFailure(
                        SourceRunErrorType.TIMEOUT,
                        "External API request timed out",
                        null
                );
            }
            return new SourceFailure(
                    SourceRunErrorType.CONNECTION_ERROR,
                    "External API connection failed",
                    null
            );
        }

        return new SourceFailure(
                SourceRunErrorType.UNKNOWN_ERROR,
                "External source request failed",
                null
        );
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

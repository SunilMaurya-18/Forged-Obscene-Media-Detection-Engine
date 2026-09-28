package com.forgeddetect.service;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A moderation rule refused the action; rendered as {@code {error: code, message, ...details}}. */
public class ModerationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ModerationException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ModerationException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}

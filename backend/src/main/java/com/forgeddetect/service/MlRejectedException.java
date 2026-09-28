package com.forgeddetect.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The ML service refused the upload itself (HTTP 4xx, e.g. a corrupt image): the caller's fault, not an outage. */
@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "The file could not be read as a supported image or video")
public class MlRejectedException extends IllegalStateException {

    public MlRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}

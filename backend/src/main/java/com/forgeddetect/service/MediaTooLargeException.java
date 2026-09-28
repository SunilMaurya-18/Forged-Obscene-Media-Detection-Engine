package com.forgeddetect.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An image over {@link MediaKinds#IMAGE_MAX_BYTES}; videos are bounded by the multipart limit instead. */
@ResponseStatus(value = HttpStatus.PAYLOAD_TOO_LARGE, reason = "Images must be at most 25 MB (videos at most 50 MB)")
public class MediaTooLargeException extends RuntimeException {

    public MediaTooLargeException(String message) {
        super(message);
    }
}

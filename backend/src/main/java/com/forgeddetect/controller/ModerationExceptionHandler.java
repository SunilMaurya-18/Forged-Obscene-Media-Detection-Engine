package com.forgeddetect.controller;

import com.forgeddetect.service.MediaTooLargeException;
import com.forgeddetect.service.ModerationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ModerationExceptionHandler {

    @ExceptionHandler(ModerationException.class)
    public ResponseEntity<Map<String, Object>> handle(ModerationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.code());
        body.put("message", ex.getMessage());
        body.putAll(ex.details());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler({MediaTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, Object>> tooLarge(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "too_large");
        body.put("message", "Images must be at most 25 MB and videos at most 50 MB.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }
}

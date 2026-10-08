package com.example.ratelimiter.exception;

import com.example.ratelimiter.controller.RateLimitHeaders;
import com.example.ratelimiter.model.RateLimitResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<RateLimitResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        RateLimitResponse response = ex.getResponse();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(RateLimitHeaders.from(response))
                .body(response);
    }

    @ExceptionHandler({InvalidUserIdException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}

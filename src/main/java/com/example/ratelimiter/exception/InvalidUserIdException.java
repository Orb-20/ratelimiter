package com.example.ratelimiter.exception;

/** Thrown when the X-User-Id header does not hold a usable value; mapped to HTTP 400. */
public class InvalidUserIdException extends RuntimeException {

    public InvalidUserIdException(String message) {
        super(message);
    }
}

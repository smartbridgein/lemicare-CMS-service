package com.lemicare.cms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A custom exception thrown when a specific resource (e.g., a document in Firestore)
 * cannot be found.
 *
 * This is an "unchecked" exception (it extends RuntimeException) and is designed
 * to be handled by a global exception handler to produce an HTTP 404 Not Found response.
 */
@ResponseStatus(HttpStatus.NOT_FOUND) // Provides a default HTTP status if not handled by a @ExceptionHandler
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Constructs a new ResourceNotFoundException with the specified detail message.
     *
     * @param message The detail message (e.g., "Supplier with ID 123 not found.").
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Constructs a new ResourceNotFoundException with the specified detail message and cause.
     *
     * @param message The detail message.
     * @param cause The original exception that caused this one.
     */
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

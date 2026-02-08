package com.lemicare.cms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A custom exception thrown when an internal, service-to-service communication
 * call fails.
 * <p>
 * This typically wraps exceptions like RestClientException or other network-related
 * errors, indicating that a downstream service is unavailable or returned an
 * unexpected error. This should result in an HTTP 5xx server error status.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) // A 503 is often more appropriate than a 500
public class ServiceCommunicationException extends RuntimeException {

    /**
     * Constructs a new ServiceCommunicationException with the specified detail message.
     *
     * @param message The detail message (e.g., "Inventory service is currently unavailable.").
     */
    public ServiceCommunicationException(String message) {
        super(message);
    }

    /**
     * Constructs a new ServiceCommunicationException with a message and the original cause.
     * This is the most common constructor, used to wrap the underlying network exception.
     *
     * @param message The detail message.
     * @param cause The original exception (e.g., RestClientException, SocketTimeoutException).
     */
    public ServiceCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}

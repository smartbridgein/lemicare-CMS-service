package com.lemicare.cms.Exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ServiceCommunicationException.class)
    public ResponseEntity<String> handleServiceCommunicationException(ServiceCommunicationException ex) {
        // In a production environment, you must log the original cause of this error.
        // ex.getCause() will contain the underlying RestClientException, which is crucial for debugging.
        // log.error("Downstream service communication failed: ", ex.getCause());

        // Return a user-friendly message, hiding the internal details.
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
    }
}

package com.lemicare.cms.exception;

import com.lemicare.cms.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        log.warn("Resource not found: {}", ex.getMessage());

        return buildError(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(ServiceCommunicationException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceCommunication(
            ServiceCommunicationException ex,
            HttpServletRequest request) {

        log.error("Downstream service failure", ex.getCause());

        return buildError(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                ex.getMessage(),
                request
        );
    }

    @ExceptionHandler(InventoryClientException.class)
    public ResponseEntity<ApiErrorResponse> handleInventoryConflict(
            InventoryClientException ex,
            HttpServletRequest request) {

        log.warn("Inventory conflict: {}", ex.getMessage());

        return buildError(
                HttpStatus.CONFLICT,
                "Inventory Conflict",
                ex.getMessage() != null ? ex.getMessage() : "Insufficient stock",
                request
        );
    }

    /**
     * Catch-all (VERY important in prod)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception", ex);

        return buildError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "Something went wrong. Please try again later.",
                request
        );
    }

    private ResponseEntity<ApiErrorResponse> buildError(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request) {

        ApiErrorResponse response = ApiErrorResponse.builder()
                .status(status.value())
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .build();

        return ResponseEntity.status(status).body(response);
    }
}

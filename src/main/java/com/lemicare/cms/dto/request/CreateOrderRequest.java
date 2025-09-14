package com.lemicare.cms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

/**
 * A Data Transfer Object (DTO) for requesting the creation of a new payment order.
 * This is used for internal, service-to-service communication.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CreateOrderRequest {

    /**
     * The amount to be charged, in the primary currency unit (e.g., 1500.00 for INR).
     * Must be a positive value.
     */
    @NotNull(message = "Amount is required.")
    @Positive(message = "Amount must be greater than zero.")
    private Double amount;

    /**
     * The unique identifier of the invoice or cart from the originating service
     * (e.g., the 'opdInvoiceId' from the opd-service). This is crucial for
     * linking the payment back to the correct record.
     */
    @NotBlank(message = "Source Invoice ID is required.")
    private String sourceInvoiceId;

    /**
     * A string identifying the originating service (e.g., "OPD", "INVENTORY").
     * This helps the payment service know where to publish success/failure events.
     */
    @NotBlank(message = "Source Service name is required.")
    private String sourceService;

    /**
     * The three-letter ISO currency code. For now, this will likely be "INR".
     */
    @NotBlank(message = "Currency code is required.")
    private String currency;
}


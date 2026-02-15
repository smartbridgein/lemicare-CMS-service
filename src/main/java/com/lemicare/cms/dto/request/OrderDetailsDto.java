package com.lemicare.cms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Data Transfer Object (DTO) that provides a detailed, read-only representation of an order.
 *
 * This class defines the internal API contract for data fetched from the order-service.
 * It is designed to be a comprehensive data source for any downstream service, like the
 * delivery-service, that needs to perform actions based on a completed order.
 *
 * The @JsonIgnoreProperties(ignoreUnknown = true) annotation ensures that if the order-service
 * adds new fields to its response in the future, this client will not break.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderDetailsDto {

    /** The unique identifier of the order. */
    private String orderId;

    /** The full name of the customer placing the order. */
    private String customerName;

    /** The email address of the customer. */
    private String customerEmail;

    /** The payment method used for the order (e.g., "Prepaid", "COD"). */
    private String paymentMethod;

    private String customerPhone;

    /** The total monetary value of all items in the order, before shipping or discounts. */
    private Integer totalOrderValue;

    // --- Structured Billing/Shipping Address ---
    // Assuming shipping and billing addresses are the same for simplicity.
    private String billingAddressLine1;
    private String billingAddressLine2;
    private String billingCity;
    private String billingPincode;
    private String billingState;

    // --- Calculated Package Details ---
    /** The total weight of the package in kilograms (kgs), calculated by the order-service. */
    private BigDecimal totalWeightKg;
    /** The total length of the package in centimeters (cms). */
    private BigDecimal  packageLengthCm;
    /** The total breadth of the package in centimeters (cms). */
    private BigDecimal  packageBreadthCm;
    /** The total height of the package in centimeters (cms). */
    private BigDecimal  packageHeightCm;

    /** A list of all line items included in the order. */
    private List<OrderItemDto> items;

    /**
     * Nested static class representing a single line item within the order.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemDto {

        /** The user-friendly name of the product. */
        private String name;

        /** The unique Stock Keeping Unit (SKU) for the product. */
        private String sku;

        /** The number of units of this product in the order. */
        private Integer quantity;

        /** The price per single unit. */
        private Double unitPrice;

        /** The Harmonized System of Nomenclature (HSN) code for tax purposes. */
        private Integer hsnCode;
    }
}

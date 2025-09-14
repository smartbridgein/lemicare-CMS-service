package com.lemicare.cms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateCheckoutRequest {

    // Customer Information
    private String patientId; // Optional: if logged in patient
    @NotNull(message = "Customer information is required")
    private Map<String, String> customerInfo; // { "name", "email", "phone" }

    @NotNull(message = "Shipping address is required")
    private Map<String, String> shippingAddress; // { "street", "city", "state", "zip", "country" }

    // Items in Cart
    @NotEmpty(message = "Cart cannot be empty")
    @Valid // This ensures validation is applied to each item in the list
    private List<CartItemDto> cartItems;
}

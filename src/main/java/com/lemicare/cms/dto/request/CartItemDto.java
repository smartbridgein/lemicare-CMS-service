package com.lemicare.cms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemDto {
    @NotBlank(message = "Product ID is required for a cart item")
    private String productId;

    @NotBlank(message = "Product name is required for a cart item")
    private String productName;

    // SKU is optional, if not always present or relevant for initial checkout
    private String sku;

    //@Positive(message = "Quantity must be positive")
    private int quantity;

    @Positive(message = "MRP per item must be positive")
    private double mrpPerItem;

   // @PositiveOrZero(message = "Discount percentage cannot be negative")
    private double discountPercentage; // Discount % applied to this item



    // The frontend should also send tax related info if it has it, or backend calculates it.
    // For simplicity, we'll assume the backend calculates full tax details.
    // private String taxProfileId;
    // private GstType gstType;
    // private double taxRateApplied;
}

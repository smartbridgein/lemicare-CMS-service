package com.lemicare.cms.dto.request;

import com.google.cloud.Timestamp;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaleItemDto {
    @NotBlank(message = "Product ID is required for a cart item")
    private String medicineId;

    private String productName;
    @NotNull(message = "Quantity is required and cannot be null") // Added
    @Min(value = 1, message = "Quantity must be at least 1")    // Added
    private Integer quantity;
    private Double discountPercentage;
    private String batchNumber;
    private Timestamp expiryDate;

    // The client sends the Unit MRP it used for calculation
    @Positive(message = "MRP per item must be positive")
    private Double mrp;

    private String taxProfileId;
    private String sku;

}

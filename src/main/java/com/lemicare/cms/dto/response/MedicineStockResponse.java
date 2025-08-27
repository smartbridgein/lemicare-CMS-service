package com.lemicare.cms.dto.response;

import com.cosmicdoc.common.model.Medicine;
import lombok.Builder;
import lombok.Data;

/**
 * A Data Transfer Object (DTO) representing the stock level and status of a medicine.
 * This is a simplified view of the Medicine model, designed to be sent to clients.
 */
@Data
@Builder
public class MedicineStockResponse {

    private String medicineId;
    private String name;
    private String sku;
    private int quantityInStock;
    private String stockStatus; // e.g., "In Stock", "Low Stock", "Out of Stock"
    private String genericName;
    private String category;
    private String manufacturer;
    private String hsnCode;
    private String location;
    private String unitOfMeasurement;
    private Integer lowStockThreshold;
    private String taxProfileId;
    private Double unitPrice;
    private String status;

    /**
     * A static factory method to create this DTO from a Medicine domain model
     * and a calculated total stock count.
     *
     * @param medicine The master Medicine object.
     * @param totalStock The real-time stock quantity calculated by summing all available batches.
     * @return A populated MedicineStockResponse DTO.
     */
    public static MedicineStockResponse from(Medicine medicine) {
        // --- THIS IS THE CORRECTED LOGIC ---

        // 1. Determine the stock status based on the calculated totalStock.
        int totalStock = medicine.getQuantityInStock();
        String currentStatus;
        if (totalStock <= 0) {
            currentStatus = "Out of Stock";
        } else if (totalStock <= medicine.getLowStockThreshold()) {
            currentStatus = "Low Stock";
        } else {
            currentStatus = "In Stock";
        }

        // 2. Build the response DTO using the calculated values.
        return MedicineStockResponse.builder()

                .medicineId(medicine.getMedicineId())
                .name(medicine.getName())
                .genericName(medicine.getGenericName())
                .category(medicine.getCategory())
                .manufacturer(medicine.getManufacturer())
                .sku(medicine.getSku())
                .hsnCode(medicine.getHsnCode())
                .location(medicine.getLocation())
                .unitOfMeasurement(medicine.getUnitOfMeasurement())
                .lowStockThreshold(medicine.getLowStockThreshold())
                .taxProfileId(medicine.getTaxProfileId())
                .unitPrice(medicine.getUnitPrice())
                .status(medicine.getStatus())
                .stockStatus(currentStatus)
                .quantityInStock(totalStock)
                .build();
    }
}
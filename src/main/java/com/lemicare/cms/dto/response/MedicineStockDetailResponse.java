package com.lemicare.cms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class MedicineStockDetailResponse {
    // Master Details
    private String medicineId;
    private String name;
    private String genericName;
    private String manufacturer;
    private int totalStock; // The sum of all batch quantities

    // The list of all batches for this medicine
    private List<BatchDetailDto> batches;

    @Data
    @Builder
    public static class BatchDetailDto {
        private String batchId;
        private String batchNo;
        private int quantityAvailable;
        private double mrp;
        private Date expiryDate;
    }
}

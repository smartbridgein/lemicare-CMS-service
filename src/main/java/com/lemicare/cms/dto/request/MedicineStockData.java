package com.lemicare.cms.dto.request;

import lombok.Data;
// This DTO mirrors the MedicineStockResponse to capture stock data.
@Data
public class MedicineStockData {
    private String medicineId;
    private int availableStock;
    private String stockStatus;
}

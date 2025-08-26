package com.lemicare.cms.dto.request;

import lombok.Data;

@Data
public class MedicineMasterData {
    private String medicineId;
    private String name;
    private String genericName;
    private String manufacturer;
    private Double unitPrice; // MRP
}

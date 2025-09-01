package com.lemicare.cms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor // Generates a constructor with all fields
@NoArgsConstructor // Generates a no-argument constructor, often useful for deserialization
public class MedicineStockRequest {
    private List<String> medicineIds;
}

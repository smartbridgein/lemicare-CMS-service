package com.lemicare.cms.integration.client;

import com.cosmicdoc.common.model.Sale;
import com.lemicare.cms.config.FeignConfig;
import com.lemicare.cms.dto.request.CreateSaleRequest;
import com.lemicare.cms.dto.response.MedicineStockDetailResponse;
import com.lemicare.cms.dto.response.MedicineStockResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "inventory-service",
        url = "${services.inventory.url}",
        configuration = FeignConfig.class
)
public interface InventoryService {
    @GetMapping ("/api/public/inventory/medicines/{medicineId}")
    MedicineStockResponse getMedicineDetails(@PathVariable String medicineId);

    @PostMapping("/api/public/inventory/sale")
    Sale createSale (@RequestBody CreateSaleRequest saleRequest);

    @GetMapping("/api/public/inventory/medicines/{medicineId}/stock-details")
    MedicineStockDetailResponse getPublicMedicineDetails(@PathVariable String medicineId);


}

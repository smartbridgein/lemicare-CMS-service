package com.lemicare.cms.controller;

import com.cosmicdoc.common.model.StockLevelChangedEvent;
import com.lemicare.cms.service.StorefrontService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal") // Internal path prefix
@RequiredArgsConstructor
@Hidden
public class InternalCmsController {
    private final StorefrontService storefrontService;

    @PostMapping("/stock-updates")
   // @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')") // Example: Specific role for Inventory Service
    public ResponseEntity<Void> handleStockChangeNotification(@RequestBody StockLevelChangedEvent event) {
        // Validate event, log, then delegate to service
        storefrontService.updateProductStockLevel(event.getOrganizationId(),event.getBranchId(), event.getMedicineId(), event.getNewTotalStock(),event.getMedicineName(),event.getMrp(),event.getTaxprofileId(),event.getGstType(),event.getCategory());

        return ResponseEntity.ok().build();
    }
}

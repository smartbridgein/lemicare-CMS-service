package com.lemicare.cms.controller;

import com.cosmicdoc.common.model.StockLevelChangedEvent;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.OrderDetailsDto;
import com.lemicare.cms.service.StorefrontService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping("/storefront/{orgId}/product/{productId}/details")
    StorefrontProduct getProductDetails(@PathVariable("orgId") String orgId, @PathVariable("productId") String productId) {
        return  storefrontService.getProductById(orgId,productId);
    }

    @GetMapping("{orgId}/orders/{orderId}")
    OrderDetailsDto getOrderDetails(@PathVariable("orgId") String orgId,@PathVariable("orderId") String orderId) {
        return storefrontService.getOrderDetails(orgId,orderId);
    }

    @GetMapping("/storefront/{organizationId}/products")
    public ResponseEntity<List<StorefrontProduct>> getProductsByIds(
            @PathVariable String organizationId,
            @RequestParam List<String> productIds) {
        // You might need a specific method in your repository for batch fetch,
        // or iterate if your Firestore setup allows efficient batch reads by ID.
        // For Firestore, this would typically involve a loop of `findById` or
        // a `whereIn` query if productIds is <= 10.
        List<StorefrontProduct> products = storefrontService.productByIds(organizationId,productIds);

        return ResponseEntity.ok(products);
    }
}

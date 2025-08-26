package com.lemicare.cms.controller;

import com.cosmicdoc.common.model.StorefrontCategory;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.lemicare.cms.dto.request.CategoryRequestDto;
import com.lemicare.cms.dto.request.ProductEnrichmentRequestDto;
import com.lemicare.cms.security.SecurityUtils;
import com.lemicare.cms.service.StorefrontService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/storefront") // Admin-specific path
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
public class AdminContentController {

    private final StorefrontService storefrontService;

    // --- Category Endpoints ---
    @PostMapping("/categories")
    public ResponseEntity<StorefrontCategory> createCategory(@Valid @RequestBody CategoryRequestDto request) {
        String orgId = SecurityUtils.getOrganizationId();
        StorefrontCategory newCategory = storefrontService.createCategory(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(newCategory);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<StorefrontCategory>> getCategories() {
        String orgId = SecurityUtils.getOrganizationId();
        return ResponseEntity.ok(storefrontService.getCategories(orgId));
    }

    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<StorefrontCategory> updateCategory(@PathVariable String categoryId, @Valid @RequestBody CategoryRequestDto request) {
        String orgId = SecurityUtils.getOrganizationId();
        return ResponseEntity.ok(storefrontService.updateCategory(orgId, categoryId, request));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String categoryId) {
        String orgId = SecurityUtils.getOrganizationId();
        storefrontService.deleteCategory(orgId, categoryId);
        return ResponseEntity.noContent().build();
    }


    // --- Product Enrichment Endpoint ---
    @PutMapping("/products/{productId}")
    public ResponseEntity<StorefrontProduct> enrichProduct(
            @PathVariable String productId,
            @Valid @RequestBody ProductEnrichmentRequestDto request) {

        String orgId = SecurityUtils.getOrganizationId();
        String branchId = SecurityUtils.getBranchId(); // The admin is enriching a product for their specific branch

        StorefrontProduct enrichedProduct = storefrontService.enrichProduct(orgId, branchId, productId, request);
        return ResponseEntity.ok(enrichedProduct);
    }
}

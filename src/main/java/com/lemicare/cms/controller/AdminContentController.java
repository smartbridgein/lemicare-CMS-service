package com.lemicare.cms.controller;

import com.cosmicdoc.common.model.StorefrontCategory;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemicare.cms.dto.request.CategoryRequestDto;
import com.lemicare.cms.dto.request.ProductEnrichmentRequestDto;
import com.lemicare.cms.dto.response.PaginatedResponse;
import com.lemicare.cms.dto.response.PublicProductListResponse;
import com.lemicare.cms.security.SecurityUtils;
import com.lemicare.cms.service.StorefrontService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/admin/storefront") // Admin-specific path
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')")
public class AdminContentController {
    private final ObjectMapper objectMapper;
    private final StorefrontService storefrontService;

    // --- Category Endpoints ---
  /*  @PostMapping("/categories")
    public ResponseEntity<StorefrontCategory> createCategory(@Valid @RequestBody CategoryRequestDto request) {
        String orgId = SecurityUtils.getOrganizationId();
        StorefrontCategory newCategory = storefrontService.createCategory(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(newCategory);
    }*/

   /* @GetMapping("/categories")
    public ResponseEntity<List<StorefrontCategory>> getCategories() {
        String orgId = SecurityUtils.getOrganizationId();
        return ResponseEntity.ok(storefrontService.getCategories(orgId));
    }*/

  /*  @PutMapping("/categories/{categoryId}")
    public ResponseEntity<StorefrontCategory> updateCategory(@PathVariable String categoryId, @Valid @RequestBody CategoryRequestDto request) {
        String orgId = SecurityUtils.getOrganizationId();
        return ResponseEntity.ok(storefrontService.updateCategory(orgId, categoryId, request));
    }*/

   /* @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String categoryId) {
        String orgId = SecurityUtils.getOrganizationId();
        storefrontService.deleteCategory(orgId, categoryId);
        return ResponseEntity.noContent().build();
    }*/

    @GetMapping("/products")
    public ResponseEntity<List<StorefrontProduct>> products()

    {
        String orgId = SecurityUtils.getOrganizationId();

        List<StorefrontProduct> products = storefrontService.getAvailableProducts(orgId);
        return ResponseEntity.ok(products);
    }


    /**
     * Unified endpoint to enrich product metadata and manage product images.
     * This endpoint expects a `multipart/form-data` request.
     * The `requestJson` part contains the ProductEnrichmentRequestDto as JSON.
     * The `imageFiles` parts contain the actual image files.
     *
     * @param productId The ID of the product to update.
     * @param requestJson A JSON string representing the ProductEnrichmentRequestDto.
     * @param imageFiles An array of MultipartFiles for new/updated images. Can be empty.
     * @return The updated StorefrontProduct.
     * @throws IOException If JSON parsing or file I/O fails.
     * @throws ExecutionException If Firestore operation fails.
     * @throws InterruptedException If Firestore operation is interrupted.
     */
    @PutMapping(value = "/products/{productId}", consumes = "multipart/form-data")
    public ResponseEntity<StorefrontProduct> updateProduct(
            @PathVariable String productId,
            @RequestParam("requestJson") String requestJson, // JSON data as a String parameter
            @RequestParam(value = "imageFiles", required = false) MultipartFile[] imageFiles) // Array of files
            throws IOException, ExecutionException, InterruptedException {

        String orgId = SecurityUtils.getOrganizationId();
        String branchId = SecurityUtils.getBranchId();

        // 1. Parse the JSON string into the DTO
        ProductEnrichmentRequestDto request = objectMapper.readValue(requestJson, ProductEnrichmentRequestDto.class);

        // 2. Call the service to handle both metadata and images
        StorefrontProduct updatedProduct = storefrontService.updateProduct(
                orgId, branchId, productId, request, imageFiles != null ? imageFiles : new MultipartFile[0]
        );
        return ResponseEntity.ok(updatedProduct);
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



    @PostMapping("/products/{productId}/images")
    public ResponseEntity<StorefrontProduct> uploadProductImage(
            @PathVariable String productId,
            @RequestParam("imageFile") MultipartFile imageFile, // Using @RequestParam for MultipartFile
            @RequestParam(value = "altText", required = false) String altText,
            @RequestParam(value = "displayOrder", defaultValue = "0") int displayOrder) throws IOException, ExecutionException, InterruptedException {

        String orgId = SecurityUtils.getOrganizationId();
        String branchId = SecurityUtils.getBranchId();

        // Pass individual parameters, or wrap them in a DTO if you prefer.
        // For simple file upload, individual @RequestParam is often cleaner.
        StorefrontProduct updatedProduct = storefrontService.uploadProductImage(
                orgId, productId, imageFile, altText, displayOrder
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(updatedProduct);
    }

    @DeleteMapping("/products/{productId}/images/{assetId}")
    public ResponseEntity<StorefrontProduct> deleteProductImage(
            @PathVariable String productId,
            @PathVariable String assetId) throws ExecutionException, InterruptedException {

        String orgId = SecurityUtils.getOrganizationId();
        String branchId = SecurityUtils.getBranchId();
        StorefrontProduct updatedProduct = storefrontService.deleteProductImage(orgId, productId, assetId);
        return ResponseEntity.ok(updatedProduct);
    }
}


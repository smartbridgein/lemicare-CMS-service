package com.lemicare.cms.controller;

import com.cosmicdoc.common.model.StorefrontCategory;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.response.PaginatedResponse;
import com.lemicare.cms.dto.response.PublicProductDetailResponse;
import com.lemicare.cms.dto.response.PublicProductListResponse;
import com.lemicare.cms.service.StorefrontService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for handling all PUBLIC-FACING API requests for the e-commerce storefront.
 * These endpoints are not secured by JWT and are intended to be consumed by the
 * customer's web browser.
 */
@RestController
@RequestMapping("/api/public/storefront") // Public, unsecured path prefix
@RequiredArgsConstructor
@Tag(name = "Public Storefront API", description = "Endpoints consumed by the public e-commerce website.")
public class PublicStorefrontController {

    private final StorefrontService storefrontService;

    /**
     * Fetches the complete, combined details for a single product to display on a product page.
     * This is an orchestrator endpoint that gathers data from both the CMS and Inventory services.
     *
     * @param orgId The ID of the organization (tenant) whose store is being viewed.
     * @param productId The ID of the product to display.
     * @return A ResponseEntity containing the rich product details or a 404 Not Found error.
     */
    @Operation(
            summary = "Get Public Product Details",
            description = "Fetches all necessary information for a product page, combining CMS content with live inventory data."
    )
    @GetMapping("/{orgId}/products/{productId}")
    public ResponseEntity<?> getPublicProductDetails(
            @Parameter(description = "The unique ID of the organization's store") @PathVariable String orgId,
            @Parameter(description = "The unique ID of the product") @PathVariable String productId) {

        try {
            // Delegate the orchestration logic to the service layer.
            PublicProductDetailResponse productDetails = storefrontService.getPublicProductDetails(orgId, productId);
            return ResponseEntity.ok(productDetails);
        } catch (ResourceNotFoundException e) {
            // If the service throws this, it means the product is not found or not visible.
            // Return a standard 404 Not Found.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            // Catch any other unexpected errors from downstream services.
            // In production, you would log the full 'e' stack trace.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An error occurred while fetching product details.");
        }
    }

    /**
     * Fetches a paginated and filterable list of all visible products for a store.
     */
    @GetMapping("/{orgId}/products")
    public ResponseEntity<PaginatedResponse<PublicProductListResponse>> listPublicProducts(
            @PathVariable String orgId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String startAfter // For Firestore cursor pagination
    ) {
        PaginatedResponse<PublicProductListResponse> products = storefrontService.listPublicProducts(orgId, categoryId, page, size, startAfter);
        return ResponseEntity.ok(products);
    }

    /**
     * Fetches the list of all categories for a store's navigation.
     */
    @GetMapping("/{orgId}/categories")
    public ResponseEntity<List<StorefrontCategory>> getPublicCategories(@PathVariable String orgId) {
        List<StorefrontCategory> categories =(storefrontService.getCategories(orgId));
        return ResponseEntity.ok(categories);
    }
}


    // You would add other public endpoints here, for example:
    // @GetMapping("/{orgId}/products") for listing all products
    // @GetMapping("/{orgId}/categories") for listing all categories


package com.lemicare.cms.controller;

import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.response.PublicProductDetailResponse;
import com.lemicare.cms.service.StorefrontService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    // You would add other public endpoints here, for example:
    // @GetMapping("/{orgId}/products") for listing all products
    // @GetMapping("/{orgId}/categories") for listing all categories
}

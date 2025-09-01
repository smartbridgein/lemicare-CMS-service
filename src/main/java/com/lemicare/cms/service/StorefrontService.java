package com.lemicare.cms.service;

import com.cosmicdoc.common.model.Medicine;
import com.cosmicdoc.common.model.StorefrontCategory;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.cosmicdoc.common.repository.StorefrontCategoryRepository;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.util.FirestorePage;
import com.cosmicdoc.common.util.IdGenerator;
import com.google.api.gax.paging.Page;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.CategoryRequestDto;
import com.lemicare.cms.dto.request.MedicineStockRequest;
import com.lemicare.cms.dto.request.ProductEnrichmentRequestDto;
import com.lemicare.cms.dto.response.*;
import com.lemicare.cms.integration.client.InventoryServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorefrontService {

    private final StorefrontProductRepository storefrontProductRepository;
    private final StorefrontCategoryRepository storefrontCategoryRepository;
    private final InventoryServiceClient inventoryServiceClient;

    // --- Category Management ---

    public StorefrontCategory createCategory(String orgId, CategoryRequestDto dto) {
        String categoryId = IdGenerator.newId("cat");
        // Create a URL-friendly "slug" from the name
        String slug = dto.getName().toLowerCase().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", "");

        StorefrontCategory category = StorefrontCategory.builder()
                .categoryId(categoryId)
                .organizationId(orgId)
                .name(dto.getName())
                .slug(slug)
                .description(dto.getDescription())
                .imageUrl(dto.getImageUrl())
                .parentCategoryId(dto.getParentCategoryId())
                .build();

        return storefrontCategoryRepository.save(category);
    }

    public StorefrontCategory updateCategory(String orgId, String categoryId, CategoryRequestDto dto) {
        StorefrontCategory existingCategory = storefrontCategoryRepository.findById(orgId, categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category with ID " + categoryId + " not found."));

        // Update fields from DTO
        existingCategory.setName(dto.getName());
        existingCategory.setSlug(dto.getName().toLowerCase().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", ""));
        existingCategory.setDescription(dto.getDescription());
        existingCategory.setImageUrl(dto.getImageUrl());
        existingCategory.setParentCategoryId(dto.getParentCategoryId());

        return storefrontCategoryRepository.save(existingCategory);
    }

    public List<StorefrontCategory> getCategories(String orgId) {
        return storefrontCategoryRepository.findAllByOrganization(orgId);
    }

    public void deleteCategory(String orgId, String categoryId) {
        // TODO: Add logic to check if any products are using this category before deleting.
        storefrontCategoryRepository.deleteById(orgId, categoryId);
    }

    // --- Product Enrichment ---

    public StorefrontProduct enrichProduct(String orgId, String branchId, String productId, ProductEnrichmentRequestDto dto) {
        // Find existing enrichment data or create a new one
        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseGet(() -> StorefrontProduct.builder()
                        .productId(productId)
                        .organizationId(orgId)
                        .branchId(branchId)
                        .build());

        // Update the fields with the new enrichment data
        product.setVisible(dto.getIsVisible());
        product.setRichDescription(dto.getRichDescription());
        product.setCategoryId(dto.getCategoryId());
        product.setTags(dto.getTags());
        // A URL-friendly slug should also be generated and stored here

        return storefrontProductRepository.save(product);
    }

    /**
     * Fetches and combines product data from the CMS and Inventory services.
     * This is a public-facing method used by the e-commerce website.
     *
     * @param orgId     The ID of the organization's store being viewed.
     * @param productId The ID of the product to fetch.
     * @return A rich, combined DTO for the product page.
     */
    public PublicProductDetailResponse getPublicProductDetails(String orgId, String productId) {

        // ===================================================================
        // Step A: (Internal Read) Get the presentation data from our own database.
        // ===================================================================

        StorefrontProduct storefrontProduct = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));

        String sourceBranchId = storefrontProduct.getBranchId();
        if (sourceBranchId == null) {
            // Product has not been fully configured for sale
            throw new ResourceNotFoundException("Product not available for online sale.");
        }

        // If the product is not marked as visible, treat it as not found.
        if (!storefrontProduct.isVisible()) {
            throw new ResourceNotFoundException("Product not found.");
        }

        // ===================================================================
        // Step B & C: (Internal API Call) Call the inventory-service via Feign.
        // ===================================================================
        // The Feign client will automatically handle authentication.
        MedicineStockResponse inventoryData = inventoryServiceClient.getMedicineDetails(productId);

        // Also fetch the category name for display
        String categoryName = storefrontCategoryRepository.findById(orgId, storefrontProduct.getCategoryId())
                .map(StorefrontCategory::getName)
                .orElse("Uncategorized");

        // ===================================================================
        // Step D: Combine and Respond
        // ===================================================================
        return PublicProductDetailResponse.builder()
                // Data from Inventory Service
                .productId(inventoryData.getMedicineId())
                .name(inventoryData.getName())
                .genericName(inventoryData.getGenericName())
                .manufacturer(inventoryData.getManufacturer())

                .availableStock(inventoryData.getQuantityInStock()) // Assuming this is on the detail response
                .mrp(inventoryData.getUnitPrice())
                // Data from Storefront (CMS) Service
                .richDescription(storefrontProduct.getRichDescription())
                .images(storefrontProduct.getImages())
                .categoryName(categoryName)
                .build();
    }


    public PaginatedResponse<PublicProductListResponse> listPublicProducts(
            String orgId, String categoryId, int page, int size, String startAfter) {

        // 1. Get a paginated list of visible storefront products from our DB.
        // It's crucial to cast to your custom implementation (FirestorePage)
        // to access getTotalElements(), getTotalPages(), isLast().
        FirestorePage<StorefrontProduct> productPage =
                (FirestorePage<StorefrontProduct>) storefrontProductRepository.findAllVisible(orgId, categoryId, size, startAfter);

        List<StorefrontProduct> cmsProducts = productPage.getValues(); // Use getValues()

        // Important: Your current logic for handling empty `cmsProducts`
        // already includes passing `productPage.getTotalElements()`, etc.
        // This is good, as even an empty page might have a total count > 0 if
        // it's an intermediate page in a large result set, or 0 if truly empty.
        if (cmsProducts.isEmpty() && productPage.getTotalElements() == 0) { // Added check for totalElements for clarity
            return new PaginatedResponse<>(
                    Collections.emptyList(),
                    page,
                    size,
                    0L, // If no products and total elements is 0, explicitly pass 0
                    0,  // If no products and total elements is 0, explicitly pass 0
                    true // If no products, it's the last page
            );
        }

        // 2. Collect the IDs to fetch from the inventory service.
        List<String> medicineIds = cmsProducts.stream()
                .map(StorefrontProduct::getProductId)
                .collect(Collectors.toList());

        // 3. Make a single, efficient batch API call to the inventory service.
        MedicineStockRequest stockRequest = new MedicineStockRequest(medicineIds);
        List<MedicineStockResponse> inventoryData = inventoryServiceClient.getStockLevelsForMedicines(stockRequest);

        // Create a lookup map for easy access
        Map<String, MedicineStockResponse> inventoryMap = inventoryData.stream()
                .collect(Collectors.toMap(MedicineStockResponse::getMedicineId, Function.identity()));

        // 4. Combine the two data sources into the final response DTO.
        List<PublicProductListResponse> responseList = cmsProducts.stream().map(cmsProduct -> {
            MedicineStockResponse stockInfo = inventoryMap.get(cmsProduct.getProductId());
            if (stockInfo == null) {
                // If a product from CMS is not found in inventory, decide how to handle.
                // Current code returns null, which then gets filtered out.
                // This correctly ensures only products with inventory data are shown.
                return null;
            }

            return PublicProductListResponse.builder()
                    .productId(cmsProduct.getProductId())
                    .name(stockInfo.getName()) // Name from inventory
                    .mainImageUrl(cmsProduct.getImages().isEmpty() ? null : cmsProduct.getImages().get(0).getOriginalUrl())
                    .mrp(stockInfo.getUnitPrice()) // MRP from inventory
                    .stockStatus(stockInfo.getStockStatus()) // Stock status from inventory
                    .build();
        }).filter(Objects::nonNull).collect(Collectors.toList());

        // Return the PaginatedResponse with all the data
        return new PaginatedResponse<>(
                responseList,
                page, // Assuming 'page' passed in is the current page number
                size,
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast()
        );
    }

    public void updateProductStockLevel(String orgId, String productId, int newStockLevel) {
        /*storefrontProductRepository.findById(orgId, productId)
                .ifPresent(product -> {
                    // Update only if the new stock level is different or you want to force an update
                    // You might need to update a specific stock field in StorefrontProduct
                    // For now, let's assume we store the stock status directly in StorefrontProduct
                    // or a simplified stock quantity if the CMS needs it for UI logic.
                    // For simplicity, let's assume `stockStatus` is the primary field to update.


                    // Example:
                     product.setQuantityInStock(newStockLevel); // If CMS stores quantity

                    // Or, if CMS only stores `stockStatus`:
                    String currentStatus;
                    // You'd need to fetch the lowStockThreshold from somewhere if CMS doesn't have it
                    // For simplicity, let's just derive it or fetch it if needed.
                    if (newStockLevel <= 0) {
                        currentStatus = "Out of Stock";
                    } else if (newStockLevel <= product.) { // Assuming product has this property
                        currentStatus = "Low Stock";
                    } else {
                        currentStatus = "In Stock";
                    }
                    product.setStockStatus(currentStatus); // Assuming StorefrontProduct has setStockStatus

                    storefrontProductRepository.save(product); // Save the updated product
                });
    }*/
    }
}
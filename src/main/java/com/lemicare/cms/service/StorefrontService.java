package com.lemicare.cms.service;

import com.cosmicdoc.common.model.Medicine;
import com.cosmicdoc.common.model.StorefrontCategory;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.cosmicdoc.common.repository.StorefrontCategoryRepository;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.util.IdGenerator;

import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.CategoryRequestDto;
import com.lemicare.cms.dto.request.ProductEnrichmentRequestDto;
import com.lemicare.cms.dto.response.MedicineStockDetailResponse;
import com.lemicare.cms.dto.response.PublicProductDetailResponse;
import com.lemicare.cms.integration.client.InventoryServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StorefrontService {

    private final StorefrontProductRepository storefrontProductRepository;
    private final StorefrontCategoryRepository categoryRepository;
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

        return categoryRepository.save(category);
    }

    public StorefrontCategory updateCategory(String orgId, String categoryId, CategoryRequestDto dto) {
        StorefrontCategory existingCategory = categoryRepository.findById(orgId, categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category with ID " + categoryId + " not found."));

        // Update fields from DTO
        existingCategory.setName(dto.getName());
        existingCategory.setSlug(dto.getName().toLowerCase().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", ""));
        existingCategory.setDescription(dto.getDescription());
        existingCategory.setImageUrl(dto.getImageUrl());
        existingCategory.setParentCategoryId(dto.getParentCategoryId());

        return categoryRepository.save(existingCategory);
    }

    public List<StorefrontCategory> getCategories(String orgId) {
        return categoryRepository.findAllByOrganization(orgId);
    }

    public void deleteCategory(String orgId, String categoryId) {
        // TODO: Add logic to check if any products are using this category before deleting.
        categoryRepository.deleteById(orgId, categoryId);
    }

    // --- Product Enrichment ---

    public StorefrontProduct enrichProduct(String orgId, String branchId, String productId, ProductEnrichmentRequestDto dto) {
        // Find existing enrichment data or create a new one
        StorefrontProduct product = storefrontProductRepository.findById(orgId, branchId, productId)
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

        return storefrontProductRepository.save(product,orgId,branchId);
    }

    /**
     * Fetches and combines product data from the CMS and Inventory services.
     * This is a public-facing method used by the e-commerce website.
     *
     * @param orgId The ID of the organization's store being viewed.
     * @param productId The ID of the product to fetch.
     * @return A rich, combined DTO for the product page.
     */
    public PublicProductDetailResponse getPublicProductDetails(String orgId, String productId) {

        // ===================================================================
        // Step A: (Internal Read) Get the presentation data from our own database.
        // ===================================================================
        //StorefrontProduct storefrontProduct = null;

        StorefrontProduct storefrontProduct = storefrontProductRepository
                .findByOrganizationIdAndProductId(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));

        // If the product is not marked as visible, treat it as not found.
        if (!storefrontProduct.isVisible()) {
            throw new ResourceNotFoundException("Product not found.");
        }

        // ===================================================================
        // Step B & C: (Internal API Call) Call the inventory-service via Feign.
        // ===================================================================
        // The Feign client will automatically handle authentication.
        Medicine inventoryData = inventoryServiceClient.getMedicineDetails(productId);

        // Also fetch the category name for display
        String categoryName = categoryRepository.findById(orgId,storefrontProduct.getCategoryId())
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
               // .mrp(inventoryData.)
                // Data from Storefront (CMS) Service
                .richDescription(storefrontProduct.getRichDescription())
                .images(storefrontProduct.getImages())
                .categoryName(categoryName)
                .build();
    }
}

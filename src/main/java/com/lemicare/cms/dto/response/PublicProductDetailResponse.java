package com.lemicare.cms.dto.response;

import com.cosmicdoc.common.model.ImageAsset;
import com.cosmicdoc.common.model.StorefrontProduct;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * A rich Data Transfer Object (DTO) representing the complete, combined view
 * of a product for the public e-commerce storefront.
 * <p>
 * This object is constructed by the StorefrontService, which orchestrates calls
 * to the Inventory Service and its own database to gather all necessary information.
 */
@Data
@Builder
public class PublicProductDetailResponse {

    // --- Core Product Information (from Inventory Service) ---
    private String productId;
    private String name;
    private String genericName;
    private String manufacturer;
    private String unitOfMeasurement;
    private double mrp; // The Maximum Retail Price (selling price)

    // --- Live Stock Information (from Inventory Service) ---
    private int availableStock;
    private String stockStatus; // e.g., "In Stock", "Low Stock", "Out of Stock"

    // --- Enriched Content (from Storefront Service / CMS) ---
    private String richDescription;
     private List<ImageAsset> images;

    private String categoryName;
    private List<String> tags;


}

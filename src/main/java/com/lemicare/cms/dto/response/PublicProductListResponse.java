package com.lemicare.cms.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PublicProductListResponse {
    private String productId;
    private String name;
    private String mainImageUrl; // Just the primary image for the list view
    private double mrp;
    private String stockStatus; // e.g., "In Stock", "Out of Stock"
}

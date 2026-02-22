package com.lemicare.cms.dto.response;

import com.cosmicdoc.common.model.ImageAsset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductWithStockResponse {

    // CMS fields
    private String productId;
    private String productName;
    private String categoryName;
    private Double mrp;
    private String slug;
    private List<ImageAsset> images;

    // Inventory fields
    private int stockLevel;

    // Derived fields
    private boolean inStock;
    private boolean lowStock;
}

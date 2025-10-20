package com.lemicare.cms.dto.request;

import com.cosmicdoc.common.model.PhysicalDimensions;
import com.cosmicdoc.common.model.Weight;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class ProductEnrichmentRequestDto {
    @NotNull(message = "Visibility status is required.")
    private boolean isVisible;
    private String richDescription;
    private String categoryId;
    private List<String> tags;
    private String highlights;
    private String slug;
    private List<ImageMetadataDto> images;
    private PhysicalDimensions dimensions; // Added PhysicalDimensions
    private Weight weight;                 // Added Weight

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageMetadataDto {
        private String assetId; // null for new images, ID for existing images
        private String altText;
        private int displayOrder;
        private boolean delete; // True if this existing image should be deleted
        // Add other metadata needed to match uploaded files to these metadata entries,
        // e.g., a temporary client-side ID or original filename.
        // For simplicity, we'll assume the order/index in the list and matching imageFiles by index.
    }
}

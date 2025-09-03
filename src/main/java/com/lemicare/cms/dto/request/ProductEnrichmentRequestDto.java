package com.lemicare.cms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProductEnrichmentRequestDto {
    @NotNull(message = "Visibility status is required.")
    private boolean isVisible;
    private String richDescription;
    private String categoryId;
    private List<String> tags;
    private String highLights;
    private String slug;
    // Note: Image management will be a separate set of endpoints.
}

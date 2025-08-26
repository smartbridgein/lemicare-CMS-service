package com.lemicare.cms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategoryRequestDto {
    @NotBlank(message = "Category name is required.")
    private String name;
    private String description;
    private String imageUrl;
    private String parentCategoryId; // Optional, for sub-categories
}

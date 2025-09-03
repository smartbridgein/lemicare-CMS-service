package com.lemicare.cms.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class ProductImageUploadRequestDto {
    private MultipartFile imageFile;
    private String altText;
    private int displayOrder;
    private String productId;

}
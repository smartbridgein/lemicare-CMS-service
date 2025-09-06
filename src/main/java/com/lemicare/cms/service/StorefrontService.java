package com.lemicare.cms.service;

import com.cosmicdoc.common.model.ImageAsset;
import com.cosmicdoc.common.model.Medicine;
import com.cosmicdoc.common.model.StorefrontCategory;
import com.cosmicdoc.common.model.StorefrontProduct;
import com.cosmicdoc.common.repository.StorefrontCategoryRepository;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.util.FirestorePage;
import com.cosmicdoc.common.util.IdGenerator;
import com.google.api.client.util.Strings;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.CategoryRequestDto;
import com.lemicare.cms.dto.request.MedicineStockRequest;
import com.lemicare.cms.dto.request.ProductEnrichmentRequestDto;
import com.lemicare.cms.dto.response.*;
import com.lemicare.cms.integration.client.InventoryServiceClient;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorefrontService {
    private static final Logger log = LoggerFactory.getLogger(StorefrontService.class);
    private final StorefrontProductRepository storefrontProductRepository;
    private final StorefrontCategoryRepository storefrontCategoryRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final Storage storage; // Google Cloud Storage client

    @Value("${gcp.storage.bucket-name}")
    private String bucketName;

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

    public StorefrontProduct enrichProduct(String orgId, String branchId, String productId, ProductEnrichmentRequestDto request) {
        // Find existing enrichment data or create a new one
        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found."));

        // Apply enrichment from request DTO
        if (!Strings.isNullOrEmpty(request.getRichDescription())) {
            product.setRichDescription(request.getRichDescription());
        }
        if (!Strings.isNullOrEmpty(request.getHighLights())) {
            product.setHighlights(request.getHighLights());
        }
        product.setVisible(request.isVisible()); // Boolean always updates
        if (!Strings.isNullOrEmpty(request.getCategoryId())) {
            product.setCategoryId(request.getCategoryId());
        }
        if (!Strings.isNullOrEmpty(request.getSlug())) {
            product.setSlug(request.getSlug());
        }
        if (request.getTags() != null) {
            product.setTags(request.getTags());
        }

        // Save the updated product back to Firestore
        return storefrontProductRepository.save(product);
    }


    /**
     * Handles uploading a product image to Google Cloud Storage, generating multiple sizes,
     * and updating the StorefrontProduct document with the image metadata.
     *
     * @param orgId The organization ID.
      * @param productId The ID of the product to associate the image with.
     * @param imageFile The MultipartFile containing the image data.
     * @param altText The alt text for the image.
     * @param displayOrder The display order for the image in a gallery.
     * @return The updated StorefrontProduct document.
     * @throws IOException If there's an issue reading/writing image data.
     * @throws ExecutionException If Firestore operation fails.
     * @throws InterruptedException If Firestore operation is interrupted.
     */
    public StorefrontProduct uploadProductImage(
            String orgId, String productId,
            MultipartFile imageFile, String altText, int displayOrder)
            throws IOException, ExecutionException, InterruptedException {

        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for image upload."));

        if (imageFile.isEmpty()) {
            throw new IllegalArgumentException("Image file cannot be empty.");
        }

        String assetId = IdGenerator.newId("IMG"); // Unique ID for this image asset
        String originalFileName = imageFile.getOriginalFilename();
        String fileExtension = originalFileName != null && originalFileName.contains(".") ?
                originalFileName.substring(originalFileName.lastIndexOf(".")) : ".jpg"; // Default to .jpg if no extension

        // Base path for all versions of this image asset
        String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);

        // --- 1. Upload Original Image ---
        String originalBlobName = basePath + "original" + fileExtension;
        BlobInfo originalBlobInfo = storage.create(
                BlobInfo.newBuilder(bucketName, originalBlobName).setContentType(imageFile.getContentType()).build(),
                imageFile.getInputStream()
        );
        String originalUrl = originalBlobInfo.getMediaLink(); // Public URL


        // --- 2. Generate and Upload Resized Images ---
        // This part would ideally be an asynchronous process (e.g., Cloud Function or a separate worker)
        // to avoid blocking the API request, especially for large images.
        // For demonstration, we'll do it synchronously here.

        ByteArrayInputStream originalImageStream = new ByteArrayInputStream(imageFile.getBytes());

        String thumbnailUrl = generateAndUploadResizedImage(originalImageStream, basePath, "thumb", fileExtension, 200, 200);
        originalImageStream.reset(); // Reset stream for next read
        String mediumUrl = generateAndUploadResizedImage(originalImageStream, basePath, "medium", fileExtension, 600, 600);
        originalImageStream.reset();
        String largeUrl = generateAndUploadResizedImage(originalImageStream, basePath, "large", fileExtension, 1200, 1200);

        // --- 3. Update StorefrontProduct in Firestore ---
        ImageAsset newImageAsset = ImageAsset.builder()
                .assetId(assetId)
                .originalUrl(originalUrl)
                .thumbnailUrl(thumbnailUrl)
                .mediumUrl(mediumUrl)
                .largeUrl(largeUrl)
                .altText(altText != null ? altText : product.getProductName()+ " image") // Default alt text
                .displayOrder(displayOrder)
                .build();

        // Add the new image asset and re-sort (if displayOrder is crucial)
        product.getImages().add(newImageAsset);
        product.getImages().sort(Comparator.comparingInt(ImageAsset::getDisplayOrder)); // Keep images sorted

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

       // String sourceBranchId = storefrontProduct.getBranchId();
        /*if (sourceBranchId == null) {
            // Product has not been fully configured for sale
            throw new ResourceNotFoundException("Product not available for online sale.");
        }

*/        // If the product is not marked as visible, treat it as not found.
        if (!storefrontProduct.isVisible()) {
            throw new ResourceNotFoundException("Product not found.");
        }

        // ===================================================================
        // Step B & C: (Internal API Call) Call the inventory-service via Feign.
        // ===================================================================
        // The Feign client will automatically handle authentication.
       // MedicineStockResponse inventoryData = inventoryServiceClient.getMedicineDetails(productId);

        // Also fetch the category name for display
        String categoryName = null;
        if(storefrontProduct.getCategoryId() != null) {
            categoryName = storefrontCategoryRepository.findById(orgId, storefrontProduct.getCategoryId())
                    .map(StorefrontCategory::getName)
                    .orElse("Uncategorized");
        }


        // ===================================================================
        // Step D: Combine and Respond
        // ===================================================================
        return PublicProductDetailResponse.builder()
                // Data from Inventory Service
                .productId(storefrontProduct.getProductId())
                .name(storefrontProduct.getProductName())
                .genericName(storefrontProduct.getSlug())
               // .manufacturer(inventoryData.getManufacturer())

                .availableStock(storefrontProduct.getStockLevel()) // Assuming this is on the detail response
                .mrp(storefrontProduct.getMrp())
                // Data from Storefront (CMS) Service
                .richDescription(storefrontProduct.getRichDescription())
                .images(storefrontProduct.getImages())
                .categoryName(categoryName)
                .build();

           //incrediants
           //offer  --> next release ,promo code --> discount
    }




    public PaginatedResponse<PublicProductListResponse> findAllByOrganizationlistPublicProducts(
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
      //  MedicineStockRequest stockRequest = new MedicineStockRequest(medicineIds);
       // List<MedicineStockResponse> inventoryData = inventoryServiceClient.getStockLevelsForMedicines(stockRequest);

        // Create a lookup map for easy access
       // Map<String, MedicineStockResponse> inventoryMap = inventoryData.stream()
       //         .collect(Collectors.toMap(MedicineStockResponse::getMedicineId, Function.identity()));

        // 4. Combine the two data sources into the final response DTO.
        List<PublicProductListResponse> responseList = cmsProducts.stream().map(cmsProduct -> {
           // MedicineStockResponse stockInfo = inventoryMap.get(cmsProduct.getProductId());
          //  if (stockInfo == null) {
                // If a product from CMS is not found in inventory, decide how to handle.
                // Current code returns null, which then gets filtered out.
                // This correctly ensures only products with inventory data are shown.
                //return null;
          //  }

            return PublicProductListResponse.builder()
                    .productId(cmsProduct.getProductId())
                    .name(cmsProduct.getProductName()) // Name from inventory
                    .mainImageUrl(cmsProduct.getImages().isEmpty() ? null : cmsProduct.getImages().get(0).getOriginalUrl())
                    .mrp(cmsProduct.getMrp()) // MRP from inventory
                    .stockStatus(cmsProduct.getCurrentStatus()) // Stock status from inventory
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

    public void updateProductStockLevel(String orgId,String branchId, String productId, int newStockLevel,String productName, Double mrp) {
        // The branchId needs to be part of the key for StorefrontProduct
        // If the StockLevelChangedEvent doesn't carry branchId, you'll need a strategy
        // (e.g., assume a default branch, or publish an event per branch if stock is branch-specific)
        // For now, let's assume the event passes branchId or we infer it.
        // If the inventory service is publishing stock changes, it *must* include branchId
        // if StorefrontProduct documents are partitioned by branchId.
        // Assuming branchId is now part of the StockLevelChangedEvent and passed here.

        Optional<StorefrontProduct> existingProductOpt = storefrontProductRepository.findById(orgId, productId);

        StorefrontProduct product;
        boolean isNewProduct = false;

        if (existingProductOpt.isPresent()) {
            product = existingProductOpt.get();
        } else {
            // Product does not exist in Storefront. Create a new entry.
            // This happens when a new medicine is first added to inventory and purchased.
            log.info("Creating new StorefrontProduct entry for productId {} (Org: {}, Branch: {}) as it doesn't exist.", productId, orgId, branchId);
            product = StorefrontProduct.builder()
                    .productId(productId)
                    .organizationId(orgId)
                    //.branchId(branchId)
                    .isVisible(false) // Default to not visible until CMS admin enriches it
                    .images(new java.util.ArrayList<>())
                    .tags(new java.util.ArrayList<>())
                    .build();
            isNewProduct = true;
        }

        if(product.getProductId() == null || product.getProductId().isEmpty()) {
            product.setProductId(productId);
        }
        // Always update core product details that come from inventory
        product.setProductName(productName);
        product.setMrp(mrp);

        // Update stock level and derive status
        product.setStockLevel(newStockLevel);
        product.setCurrentStatus(deriveStockStatus(newStockLevel));

        StorefrontProduct savedProduct = storefrontProductRepository.save(product);

        if (isNewProduct) {
            log.info("Successfully created new StorefrontProduct and updated stock for productId {}. New Stock: {}, Status: {}",
                    productId, savedProduct.getStockLevel(), savedProduct.getCurrentStatus());
        } else {
            log.info("Successfully updated stock for StorefrontProduct productId {}. New Stock: {}, Status: {}",
                    productId, savedProduct.getStockLevel(), savedProduct.getCurrentStatus());
        }
    }

    /**
     * Helper method to derive stock status based on quantity and threshold.
     */
    private String deriveStockStatus(int quantity) {
        if (quantity <= 0) {
            return "Out of Stock";
        }  else {
            return "In Stock";
        }
    }

    /**
     * Deletes an image from Cloud Storage and removes its metadata from the StorefrontProduct.
     *
     * @param orgId The organization ID.
     * @param productId The ID of the product.
     * @param assetId The unique ID of the image asset to delete.
     * @return The updated StorefrontProduct document.
     * @throws ExecutionException If Firestore operation fails.
     * @throws InterruptedException If Firestore operation is interrupted.
     */
    public StorefrontProduct deleteProductImage(String orgId, String productId, String assetId) throws ExecutionException, InterruptedException {
        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for image deletion."));

        // Remove the ImageAsset from the list
        boolean removedFromList = product.getImages().removeIf(image -> image.getAssetId().equals(assetId));

        if (removedFromList) {
            // Delete all associated blobs from GCS
            String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);
            // List all blobs with this prefix and delete them
            for (Blob blob : storage.list(bucketName, Storage.BlobListOption.prefix(basePath)).iterateAll()) {
                blob.delete();
                log.info("Deleted GCS blob: {}", blob.getName());
            }
            log.info("Image asset {} and associated GCS files deleted for product {}", assetId, productId);
        } else {
            log.warn("Image asset {} not found in product {} for deletion. No GCS files deleted.", assetId, productId);
        }

        return storefrontProductRepository.save(product);
    }

    /**
     * Unified method to enrich product metadata and manage product images.
     * This method handles both text/boolean field updates and image uploads/deletions.
     *
     * @param orgId The organization ID.
     * @param branchId The branch ID.
     * @param productId The ID of the product to update.
     * @param request The ProductEnrichmentRequestDto containing metadata updates and image instructions.
     * @param imageFiles An array of MultipartFiles for new images to be uploaded.
     * @return The updated StorefrontProduct document.
     * @throws IOException If there's an issue reading/writing image data.
     * @throws ExecutionException If Firestore operation fails.
     * @throws InterruptedException If Firestore operation is interrupted.
     */
    public StorefrontProduct updateProduct(
            String orgId, String branchId, String productId,
            ProductEnrichmentRequestDto request, MultipartFile[] imageFiles)
            throws IOException, ExecutionException, InterruptedException {

        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for update."));

        // --- 1. Update general product metadata fields ---
        if (!Strings.isNullOrEmpty(request.getRichDescription())) {
            product.setRichDescription(request.getRichDescription());
        }
        if (!Strings.isNullOrEmpty(request.getHighLights())) {
            product.setHighlights(request.getHighLights());
        }
        product.setVisible(request.isVisible());
        if (!Strings.isNullOrEmpty(request.getCategoryId())) {
            product.setCategoryId(request.getCategoryId());
        }
        if (!Strings.isNullOrEmpty(request.getSlug())) {
            product.setSlug(request.getSlug());
        }
        if (request.getTags() != null) {
            product.setTags(request.getTags());
        }

        // --- 2. Process Images (Deletions, Updates, New Uploads) ---
        List<ImageAsset> currentImages = product.getImages() != null ? new ArrayList<>(product.getImages()) : new ArrayList<>();
        List<ImageAsset> updatedImages = new ArrayList<>();

        // Map existing images by assetId for quick lookup/modification
        Map<String, ImageAsset> existingImageMap = currentImages.stream()
                .collect(Collectors.toMap(ImageAsset::getAssetId, Function.identity()));

        // Process image metadata from the request
        if (request.getImages() != null) {
            for (ProductEnrichmentRequestDto.ImageMetadataDto imageMetadata : request.getImages()) {
                if (imageMetadata.isDelete()) {
                    // This image should be deleted
                    if (imageMetadata.getAssetId() != null && existingImageMap.containsKey(imageMetadata.getAssetId())) {
                        deleteImageFilesFromGCS(orgId, productId, imageMetadata.getAssetId());
                        existingImageMap.remove(imageMetadata.getAssetId()); // Remove from our working map
                    }
                } else {
                    // Existing image to update or new image placeholder
                    ImageAsset imgAsset;
                    if (imageMetadata.getAssetId() != null && existingImageMap.containsKey(imageMetadata.getAssetId())) {
                        // Update existing image metadata
                        imgAsset = existingImageMap.get(imageMetadata.getAssetId());
                        imgAsset.setAltText(imageMetadata.getAltText());
                        imgAsset.setDisplayOrder(imageMetadata.getDisplayOrder());
                        updatedImages.add(imgAsset); // Add updated existing image to the new list
                        existingImageMap.remove(imageMetadata.getAssetId()); // Remove from map so we don't process it again
                    } else {
                        // This metadata corresponds to a new image that will be uploaded.
                        // We'll process this after processing all existing images.
                        // For now, we'll just queue up the metadata and process the actual files next.
                    }
                }
            }
        }

        // Add any remaining existing images (that weren't in the request or marked for deletion)
        updatedImages.addAll(existingImageMap.values());


        // --- 3. Handle new image file uploads ---
        // This part requires careful matching between `imageFiles` array and `request.getImages()`
        // If the client sends `imageFiles[0]` with `request.getImages()[0]` as its metadata,
        // then they need to be correlated.
        // A robust solution might involve client-side generated UUIDs passed in both.
        // For simplicity, let's assume `imageFiles` are new uploads and we assign metadata based on their order
        // or just use generic alt text and default order if not explicitly linked.

        for (int i = 0; i < imageFiles.length; i++) {
            MultipartFile imageFile = imageFiles[i];
            if (!imageFile.isEmpty()) {
                String assetId = IdGenerator.newId("IMG");;

                String originalFileName = imageFile.getOriginalFilename();
                String fileExtension = originalFileName != null && originalFileName.contains(".") ?
                        originalFileName.substring(originalFileName.lastIndexOf(".")) : ".jpg";

                String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);

                // 1. Upload Original
                String originalBlobName = basePath + "original" + fileExtension;
                BlobInfo originalBlobInfo = storage.create(
                        BlobInfo.newBuilder(bucketName, originalBlobName).setContentType(imageFile.getContentType()).build(),
                        imageFile.getInputStream()
                );
                String originalUrl = originalBlobInfo.getMediaLink();

                // 2. Generate and Upload Resized Images
                ByteArrayInputStream originalImageStream = new ByteArrayInputStream(imageFile.getBytes());
                String thumbnailUrl = generateAndUploadResizedImage(originalImageStream, basePath, "thumb", fileExtension, 200, 200);
                originalImageStream.reset();
                String mediumUrl = generateAndUploadResizedImage(originalImageStream, basePath, "medium", fileExtension, 600, 600);
                originalImageStream.reset();
                String largeUrl = generateAndUploadResizedImage(originalImageStream, basePath, "large", fileExtension, 1200, 1200);

                // 3. Create new ImageAsset
                ProductEnrichmentRequestDto.ImageMetadataDto correspondingMetadata = null;
                if (request.getImages() != null && i < request.getImages().size()) {
                    correspondingMetadata = request.getImages().get(i);
                    // This assumes the order of imageFiles directly corresponds to the order of new image metadata in request.getImages()
                    // If not, you need a more robust matching strategy (e.g., client-generated IDs).
                }

                ImageAsset newImageAsset = ImageAsset.builder()
                        .assetId(assetId)
                        .originalUrl(originalUrl)
                        .thumbnailUrl(thumbnailUrl)
                        .mediumUrl(mediumUrl)
                        .largeUrl(largeUrl)
                        .altText(correspondingMetadata != null && !Strings.isNullOrEmpty(correspondingMetadata.getAltText())
                                ? correspondingMetadata.getAltText() : product.getProductName() + " image " + (updatedImages.size() + 1))
                        .displayOrder(correspondingMetadata != null ? correspondingMetadata.getDisplayOrder() : updatedImages.size()) // Default order
                        .build();
                updatedImages.add(newImageAsset);
            }
        }

        // Sort all images by display order before saving
        updatedImages.sort(Comparator.comparingInt(ImageAsset::getDisplayOrder));
        product.setImages(updatedImages);

        // --- 4. Save the final updated product ---
        return storefrontProductRepository.save(product);
    }


    /**
     * Helper method to generate a resized image and upload it to GCS.
     */
    private String generateAndUploadResizedImage(
            InputStream originalImageStream,
            String basePath,
            String sizePrefix,
            String fileExtension,
            int width, int height) throws IOException {

        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();

        Thumbnails.of(originalImageStream)
                .size(width, height)
                .outputFormat(fileExtension.substring(1))
                .toOutputStream(os);

        byte[] resizedImageBytes = os.toByteArray();

        String resizedBlobName = basePath + sizePrefix + "_" + width + "x" + height + fileExtension;
        BlobInfo resizedBlobInfo = storage.create(
                BlobInfo.newBuilder(bucketName, resizedBlobName)
                        .setContentType("image/" + fileExtension.substring(1))
                        .build(),
                resizedImageBytes
        );
        return resizedBlobInfo.getMediaLink();
    }





    /**
     * Helper method to delete all associated blobs for an image asset from GCS.
     */
    private void deleteImageFilesFromGCS(String orgId, String productId, String assetId) {
        String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);
        for (Blob blob : storage.list(bucketName, Storage.BlobListOption.prefix(basePath)).iterateAll()) {
            blob.delete();
            log.info("Deleted GCS blob: {}", blob.getName());
        }
        log.info("Image asset {} and associated GCS files deleted for product {}", assetId, productId);
    }

    public List<StorefrontProduct> getAvailableProducts(String orgId) {
          return storefrontProductRepository.findAllByOrganizationId(orgId);
    }
}



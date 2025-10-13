package com.lemicare.cms.service;


import com.cosmicdoc.common.model.*;
import com.cosmicdoc.common.repository.StorefrontCategoryRepository;
import com.cosmicdoc.common.repository.StorefrontOrderRepository;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.util.FirestorePage;
import com.cosmicdoc.common.util.IdGenerator;
import com.google.api.client.util.Strings;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.Role;
import com.google.cloud.Timestamp;
import com.google.cloud.storage.*;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.*;
import com.lemicare.cms.dto.response.*;
import com.lemicare.cms.integration.client.InventoryServiceClient;
import com.lemicare.cms.integration.client.PaymentServiceClient;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.Acl.User;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;


@Service
@RequiredArgsConstructor
public class StorefrontService {
    private static final Logger log = LoggerFactory.getLogger(StorefrontService.class);
    private final StorefrontProductRepository storefrontProductRepository;
    private final StorefrontCategoryRepository storefrontCategoryRepository;
    private final StorefrontOrderRepository storefrontOrderRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final Storage storage; // Google Cloud Storage client
    PaymentServiceClient paymentServiceClient;

    private static final Map<String, String> IMAGE_FORMAT_MAP = new HashMap<>();

    static {
        IMAGE_FORMAT_MAP.put(".jpg", "image/jpeg");
        IMAGE_FORMAT_MAP.put(".jpeg", "image/jpeg");
        IMAGE_FORMAT_MAP.put(".png", "image/png");
        IMAGE_FORMAT_MAP.put(".gif", "image/gif");
        IMAGE_FORMAT_MAP.put(".webp", "image/webp");
    }

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
            product.setCategoryName(request.getCategoryId());
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
     * @param orgId        The organization ID.
     * @param productId    The ID of the product to associate the image with.
     * @param imageFile    The MultipartFile containing the image data.
     * @param altText      The alt text for the image.
     * @param displayOrder The display order for the image in a gallery.
     * @return The updated StorefrontProduct document.
     * @throws IOException          If there's an issue reading/writing image data.
     * @throws ExecutionException   If Firestore operation fails.
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
                .altText(altText != null ? altText : product.getProductName() + " image") // Default alt text
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
        String categoryName = storefrontProduct.getCategoryName() != null ? storefrontProduct.getCategoryName() : "Uncategorized";

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

    public void updateProductStockLevel(String orgId, String branchId, String productId, int newStockLevel, String productName, Double mrp, String taxProfileId, String gstType, String category) {
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
                    .categoryName(category)
                    .taxProfileId(taxProfileId)
                    .gstType(gstType)
                    .isVisible(false) // Default to not visible until CMS admin enriches it
                    .images(new java.util.ArrayList<>())
                    .tags(new java.util.ArrayList<>())
                    .build();
            isNewProduct = true;
        }

        if (product.getProductId() == null || product.getProductId().isEmpty()) {
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
        } else {
            return "In Stock";
        }
    }

    /**
     * Deletes an image from Cloud Storage and removes its metadata from the StorefrontProduct.
     *
     * @param orgId     The organization ID.
     * @param productId The ID of the product.
     * @param assetId   The unique ID of the image asset to delete.
     * @return The updated StorefrontProduct document.
     * @throws ExecutionException   If Firestore operation fails.
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
            product.setCategoryName(request.getCategoryId());
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

    public StorefrontOrder createPendingOrder(String orgId, InitiateCheckoutRequest request)
            throws ExecutionException, InterruptedException {

        // Generate a unique order ID
        String orderId = IdGenerator.newId("ORD");

        List<StorefrontOrderItem> orderItems = new ArrayList<>();
        double grandTotal = 0.0; // We'll calculate this now

        for (CartItemDto cartItem : request.getCartItems()) {
            // --- IMPORTANT: Real-world Scenario ---
            // In a production system, you MUST fetch the actual product price, stock,
            // and tax information from your inventory/CMS service here using cartItem.productId.
            // DO NOT trust the price (mrpPerItem) and discount sent by the frontend directly.
            // This prevents price manipulation.
            // For this example, we'll use the DTO values for simplicity, but mark this
            // as a critical point for refinement.

            // Example:
            // Product realProduct = productLookupService.getProductDetails(orgId, cartItem.getProductId());
            // if (realProduct == null || realProduct.getStockLevel() < cartItem.getQuantity()) {
            //     throw new InsufficientStockException("Not enough stock for " + cartItem.getProductName());
            // }
            // double verifiedMrp = realProduct.getMrp();
            // double verifiedDiscount = calculateDiscount(realProduct, cartItem.getQuantity());
            // TaxDetails verifiedTax = calculateTax(realProduct, orgId, branchId); // Needs branchId

            // For now, using DTO values:
            double mrpPerItem = cartItem.getMrpPerItem();
            double discountPercentage = cartItem.getDiscountPercentage();
            int quantity = cartItem.getQuantity();

            double lineItemGrossMrp = mrpPerItem * quantity;
            double lineItemDiscountAmount = lineItemGrossMrp * (discountPercentage / 100.0);
            double lineItemNetAfterDiscount = lineItemGrossMrp - lineItemDiscountAmount;

            // --- Tax Calculation (Simplified for example) ---
            // In a real system, tax calculation would be complex, involving:
            // 1. Product's tax profile
            // 2. Customer's shipping address (state/country)
            // 3. Organization's tax settings
            // For now, let's assume a flat 18% GST (replace with actual logic)
            double taxRateApplied = 18.0; // Example
            double lineItemTaxableAmount = lineItemNetAfterDiscount; // Assuming discount before tax
            double lineItemTaxAmount = lineItemTaxableAmount * (taxRateApplied / 100.0);
            double lineItemTotalAmount = lineItemTaxableAmount + lineItemTaxAmount;

            // Example tax components (replace with actual breakdown)
            List<TaxComponent> taxComponents = new ArrayList<>();
            // taxComponents.add(TaxComponent.builder().name("CGST").rate(9.0).amount(lineItemTaxAmount / 2).build());
            //taxComponents.add(TaxComponent.builder().name("SGST").rate(9.0).amount(lineItemTaxAmount / 2).build());


            StorefrontOrderItem orderItem = StorefrontOrderItem.builder()
                    .productId(cartItem.getProductId())
                    .productName(cartItem.getProductName())
                    .sku(cartItem.getSku())
                    .quantity(quantity)
                    .mrpPerItem(mrpPerItem)
                    .discountPercentage(discountPercentage)
                    .lineItemGrossMrp(lineItemGrossMrp)
                    .lineItemDiscountAmount(lineItemDiscountAmount)
                    .lineItemNetAfterDiscount(lineItemNetAfterDiscount)
                    .lineItemTaxableAmount(lineItemTaxableAmount)
                    .lineItemTaxAmount(lineItemTaxAmount)
                    .lineItemTotalAmount(lineItemTotalAmount)
                    // Tax Details Snapshot
                    // .gstType(verifiedTax.getGstType()) // Use actual tax type from lookup
                    // .taxProfileId(verifiedTax.getTaxProfileId())
                    .taxRateApplied(taxRateApplied) // Use actual rate
                    .taxComponents(taxComponents) // Use actual components
                    .build();
            orderItems.add(orderItem);

            grandTotal += lineItemTotalAmount;
        }

        // --- Determine Branch ---
        // How do you determine the branchId?
        // 1. Based on customer's shipping address (find nearest branch)?
        // 2. Pre-selected by customer on frontend?
        // 3. Default branch for the organization?
        // For now, we'll need to explicitly get it or assume it's passed or derived.
        // Let's assume it can be derived or picked from a default.
        String branchId = determineFulfillingBranch(orgId, request.getShippingAddress()); // Implement this logic

        StorefrontOrder order = StorefrontOrder.builder()
                .orderId(orderId)
                .organizationId(orgId)
                .branchId(branchId) // CRITICAL: This needs to be correctly determined
                .patientId(request.getPatientId())
                .customerInfo(request.getCustomerInfo())
                .shippingAddress(request.getShippingAddress())
                .grandTotal(grandTotal)
                .status("PENDING_PAYMENT") // Initial status
                .createdAt(Timestamp.now())
                .items(orderItems)
                .build();

        log.info("Saving new StorefrontOrder with ID: {} for Org: {}", orderId, orgId);
        StorefrontOrder savedOrder = storefrontOrderRepository.save(order);
        log.info("Successfully created pending order: {}", savedOrder.getOrderId());

        return savedOrder;
    }

    private String determineFulfillingBranch(String orgId, Map<String, String> shippingAddress) {
        // Implement logic here to determine which branch should fulfill the order.
        // This could involve:
        // - Looking up branches by geo-location/delivery radius.
        // - Using a default branch for the organization.
        // - If the frontend explicitly sends a branch preference, use that.
        // For simplicity, returning a hardcoded dummy for now.
        log.warn("Using dummy branchId for order fulfillment. Implement actual branch determination logic!");
        // return "some-derived-branch-id";
        // Let's assume for now, there's a default branch or it's implicitly part of the org.
        // In a single-branch org, it might always be the same.
        // If your SecurityUtils.getBranchId() can give a context, you could use that.
        // Or if the frontend passes it in the request.
        // IMPORTANT: You might need to update InitiateCheckoutRequest to include branchId.
        return "branch_default_001"; // Placeholder
    }

    public CreateOrderResponse createPaymentOrder(String orgId, CreateOrderRequest request) {

        return paymentServiceClient.createPaymentOrder(request);

    }

    public StorefrontProduct getProductById(String orgId, String productId) {
        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for update."));
        return product;
    }

    /*public StorefrontProduct updateProduct(
            String orgId, String branchId, String productId, // branchId is currently unused
            ProductEnrichmentRequestDto request, MultipartFile[] imageFiles)
            throws IOException, ExecutionException, InterruptedException {

        // --- 0. Input Validation ---
        if (Strings.isNullOrEmpty(orgId)) {
            throw new IllegalArgumentException("Organization ID cannot be null or empty.");
        }
        if (Strings.isNullOrEmpty(productId)) {
            throw new IllegalArgumentException("Product ID cannot be null or empty.");
        }
        if (request == null) {
            throw new IllegalArgumentException("Product enrichment request cannot be null.");
        }
        // Ensure imageFiles array is not null to avoid NullPointerException in loop
        if (imageFiles == null) {
            imageFiles = new MultipartFile[0];
        }


        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for update."));

        // --- 1. Update general product metadata fields ---
        if (!Strings.isNullOrEmpty(request.getRichDescription())) {
            product.setRichDescription(request.getRichDescription());
        }
        if (!Strings.isNullOrEmpty(request.getHighLights())) {
            product.setHighlights(request.getHighLights());
        }
        product.setVisible(request.isVisible()); // Boolean, usually not null
        if (!Strings.isNullOrEmpty(request.getCategoryId())) {
            product.setCategoryName(request.getCategoryId()); // Using categoryId for categoryName
        }
        if (!Strings.isNullOrEmpty(request.getSlug())) {
            product.setSlug(request.getSlug());
        }
        if (request.getTags() != null) {
            // Ensure tags are updated with a new list, defensive copy.
            product.setTags(new ArrayList<>(request.getTags()));
        }

        // --- 2. Process Images (Deletions, Updates, New Uploads) ---

        // Start with a mutable copy of current images for working set
        List<ImageAsset> currentProductImages = product.getImages() != null ? new ArrayList<>(product.getImages()) : new ArrayList<>();

        // Create a map from existing images for efficient lookup/modification by assetId.
        // Using LinkedHashMap to preserve order if existing images' order is relevant,
        // though final sort by displayOrder will re-establish.
        Map<String, ImageAsset> existingImageAssetsById = currentProductImages.stream()
                .collect(Collectors.toMap(
                        ImageAsset::getAssetId,
                        Function.identity(),
                        (existing, replacement) -> existing, // Merge function for duplicate keys (shouldn't happen with unique assetId)
                        () -> new HashMap<>() // Supplier for the map type
                ));

        // List to hold metadata for *new* image files to be uploaded.
        // These are imageMetadata DTOs that don't have an assetId yet.
        List<ProductEnrichmentRequestDto.ImageMetadataDto> newImageMetadataQueue = new ArrayList<>();

        // Process incoming image metadata from the request DTO
        if (request.getImages() != null) {
            for (ProductEnrichmentRequestDto.ImageMetadataDto imageMetadata : request.getImages()) {
                if (imageMetadata.getAssetId() != null) {
                    // This metadata refers to an existing image
                    ImageAsset existingAsset = existingImageAssetsById.get(imageMetadata.getAssetId());
                    if (existingAsset != null) {
                        if (imageMetadata.isDelete()) {
                            log.info("Deleting image asset: {} for product: {}", imageMetadata.getAssetId(), productId);
                            deleteImageFilesFromGCS(orgId, productId, imageMetadata.getAssetId());
                            existingImageAssetsById.remove(imageMetadata.getAssetId()); // Mark for removal from the final list
                        } else {
                            // Update metadata for existing image
                            existingAsset.setAltText(imageMetadata.getAltText());
                            existingAsset.setDisplayOrder(imageMetadata.getDisplayOrder());
                            // No need to re-add to map; it's already there and updated
                        }
                    } else {
                        log.warn("Received metadata for non-existent or already deleted image asset: {} for product: {}",
                                imageMetadata.getAssetId(), productId);
                    }
                } else if (!imageMetadata.isDelete()) { // No assetId and not a delete means it's for a new upload
                    newImageMetadataQueue.add(imageMetadata);
                }
            }
        }

        // The 'existingImageAssetsById.values()' now contains all images that were NOT deleted
        // and have their metadata (altText, displayOrder) updated if present in the request.
        List<ImageAsset> finalImagesToPersist = new ArrayList<>(existingImageAssetsById.values());


        // --- 3. Handle new image file uploads ---
        // This assumes a 1:1 positional correspondence between imageFiles and newImageMetadataQueue.
        // For production, if order cannot be guaranteed, client-generated temporary IDs in both
        // MultipartFile and ImageMetadataDto are recommended for robust matching.
        for (int i = 0; i < imageFiles.length; i++) {
            MultipartFile imageFile = imageFiles[i];
            // Only process if there's a corresponding metadata entry for a new image upload
            // and the file itself is not empty.
            if (i >= newImageMetadataQueue.size()) {
                log.warn("No corresponding metadata found for MultipartFile at index {}. Skipping upload.", i);
                continue;
            }
            if (imageFile == null || imageFile.isEmpty()) {
                log.warn("Skipping empty or null MultipartFile at index {}", i);
                continue;
            }

            ProductEnrichmentRequestDto.ImageMetadataDto correspondingMetadata = newImageMetadataQueue.get(i);

            try {
                String assetId = IdGenerator.newId("IMG");
                String originalFileName = imageFile.getOriginalFilename();
                String fileExtension = getFileExtension(originalFileName);
                String contentType = getContentType(fileExtension, imageFile.getContentType());

                String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);

                // Read bytes once for resizing
                byte[] imageBytes = imageFile.getBytes();

                // 1. Upload Original Image
                String originalBlobName = basePath + "original" + fileExtension;
                BlobInfo originalBlobInfo = storage.create(
                        BlobInfo.newBuilder(bucketName, originalBlobName)
                                .setContentType(contentType)
                                .build(),
                        new ByteArrayInputStream(imageBytes) // Use fresh stream for original upload
                );
                makeBlobPubliclyReadable(originalBlobInfo.getBlobId()); // Ensure public readability
                String originalUrl = originalBlobInfo.getMediaLink();
                log.info("Uploaded original image: {} to GCS", originalUrl);

                // 2. Generate and Upload Resized Images
                String thumbnailUrl = generateAndUploadResizedImage(
                        new ByteArrayInputStream(imageBytes), basePath, "thumb", fileExtension, 200, 200);
                String mediumUrl = generateAndUploadResizedImage(
                        new ByteArrayInputStream(imageBytes), basePath, "medium", fileExtension, 600, 600);
                String largeUrl = generateAndUploadResizedImage(
                        new ByteArrayInputStream(imageBytes), basePath, "large", fileExtension, 1200, 1200);

                log.info("Uploaded resized images for assetId: {}", assetId);

                // 3. Create new ImageAsset
                String altText = (correspondingMetadata != null && !Strings.isNullOrEmpty(correspondingMetadata.getAltText()))
                        ? correspondingMetadata.getAltText()
                        : (product.getProductName() != null ? product.getProductName() : "Product") + " image " + (finalImagesToPersist.size() + 1);
                int displayOrder = (correspondingMetadata != null)
                        ? correspondingMetadata.getDisplayOrder()
                        : (finalImagesToPersist.size() + 1); // Assign a unique default order

                ImageAsset newImageAsset = ImageAsset.builder()
                        .assetId(assetId)
                        .originalUrl(originalUrl)
                        .thumbnailUrl(thumbnailUrl)
                        .mediumUrl(mediumUrl)
                        .largeUrl(largeUrl)
                        .altText(altText)
                        .displayOrder(displayOrder)
                        .fileExtension(fileExtension)
                        .build();
                finalImagesToPersist.add(newImageAsset); // Add new image to the final list

            } catch (IOException e) {
                log.error("Failed to process image file at index {}: {}", i, e.getMessage(), e);
                // For production, consider:
                // 1. Logging the failure and continuing for other images.
                // 2. Rolling back previously uploaded GCS files for this product if image processing is critical.
                // 3. Re-throwing a more specific exception for the client.
                throw new IOException("Failed to process image file for upload at index " + i + ": " + e.getMessage(), e);
            }
        }

        // Sort all images by display order before saving
        // Use a stable sort to ensure consistent order if displayOrder values are the same
        finalImagesToPersist.sort(Comparator.comparingInt(ImageAsset::getDisplayOrder));
        product.setImages(finalImagesToPersist);

        // --- 4. Save the final updated product ---
        try {
            return storefrontProductRepository.save(product);
        } catch (Exception e) {
            log.error("Failed to save product {} after image updates: {}", productId, e.getMessage(), e);
            // Critical consideration: If database save fails, you might have orphaned GCS files.
            // Implement transaction management or a cleanup mechanism (e.g., a GCS lifecycle policy
            // to delete old files, or a background job to identify and delete unreferenced blobs).
            throw new RuntimeException("Failed to save product changes to database.", e);
        }
    }

    *//**
     * Helper method to determine file extension from filename.
     *//*
    private String getFileExtension(String fileName) {
        if (!Strings.isNullOrEmpty(fileName) && fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
            if (IMAGE_FORMAT_MAP.containsKey(extension)) {
                return extension;
            }
        }
        return ".jpg"; // Default to JPG if no valid extension found
    }

    *//**
     * Helper method to determine content type.
     *//*
    private String getContentType(String fileExtension, String defaultContentType) {
        String mappedContentType = IMAGE_FORMAT_MAP.get(fileExtension);
        if (mappedContentType != null) {
            return mappedContentType;
        }
        // Fallback to defaultContentType if valid image, else jpeg
        return (!Strings.isNullOrEmpty(defaultContentType) && defaultContentType.startsWith("image/"))
                ? defaultContentType
                : "image/jpeg";
    }

    *//**
     * Helper method to delete all versions of an image from GCS based on assetId.
     * Assumes consistent naming convention (original, thumb, medium, large) and stored extension.
     * This method retrieves the product to get the exact file extension for precise deletion.
     *
     * @param orgId     The organization ID.
     * @param productId The product ID.
     * @param assetId   The asset ID of the image to delete.
     *//*
    private void deleteImageFilesFromGCS(String orgId, String productId, String assetId) {
        String storedFileExtension = null; // We'll try to find this accurately

        // Try to retrieve the product and find the image asset to get its specific file extension
        try {
            Optional<StorefrontProduct> productOptional = storefrontProductRepository.findById(orgId, productId);
            if (productOptional.isPresent() && productOptional.get().getImages() != null) {
                Optional<ImageAsset> assetToDelete = productOptional.get().getImages().stream()
                        .filter(img -> assetId.equals(img.getAssetId()))
                        .findFirst();
                if (assetToDelete.isPresent() && !Strings.isNullOrEmpty(assetToDelete.get().getFileExtension())) {
                    storedFileExtension = assetToDelete.get().getFileExtension();
                }
            }
        } catch (Exception e) {
            log.warn("Could not retrieve product {} to find file extension for asset {}. Deletion might be less precise: {}",
                    productId, assetId, e.getMessage());
        }

        List<String> blobNamesToDelete = new ArrayList<>();
        String basePath = String.format("images/%s/%s/%s/", orgId, productId, assetId);

        if (storedFileExtension != null) {
            // If we found a specific extension, try to delete with it
            blobNamesToDelete.add(basePath + "original" + storedFileExtension);
            blobNamesToDelete.add(basePath + "thumb" + storedFileExtension);
            blobNamesToDelete.add(basePath + "medium" + storedFileExtension);
            blobNamesToDelete.add(basePath + "large" + storedFileExtension);
        } else {
            // Fallback: try deleting with all known image extensions
            log.warn("No specific file extension found for asset {}. Attempting deletion with all common image extensions.", assetId);
            for (String ext : IMAGE_FORMAT_MAP.keySet()) {
                blobNamesToDelete.add(basePath + "original" + ext);
                blobNamesToDelete.add(basePath + "thumb" + ext);
                blobNamesToDelete.add(basePath + "medium" + ext);
                blobNamesToDelete.add(basePath + "large" + ext);
            }
        }

        for (String blobName : blobNamesToDelete) {
            try {
                BlobId targetBlobId = BlobId.of(bucketName, blobName);
                if (storage.delete(targetBlobId)) {
                    log.debug("Successfully deleted GCS blob: {}", blobName);
                } else {
                    log.debug("GCS blob not found or already deleted (expected for some fallback attempts): {}", blobName);
                }
            } catch (Exception e) {
                // Log and continue, as one deletion failure shouldn't stop others
                log.error("Error deleting GCS blob {}: {}", blobName, e.getMessage(), e);
            }
        }
    }

    *//**
     * Resizes an image from an InputStream and uploads it to Google Cloud Storage.
     * The uploaded image is made publicly readable via IAM after creation.
     *
     * @param originalImageStream The input stream of the original image bytes. This stream will be closed by this method.
     * @param basePath            The base path in GCS (e.g., "images/orgId/productId/assetId/")
     * @param sizeQualifier       A string to append to the filename (e.g., "thumb", "medium", "large")
     * @param fileExtension       The file extension for the output (e.g., ".jpg", ".png")
     * @param targetWidth         The desired width for the resized image.
     * @param targetHeight        The desired height for the resized image.
     * @return The public URL of the uploaded resized image.
     * @throws IOException If an I/O error occurs during image processing or upload.
     *//*
    private String generateAndUploadResizedImage(
            InputStream originalImageStream, String basePath, String sizeQualifier,
            String fileExtension, int targetWidth, int targetHeight) throws IOException {

        BufferedImage originalImage = null;
        try {
            originalImage = ImageIO.read(originalImageStream);
        } catch (IIOException e) {
            log.error("Failed to read image for resizing for basePath {}. Invalid image format? {}", basePath, e.getMessage(), e);
            throw new IOException("Failed to read image for resizing. Invalid image format? " + e.getMessage(), e);
        } finally {
            try {
                if (originalImageStream != null) originalImageStream.close();
            } catch (IOException e) {
                log.warn("Failed to close original image stream during resizing for basePath: {}", basePath, e);
            }
        }

        if (originalImage == null) {
            throw new IOException("Could not read image for resizing from provided stream. Original image was null.");
        }

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // Avoid division by zero if image dimensions are invalid
        if (originalWidth <= 0 || originalHeight <= 0) {
            throw new IOException("Invalid original image dimensions: " + originalWidth + "x" + originalHeight);
        }

        double aspectRatio = (double) originalWidth / originalHeight;

        int newWidth = targetWidth;
        int newHeight = (int) (newWidth / aspectRatio);

        // If the calculated height is greater than the target height,
        // re-calculate based on target height to fit within bounds.
        if (newHeight > targetHeight) {
            newHeight = targetHeight;
            newWidth = (int) (newHeight * aspectRatio);
        }

        // Ensure new dimensions are positive and at least 1x1
        newWidth = Math.max(1, newWidth);
        newHeight = Math.max(1, newHeight);

        // Create a new buffered image with the desired size and type
        // Use TYPE_INT_ARGB for PNG/GIF to preserve transparency, otherwise TYPE_INT_RGB
        int imageType = (fileExtension.equalsIgnoreCase(".png") || fileExtension.equalsIgnoreCase(".gif"))
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, imageType);

        // Draw the original image onto the new image, scaling it using Graphics2D
        java.awt.Graphics2D g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        g.dispose(); // Release graphics resources

        // Prepare to upload the resized image
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        String formatName = fileExtension.substring(1); // e.g., "jpg", "png"

        // Ensure the formatName is valid for ImageIO.write
        if (!ImageIO.write(resizedImage, formatName, os)) {
            // This might happen if ImageIO doesn't support the format, or there's an internal error
            throw new IOException("Could not write resized image to output stream for format: " + formatName);
        }
        ByteArrayInputStream resizedInputStream = new ByteArrayInputStream(os.toByteArray());

        String resizedBlobName = basePath + sizeQualifier + fileExtension;
        String contentType = getContentType(fileExtension, null); // Get proper content type

        // Upload resized image to GCS
        BlobInfo resizedBlobInfo = storage.create(
                BlobInfo.newBuilder(bucketName, resizedBlobName)
                        .setContentType(contentType)
                        .build(),
                resizedInputStream
        );

        makeBlobPubliclyReadable(resizedBlobInfo.getBlobId()); // Make the resized object publicly readable

        return resizedBlobInfo.getMediaLink();
    }

    *//**
     * Helper method to make a GCS blob publicly readable by adding an IAM policy binding.
     * Grants the 'roles/storage.objectViewer' role to 'allUsers' for the specified blob.
     * This version correctly uses Storage.getIamPolicy() and Storage.setIamPolicy() with BlobId.
     *//*
    private void makeBlobPubliclyReadable(BlobId blobId) {
        try {
            // 1. Get the current Blob object.
            // It's essential to get the *existing* blob to ensure we update it correctly.
            Blob blob = storage.get(blobId);

            // If the blob doesn't exist (e.g., race condition), log and exit.
            if (blob == null) {
                log.warn("Attempted to set public read ACL for non-existent blob: {}", blobId.getName());
                return;
            }

            // Define the ACL entry for public read access
            Acl publicReadAcl = Acl.of(User.ofAllUsers(), Role.READER);

            // 2. Get the current list of ACLs for the blob.
            List<Acl> currentAcls = new ArrayList<>(blob.getAcls());

            // 3. Check if the public read ACL already exists to avoid redundant updates.
            boolean alreadyPublic = currentAcls.contains(publicReadAcl);

            if (!alreadyPublic) {
                // 4. If not present, add the new public read ACL to the list.
                currentAcls.add(publicReadAcl);

                // 5. Update the blob with the modified list of ACLs.
                // This replaces the blob's ACLs with the new list.
                storage.update(blob.toBuilder().setAcls(currentAcls).build());

                log.info("Successfully added public read ACL for blob: {} in bucket {}.",
                        blobId.getName(), blobId.getBucket());
            } else {
                log.debug("Blob {} in bucket {} was already publicly readable.",
                        blobId.getName(), blobId.getBucket());
            }
        } catch (Exception e) {
            log.error("Failed to make blob {} publicly readable: {}", blobId.getName(), e.getMessage(), e);
            // Re-throw as a more specific runtime exception if appropriate for your error handling strategy
            throw new RuntimeException("Failed to set public read access for blob: " + blobId.getName(), e);
        }
    }*/
}




package com.lemicare.cms.service;


import com.cosmicdoc.common.model.*;
import com.cosmicdoc.common.repository.*;
import com.cosmicdoc.common.util.CursorPage;
import com.cosmicdoc.common.util.IdGenerator;
import com.google.api.client.util.Strings;
import com.google.cloud.Timestamp;
import com.google.cloud.storage.*;
import com.lemicare.cms.exception.InventoryClientException;
import com.lemicare.cms.exception.ResourceNotFoundException;
import com.lemicare.cms.dto.request.*;
import com.lemicare.cms.dto.response.*;
import com.lemicare.cms.integration.client.InventoryService;
import com.lemicare.cms.integration.client.InventoryServiceClient;
import com.lemicare.cms.integration.client.PaymentServiceClient;
import feign.FeignException;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.cloud.storage.Blob;
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
    private final PaymentServiceClient paymentServiceClient;
    private final TaxProfileRepository taxProfileRepository;
    private final BranchRepository branchRepository;
    private final InventoryService inventoryService;
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
        if (!Strings.isNullOrEmpty(request.getHighlights())) {
            product.setHighlights(request.getHighlights());
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
        MedicineStockDetailResponse inventoryData = inventoryService.getPublicMedicineDetails(productId);

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
                 .manufacturer(inventoryData.getManufacturer())

                .availableStock(inventoryData.getTotalStock()) // Assuming this is on the detail response
                .mrp(storefrontProduct.getMrp())
                // Data from Storefront (CMS) Service
                .richDescription(storefrontProduct.getRichDescription())
                .images(storefrontProduct.getImages())
                .categoryName(categoryName)
                .build();

        //incrediants
        //offer  --> next release ,promo code --> discount
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
                    .createdAt(Timestamp.now())
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
        if (!Strings.isNullOrEmpty(request.getHighlights())) {
            product.setHighlights(request.getHighlights());
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
       // --- NEW: Handle PhysicalDimensions and Weight ---
       // Only update if the request explicitly provides them
       if (request.getDimensions() != null) {
           product.setDimensions(request.getDimensions());
       }
       if (request.getWeight() != null) {
           product.setWeight(request.getWeight());
       }
       // --- END NEW ---
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
             {
       try {
           List<Branch> branches = branchRepository.findAllByOrganizationId(orgId);

           Sale partialSale = Sale.builder()
                   .saleType("E-COMMERCE")
                   .organizationId(orgId)
                   .branchId(branches.get(0).getBranchId())
                   .gstType(parseGstType(request.getGstType()))
                   .build();


           CreateSaleRequest createSaleRequest = CreateSaleRequest.builder()
                   .orgId(orgId)
                   .branchId(branches.get(0).getBranchId())
                   .sale(partialSale)
                   .saleItemDtoList(request.getCartItems())
                   .build();

           log.info(
                   "Creating sale request | orgId={} | customerId={} | cartItemCount={} | gstType={} | courierId={}",
                   orgId,
                   createSaleRequest.getOrgId(),
                   request.getCartItems().size(),
                   request.getGstType(),
                   request.getCourierId()
           );
           Sale sale = inventoryService.createSale(createSaleRequest);
           // Generate a unique order ID
           String orderId = IdGenerator.newId("ORD");

           List<StorefrontOrderItem> orderItems = new ArrayList<>();
           double grandTotal = 0.0; // We'll calculate this now


           grandTotal = sale.getGrandTotal() + request.getShippingCost();

           // --- Determine Branch ---
           // How do you determine the branchId?
           // 1. Based on customer's shipping address (find nearest branch)?
           // 2. Pre-selected by customer on frontend?
           // 3. Default branch for the organization?
           // For now, we'll need to explicitly get it or assume it's passed or derived.
           // Let's assume it can be derived or picked from a default.
           //  String branchId = determineFulfillingBranch(orgId, request.getShippingAddress()); // Implement this logic

           StorefrontOrder order = StorefrontOrder.builder()
                   .orderId(orderId)
                   .organizationId(orgId)
                   // .branchId(branchId) // CRITICAL: This needs to be correctly determined
                   .patientId(request.getCustomerId())
                   .customerInfo(request.getCustomerInfo())
                   .shippingAddress(request.getShippingAddress())
                   .grandTotal(grandTotal)
                   .status("PENDING_PAYMENT") // Initial status
                   .createdAt(Timestamp.now())
                   .items(sale.getItems())
                   .build();

           log.info("Saving new StorefrontOrder with ID: {} for Org: {}", orderId, orgId);
           StorefrontOrder savedOrder = storefrontOrderRepository.save(order);
           log.info("Successfully created pending order: {}", savedOrder.getOrderId());

           return savedOrder;
       } catch (FeignException e) {
           String actualMessage = e.contentUTF8();
           throw new InventoryClientException(actualMessage);
       }
    }

    private GstType parseGstType(String value) {
        if (value == null || value.isBlank()) {
            return GstType.NON_GST;
        }
        try {
            return GstType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return GstType.NON_GST; // default fallback
        }
    }

   public CreateOrderResponse createPaymentOrder( CreateOrderRequest request) {

        return paymentServiceClient.createPaymentOrder(request);

   }

    public StorefrontProduct getProductById(String orgId, String productId) {
        StorefrontProduct product = storefrontProductRepository.findById(orgId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Storefront Product with ID " + productId + " not found for update."));
        return product;
    }

   /* public OrderDetailsDto getOrderDetails(String orgId, String orderId) {
        StorefrontOrder order = storefrontOrderRepository.findById(orgId,orderId)
         .orElseThrow(() -> new ResourceNotFoundException("Storefront order with ID " + orderId + " not found"));
        return mapToOrderDetailsDto(order);
    }*/

    public OrderDetailsDto getOrderDetails(String orgId, String orderId) {

        // 1️ Fetch order
        StorefrontOrder order = storefrontOrderRepository
                .findById(orgId, orderId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Storefront order with ID " + orderId + " not found"
                        )
                );

        List<SaleItem> items =
                Optional.ofNullable(order.getItems()).orElse(List.of());

        // 2️ Extract productIds
        List<String> productIds = items.stream()
                .map(SaleItem::getMedicineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 3️ Fetch StorefrontProducts (CMS collection)
        Map<String, StorefrontProduct> productMap =
                storefrontProductRepository
                        .findAllByOrganizationIdAndProductIdIn(orgId, productIds)
                        .stream()
                        .collect(Collectors.toMap(
                                StorefrontProduct::getProductId,
                                p -> p
                        ));

        // 4️ Calculate dynamic package details
        PackageDetails packageDetails =
                calculatePackageDetails(items, productMap);

        // 5️ Map to DTO
        return mapToOrderDetailsDto(order, packageDetails);
    }

    private PackageDetails calculatePackageDetails(
            List<SaleItem> items,
            Map<String, StorefrontProduct> productMap) {

        if (items == null || items.isEmpty()) {
            return new PackageDetails(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal maxLength = BigDecimal.ZERO;
        BigDecimal maxWidth = BigDecimal.ZERO;
        BigDecimal totalHeight = BigDecimal.ZERO;

        for (SaleItem item : items) {

            StorefrontProduct product =
                    productMap.get(item.getMedicineId());

            if (product == null) continue;

            BigDecimal quantity =
                    BigDecimal.valueOf(item.getQuantity());

            // -------- WEIGHT --------
            BigDecimal weight = BigDecimal.ZERO;

            if (product.getWeight() != null &&
                    product.getWeight().getValue() != null) {

                weight = product.getWeight().getValue();
            }

            totalWeight = totalWeight.add(
                    weight.multiply(quantity)
            );

            // -------- DIMENSIONS --------
            if (product.getDimensions() != null) {

                PhysicalDimensions dim =
                        product.getDimensions();

                BigDecimal length =
                        dim.getLength() != null
                                ? dim.getLength()
                                : BigDecimal.ZERO;

                BigDecimal width =
                        dim.getWidth() != null
                                ? dim.getWidth()
                                : BigDecimal.ZERO;

                BigDecimal height =
                        dim.getHeight() != null
                                ? dim.getHeight()
                                : BigDecimal.ZERO;

                maxLength = maxLength.max(length);
                maxWidth = maxWidth.max(width);

                totalHeight = totalHeight.add(
                        height.multiply(quantity)
                );
            }
        }

        return new PackageDetails(
                scale(totalWeight),
                scale(maxLength),
                scale(maxWidth),
                scale(totalHeight)
        );


    }


    private BigDecimal scale(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.setScale(2, RoundingMode.HALF_UP);
    }



    /**
     * Maps a StorefrontOrder domain model to an OrderDetailsDto.
     * This method contains the core mapping logic.
     *
     * @param storefrontOrder The domain model to map.
     * @return The DTO representation.
     */
    private OrderDetailsDto mapToOrderDetailsDto(StorefrontOrder storefrontOrder,PackageDetails packageDetails) {
        // --- Customer Name & Email Mapping ---
        // Assuming customerInfo map contains "name" and "email" keys
        String customerName = Optional.ofNullable(storefrontOrder.getCustomerInfo())
                .map(info -> info.get("name"))
                .orElse("N/A"); // Default or throw error if mandatory

        String customerEmail = Optional.ofNullable(storefrontOrder.getCustomerInfo())
                .map(info -> info.get("email"))
                .orElse("N/A"); // Default or throw error if mandatory

        // --- Customer Phone Mapping ---
        String customerPhone = Optional.ofNullable(storefrontOrder.getCustomerInfo())
                .map(info -> info.get("phone")) // <--- CHECK THIS KEY in Firestore!
                .orElse(""); // Or throw error if mandatory
        // --- Shipping Address Mapping ---
        // Assuming shippingAddress map contains "addressLine1", "addressLine2", "city", "pincode", "state" keys
        // You might need to adjust these keys based on your actual data in Firestore.
        String shippingAddressLine1 = Optional.ofNullable(storefrontOrder.getShippingAddress())
                .map(addr -> addr.get("street"))
                .orElse("");
        String shippingAddressLine2 = Optional.ofNullable(storefrontOrder.getShippingAddress())
                .map(addr -> addr.get("street1"))
                .orElse("");
        String shippingCity = Optional.ofNullable(storefrontOrder.getShippingAddress())
                .map(addr -> addr.get("city"))
                .orElse("");
        String shippingPincode = Optional.ofNullable(storefrontOrder.getShippingAddress())
                .map(addr -> addr.get("zip"))
                .orElse("");
        String shippingState = Optional.ofNullable(storefrontOrder.getShippingAddress())
                .map(addr -> addr.get("state"))
                .orElse("");


        // --- Items Mapping ---
        List<OrderDetailsDto.OrderItemDto> itemDtos = Optional.ofNullable(storefrontOrder.getItems())
                .orElse(List.of()) // Provide an empty list if items is null
                .stream()
                .map(this::mapStorefrontOrderItemToOrderItemDto)
                .collect(Collectors.toList());

        log.debug("Mapping StorefrontOrder {} to OrderDetailsDto", storefrontOrder.getOrderId());
        return OrderDetailsDto.builder()
                .orderId(storefrontOrder.getOrderId())
                .customerName(customerName)
                .customerEmail(customerEmail)
                .customerPhone(customerPhone)
                .paymentMethod(determinePaymentMethod(storefrontOrder.getPaymentId()))
                .totalOrderValue((int) Math.round(storefrontOrder.getGrandTotal()))
                .billingAddressLine1(shippingAddressLine1)
                .billingAddressLine2(shippingAddressLine2)
                .billingCity(shippingCity)
                .billingPincode(shippingPincode)
                .billingState(shippingState)
                .totalWeightKg(packageDetails.getTotalWeightKg())
                .packageLengthCm(packageDetails.getLengthCm())
                .packageBreadthCm(packageDetails.getWidthCm())
                .packageHeightCm(packageDetails.getHeightCm())
                .items(itemDtos)
                .build();
    }

    /**
     * Maps a single StorefrontOrderItem to an OrderDetailsDto.OrderItemDto.
     *
     * @param storefrontOrderItem The domain item to map.
     * @return The DTO item representation.
     */
    private OrderDetailsDto.OrderItemDto mapStorefrontOrderItemToOrderItemDto(SaleItem storefrontOrderItem) {


        log.trace("Mapping StorefrontOrderItem {} ()", storefrontOrderItem.getProductName());
        return OrderDetailsDto.OrderItemDto.builder()
                .name(storefrontOrderItem.getProductName())
                .name(
                        storefrontOrderItem.getProductName() != null && !storefrontOrderItem.getProductName().isBlank()
                                ? storefrontOrderItem.getProductName()
                                : "Product12345"
                )

                .quantity(storefrontOrderItem.getQuantity())

                .sku(
                       storefrontOrderItem.getSku() != null && !storefrontOrderItem.getSku().isBlank()
                                ? storefrontOrderItem.getSku()
                                : "SKU12345"
                )
                .hsnCode(Integer.valueOf(storefrontOrderItem.getHsn() != null && !storefrontOrderItem.getHsn().isBlank()
                        ? storefrontOrderItem.getHsn()
                        : "12345")
                )
                .unitPrice(storefrontOrderItem.getMrpPerItem())
                .build();
    }

    /**
     * Placeholder method to determine payment method.
     * You would replace this with actual logic based on your payment integration.
     */
    private String determinePaymentMethod(String paymentId) {
        // Example: If paymentId is "COD", return "COD", otherwise "Prepaid"
        if ("COD_IDENTIFIER".equals(paymentId)) { // Replace "COD_IDENTIFIER" with your actual COD payment ID or mechanism
            return "COD";
        }
        return "Prepaid";
    }

    private Double round(Double value) {
        if (value == null) return 0.0;
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Placeholder method to calculate total weight.
     * You'll need to fetch product weight from a product catalog or include it in StorefrontOrderItem.
     */
    private Double calculateTotalWeight(List<SaleItem> items) {
        if (items == null || items.isEmpty()) {
            return 0.0;
        }
        // Example: Assuming each item has a default weight of 0.1 kg if not specified
        // In a real scenario, you'd fetch product-specific weights.
        return items.stream()
                .mapToDouble(item -> item.getQuantity() * 0.1) // 0.1 kg per unit as a placeholder
                .sum();
    }

    public List<StorefrontProduct> productByIds(String organizationId, List<String> productIds) {
        return productIds.stream()
                .map(id -> storefrontProductRepository.findById(organizationId, id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public void deleteProduct(String orgId, String productId) {
        storefrontProductRepository.deleteByProductId(orgId, productId);
        log.info("delete StorefrontOrder with ID: {} for Org: {}", productId, orgId);
    }

    public CursorPage<ProductWithStockResponse> getAvailableProductsPaged(
            String orgId,
            String categoryId,
            int pageSize,
            String startAfter
    ) {

        // 1️⃣ Fetch CMS products using cursor pagination
        CursorPage<StorefrontProduct> productPage =
                storefrontProductRepository.findAllVisible(
                        orgId,
                        categoryId,
                        pageSize,
                        startAfter
                );

        List<StorefrontProduct> products = productPage.getContent();

        if (products.isEmpty()) {
            return new CursorPage<>(
                    List.of(),
                    null,
                    false
            );
        }

        // 2️⃣ Extract productIds
        List<String> productIds = products.stream()
                .map(StorefrontProduct::getProductId)
                .toList();

        List<Branch> branches = branchRepository.findAllByOrganizationId(orgId);

        StockCountDetails stockCountDetails = StockCountDetails.builder()
                .orgId(orgId)
                .branchId(branches.get(0).getBranchId())
                .productIds(productIds)
                .build();

        // 3️⃣ Batch inventory call
        Map<String, Integer> stockMap =
                inventoryService.getStockBatch(stockCountDetails);

        // 4️⃣ Merge CMS + Inventory
        List<ProductWithStockResponse> responseList = products.stream()
                .map(product -> mapToProductWithStock(product, stockMap))
                .toList();

        // 5️⃣ Return correct CursorPage
        return new CursorPage<>(
                responseList,
                productPage.getNextPageToken(),
                productPage.isHasNext() // or productPage.isHasNext()
        );
    }
    private ProductWithStockResponse mapToProductWithStock(
            StorefrontProduct product,
            Map<String, Integer> stockMap
    ) {

        int stock = stockMap.getOrDefault(product.getProductId(), 0);

        return ProductWithStockResponse.builder()
                .productId(product.getProductId())
                .productName(product.getProductName())
                .categoryName(product.getCategoryName())
                .mrp(product.getMrp())
                .slug(product.getSlug())
                .images(product.getImages())
                .stockLevel(stock)
                .inStock(stock > 0)
                .lowStock(stock > 0 && stock <= 5)
                .build();
    }
}





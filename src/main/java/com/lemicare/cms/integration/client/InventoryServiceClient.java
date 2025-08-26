package com.lemicare.cms.integration.client;

import com.cosmicdoc.common.model.Medicine;
import com.lemicare.cms.Exception.ResourceNotFoundException;
import com.lemicare.cms.Exception.ServiceCommunicationException;
import com.lemicare.cms.dto.request.MedicineMasterData;
import com.lemicare.cms.dto.request.MedicineStockData;
import com.lemicare.cms.dto.response.MedicineStockDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

/**
 * A robust client for making REST API calls to the Inventory Service.
 * It encapsulates URL construction, request execution, and error translation.
 */
@Component

public class InventoryServiceClient {

    private final RestTemplate restTemplate;

    @Value("${services.inventory.url}")
    private String inventoryServiceBaseUrl;

    public InventoryServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Fetches the full master details for a single medicine from the inventory service.
     *
     * @param medicineId The ID of the medicine to fetch.
     * @return A DTO with the medicine's master data.
     */
    public Medicine getMedicineDetails(String medicineId) {
        String url = inventoryServiceBaseUrl + "/masters/medicines/{medicineId}";
        Map<String, String> uriVariables = Map.of("medicineId", medicineId);

        try {
            ResponseEntity<Medicine> response = restTemplate.getForEntity(url, Medicine.class, uriVariables);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // Translate the 404 from the downstream service into our own specific exception.
            throw new ResourceNotFoundException("Medicine master data with ID " + medicineId + " not found in inventory.");
        } catch (Exception e) {
            // Wrap any other exception (500, network error) in a generic service communication error.
            throw new ServiceCommunicationException("Could not communicate with the Inventory Service.", e);
        }
    }

    /**
     * Fetches the current stock level for a single medicine from the inventory service.
     *
     * @param medicineId The ID of the medicine to check stock for.
     * @return A DTO with the medicine's stock data.
     */
    public MedicineStockData getMedicineStock(String medicineId) {
        String url = inventoryServiceBaseUrl + "/masters/medicines/{medicineId}/stock"; // Assuming this endpoint exists
        Map<String, String> uriVariables = Map.of("medicineId", medicineId);

        try {
            ResponseEntity<MedicineStockData> response = restTemplate.getForEntity(url, MedicineStockData.class, uriVariables);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Medicine with ID " + medicineId + " not found in inventory.");
        } catch (Exception e) {
            throw new ServiceCommunicationException("Could not communicate with the Inventory Service.", e);
        }
    }
}

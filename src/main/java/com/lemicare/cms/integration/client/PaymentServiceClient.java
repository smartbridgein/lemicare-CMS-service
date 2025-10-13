package com.lemicare.cms.integration.client;

import com.lemicare.cms.config.FeignConfig;

import com.lemicare.cms.dto.request.CreateOrderRequest;
import com.lemicare.cms.dto.response.CreateOrderResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(
        name = "payment-service",
        url = "${services.payment-service.url}",
        configuration = FeignConfig.class
)

public interface  PaymentServiceClient{

    @PostMapping("/api/internal/payments/create-order")
    CreateOrderResponse createPaymentOrder( CreateOrderRequest request);

}


package com.lemicare.cms.config;

import com.cosmicdoc.common.repository.StorefrontCategoryRepository;
import com.cosmicdoc.common.repository.StorefrontOrderRepository;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.repository.impl.StorefrontCategoryRepositoryImpl;
import com.cosmicdoc.common.repository.impl.StorefrontOrderRepositoryImpl;
import com.cosmicdoc.common.repository.impl.StorefrontProductRepositoryImpl;
import com.google.cloud.firestore.Firestore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntityConfiguration {

    Firestore firestore;

    @Bean
     StorefrontProductRepository storefrontProductRepository (Firestore firestore) {
        return new StorefrontProductRepositoryImpl(firestore);
    }

    @Bean
    StorefrontCategoryRepository storefrontCategoryRepository (Firestore firestore) {
        return new StorefrontCategoryRepositoryImpl(firestore);
    }

    @Bean
    StorefrontOrderRepository storefrontOrderRepository (Firestore firestore) {
        return new StorefrontOrderRepositoryImpl(firestore);
    }
}

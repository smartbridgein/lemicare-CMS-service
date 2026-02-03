package com.lemicare.cms.config;

import com.cosmicdoc.common.repository.*;
import com.cosmicdoc.common.repository.impl.*;
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
    @Bean
    TaxProfileRepository taxProfileRepository (Firestore firestore) {
        return new TaxProfileRepositoryImpl(firestore);
    }

    @Bean
    BranchRepository branchRepository (Firestore firestore) {
        return new BranchRepositoryImpl(firestore);
    }
}

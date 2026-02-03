package com.lemicare.cms.config;

import com.lemicare.cms.filter.TenantFilter;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}")
    private String jwtSecretKey;
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer}")
    private String jwtIssuer;

    @Value("${spring.security.oauth2.resourceserver.jwt.audience}")
    private String jwtAudience;

    private final TenantFilter tenantFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/public/**").permitAll() // Allow all public endpoints
                        .requestMatchers("/test/**").permitAll() // Allow test endpoints for Firestore testing
                        .requestMatchers("/error").permitAll()
                        // Allow error pages
                        .anyRequest().permitAll() // For now, allow everything for debugging
                )

                // Configure resource server to use our custom converter
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                // Add your custom filter to the chain, ensuring it runs after authentication
                .addFilterAfter(tenantFilter, BasicAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Configures the JwtDecoder for validating incoming JWTs.
     * It uses the shared secret key for signature verification and enforces
     * validation of the issuer and audience claims.
     *
     * @return A configured JwtDecoder bean.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKey hmacKey = Keys.hmacShaKeyFor(jwtSecretKey.getBytes(StandardCharsets.UTF_8));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey).build();

        // --- Custom Claim Validation (Issuer and Audience) ---
        // Build a list of validators to apply to the JWT.
        // NimbusJwtDecoder directly accepts an OAuth2TokenValidator,
        // which can be constructed as a DelegatingOAuth2TokenValidator for chaining.
        // We use the public DelegatingOAuth2TokenValidator in `org.springframework.security.oauth2.core`.
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();

        // Add the default timestamp validator (checks exp, nbf, iat)
        validators.add(new JwtTimestampValidator());

        // Custom validator for issuer
        validators.add(jwt -> {
            if (!jwt.getIssuer().toString().equals(jwtIssuer)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "The issuer '" + jwt.getIssuer() + "' is not trusted.", null));
            }
            return OAuth2TokenValidatorResult.success();
        });

        // Custom validator for audience
        validators.add(jwt -> {
            // The 'aud' claim is a list of strings. Check if our expected audience is in that list.
            if (jwt.getAudience() == null || !jwt.getAudience().contains(jwtAudience)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "The audience '" + jwt.getAudience() + "' is not valid for this resource server. Expected: " + jwtAudience, null));
            }
            return OAuth2TokenValidatorResult.success();
        });

        // Chain all validators together using the public DelegatingOAuth2TokenValidator
        // This is the correct way to apply multiple custom validators.
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(validators));

        return decoder;
    }

    /**
     * Configures the JwtAuthenticationConverter to extract roles (authorities)
     * from the 'scope' claim in the JWT.
     *
     * @return A configured JwtAuthenticationConverter bean.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("scope");
        grantedAuthoritiesConverter.setAuthorityPrefix(""); // No "SCOPE_" prefix

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return jwtConverter;
    }
}

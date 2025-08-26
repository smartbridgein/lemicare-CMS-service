package com.lemicare.cms.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Configuration class for initializing the Firebase Admin SDK.
 * <p>
 * This entire configuration is conditional and will ONLY be activated when the
 * active Spring profile is NOT 'local'. This is controlled by the @Profile("!local")
 * annotation. It allows the application to start for local development without
 * requiring a live Firebase connection or a 'google-services.json' file.
 */
@Configuration
@Profile("!local") // CRITICAL: This bean will not be created if the profile is 'local'.
public class FirebaseConfig {

    // Injects the path to the service account key from application.yml
    @Value("${app.firebase.service-account-path}")
    private String serviceAccountPath;

    /**
     * Initializes the Firebase Admin SDK as a Spring Bean.
     * <p>
     * It robustly checks if an app has already been initialized to prevent errors
     * during application restarts or in certain testing scenarios.
     *
     * @return The initialized FirebaseApp instance.
     * @throws IOException if the credentials file cannot be found or read.
     */
    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            // Load the service account credentials from the project's classpath (src/main/resources)
            InputStream serviceAccount = new ClassPathResource(serviceAccountPath).getInputStream();

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            return FirebaseApp.initializeApp(options);
        } else {
            // If the app is already initialized, return the existing instance.
            return FirebaseApp.getInstance();
        }
    }

    /**
     * Provides the primary Firestore database instance as a Spring Bean.
     * <p>
     * By defining this as a bean, Spring's dependency injection container can
     * automatically provide the Firestore object to any other component that
     * needs it (e.g., your repository implementations). Spring automatically
     * injects the 'firebaseApp' bean created above.
     *
     * @param firebaseApp The initialized FirebaseApp bean.
     * @return The configured Firestore database instance.
     */
    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }
}

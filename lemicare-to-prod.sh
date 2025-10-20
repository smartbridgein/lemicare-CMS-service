#!/bin/bash
# Cloud Run deployment script for lemicare-cms-service
# Target project: lemicareprod

set -euo pipefail # Exit immediately if a command exits with a non-zero status. Pipefail ensures pipeline errors are caught.

# =========================
# Configuration for THIS SERVICE (lemicare-cms-service)
# =========================
PROJECT_ID="lemicareprod"
REGION="asia-south1"

# --- SERVICE SPECIFIC CONFIGURATION ---
SERVICE_NAME="lemicare-cms-service" # Correct Service Name for Cloud Run
SERVICE_PORT=8086                    # Matches the hardcoded port in pom.xml
SERVICE_MEMORY="512Mi"               # Adjust memory as needed
SERVICE_CPU="1"                      # Adjust CPU as needed
ALLOW_UNAUTHENTICATED=true           # Set to false if this service should NOT be publicly accessible
COMMON_LIB_PATH="../lemicare-common" # Path to your common library relative to *this service's root*

# Additional environment variables specific to this service
# If no additional vars, set to an empty string: ADDITIONAL_ENV_VARS=""
ADDITIONAL_ENV_VARS="SPRING_PROFILES_ACTIVE=cloud,ALLOWED_ORIGINS=*" # As per your previous script

# Image tag - derived from SERVICE_NAME
IMAGE="gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest"

# =========================
# Step 1: Validate common library path
# =========================
echo "=== Validating lemicare-common library path ==="
if [ ! -d "$COMMON_LIB_PATH" ]; then
  echo "❌ Error: lemicare-common library not found at $COMMON_LIB_PATH"
  echo "Please ensure the path is correct or skip common lib build if not applicable."
  exit 1
fi

# =========================
# Step 2: Build common library (if needed)
# =========================
echo "=== Building and installing lemicare-common library ==="
(
  cd "$COMMON_LIB_PATH"
  echo "Current directory: $(pwd)"
  mvn clean install -DskipTests
)
echo "✅ lemicare-common built successfully."

# =========================
# Step 3: Build this service
# =========================
echo "=== Building ${SERVICE_NAME} service ==="
# No specific 'mvn clean package' needed if Jib is handling the build,
# but it's good for local verification. Jib compile is sufficient for image.
mvn clean compile -DskipTests # 'compile' is sufficient for Jib 'jib:build'
echo "✅ ${SERVICE_NAME} compiled successfully."

# =========================
# Step 4: Build and push container image using Jib Maven plugin
# =========================
echo "=== Building and pushing container image to GCR using Jib ==="

# Export PROJECT_ID environment variable for Jib to use in pom.xml
export PROJECT_ID=${PROJECT_ID}

# Jib configuration from pom.xml is:
# <to><image>gcr.io/${env.PROJECT_ID}/lemicare-payment-service:latest</image></to>
# THIS IS INCORRECT AND NEEDS TO BE OVERRIDDEN OR FIXED IN POM.XML
# We will override it here using -Djib.to.image command line argument.
# It's better to fix the pom.xml directly if this is not a temporary override.

# If you prefer to fix the pom.xml, change:
# <to><image>gcr.io/${env.PROJECT_ID}/lemicare-payment-service:latest</image></to>
# To:
# <to><image>gcr.io/${env.PROJECT_ID}/lemicare-cms-service:latest</image></to>
# And then you can simply use: mvn jib:build -Djib.to.credHelper=gcloud

# For now, we override the incorrect image name from pom.xml directly in the command
if mvn jib:build -Djib.to.image="${IMAGE}" -Djib.to.credHelper=gcloud; then
  echo "✅ Container image built and pushed successfully: ${IMAGE}"
else
  echo "❌ Container image build/push failed. Check Jib configuration and GCR access."
  exit 1
fi

# =========================
# Step 5: Deploy the container to Cloud Run
# =========================
echo "=== Deploying ${SERVICE_NAME} to Cloud Run ==="

DEPLOY_COMMAND=(
    gcloud run deploy "${SERVICE_NAME}"
    --image="${IMAGE}"
    --region="${REGION}"
    --platform=managed
    --project="${PROJECT_ID}"
    --memory="${SERVICE_MEMORY}"
    --cpu="${SERVICE_CPU}"
    --port="${SERVICE_PORT}" # <--- This now correctly matches the port hardcoded in pom.xml
)

# Add --allow-unauthenticated if needed
if [ "$ALLOW_UNAUTHENTICATED" = true ] ; then
    DEPLOY_COMMAND+=(--allow-unauthenticated)
fi

# Add additional environment variables if provided
if [ -n "$ADDITIONAL_ENV_VARS" ] ; then
    # --set-env-vars overrides previous settings, so combine if needed
    # Jib already sets SPRING_PROFILES_ACTIVE=cloud. This will override it again with the same value.
    DEPLOY_COMMAND+=(--set-env-vars="${ADDITIONAL_ENV_VARS}")
fi

# Execute the deployment command
echo "Executing: ${DEPLOY_COMMAND[@]}"
if "${DEPLOY_COMMAND[@]}"; then
  echo "✅ Service deployed successfully to Cloud Run."
else
  echo "❌ Service deployment failed. Check Cloud Run logs for details."
  exit 1
fi

# =========================
# Done!
# =========================
echo "🎉 Deployment for ${SERVICE_NAME} completed."
echo "View logs at: https://console.cloud.google.com/logs/viewer?project=${PROJECT_ID}&resource=cloud_run_service/service_name/${SERVICE_NAME}&minLogLevel=ERROR"
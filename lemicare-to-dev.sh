#!/bin/bash
# Cloud Run deployment script for payment-service
# Target project: lemicare-prod

set -euo pipefail

# =========================
# Configuration
# =========================
PROJECT_ID="lemicaredev"
REGION="asia-south1"
SERVICE_NAME="lemicare-cms-service"
IMAGE="gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest"
COMMON_LIB_PATH="../lemicare-common"

# =========================
# Step 1: Build common library
# =========================
echo "=== Building and installing lemicare-common library ==="

if [ ! -d "$COMMON_LIB_PATH" ]; then
  echo "❌ Error: lemicare-common library not found at $COMMON_LIB_PATH"
  exit 1
fi

(
  cd "$COMMON_LIB_PATH"
  mvn clean install -DskipTests
)

# =========================
# Step 2: Build payment service
# =========================
echo "=== Building ${SERVICE_NAME} service ==="
mvn clean package -DskipTests

# Detect the final JAR dynamically (ignore .jar.original backup)
ARTIFACT=$(find target -maxdepth 1 -type f -name "*.jar" ! -name "*.jar.original" | head -n 1 || true)

if [ -z "$ARTIFACT" ]; then
  echo "❌ No runnable JAR file found in target/. Aborting deployment."
  exit 1
fi

echo "✅ Found artifact: $ARTIFACT"

# =========================
# Step 3: Build and deploy using Jib Maven plugin
# =========================
echo "=== Building and deploying to Cloud Run using Jib Maven plugin ==="

# Export PROJECT_ID environment variable for Jib to use in pom.xml
export PROJECT_ID=${PROJECT_ID}

# Build and push image using Jib Maven plugin
if mvn compile jib:build -Djib.to.image=gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest -Djib.to.credHelper=gcloud; then
  echo "✅ Container image built and pushed successfully"
else
  echo "❌ Container image build/push failed"
  exit 1
fi

# Deploy the container to Cloud Run
if gcloud run deploy ${SERVICE_NAME} \
    --image=gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest \
    --region=${REGION} \
    --platform=managed \
    --allow-unauthenticated \
    --project=${PROJECT_ID} \
    --memory=512Mi \
    --cpu=1 \
    --port=8087 \
    --set-env-vars=SPRING_PROFILES_ACTIVE=cloud,ALLOWED_ORIGINS=https://healthcare-app-145837205370.asia-south1.run.app; then
  echo "✅ Service deployed successfully"
else
  echo "❌ Service deployment failed"
  exit 1
fi

# =========================
# Done!
# =========================
echo "🎉 Done! Your ${SERVICE_NAME} is deployed."
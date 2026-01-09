#!/bin/bash
#
# Local validation script for Play Store deployment
# This simulates the GitHub Actions workflow locally
#
# Prerequisites:
# 1. Place your keystore file at: app/release.keystore
# 2. Place your Play Store service account JSON at: app/play-credentials.json
# 3. Set the following environment variables (or edit this script):
#    - KEYSTORE_PASSWORD
#    - KEY_ALIAS
#    - KEY_PASSWORD
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Play Store Deployment Local Validation ===${NC}"

# Check for required files
echo -e "\n${YELLOW}Checking required files...${NC}"

if [ ! -f "app/release.keystore" ]; then
    echo -e "${RED}ERROR: app/release.keystore not found${NC}"
    echo "Please place your keystore file at app/release.keystore"
    exit 1
else
    echo -e "${GREEN}✓ app/release.keystore found${NC}"
fi

if [ ! -f "app/play-credentials.json" ]; then
    echo -e "${RED}ERROR: app/play-credentials.json not found${NC}"
    echo "Please place your Play Store service account JSON at app/play-credentials.json"
    exit 1
else
    echo -e "${GREEN}✓ app/play-credentials.json found${NC}"
fi

# Check for required environment variables
echo -e "\n${YELLOW}Checking environment variables...${NC}"

if [ -z "$KEYSTORE_PASSWORD" ]; then
    echo -e "${YELLOW}KEYSTORE_PASSWORD not set. Please enter it:${NC}"
    read -s KEYSTORE_PASSWORD
    export KEYSTORE_PASSWORD
fi
echo -e "${GREEN}✓ KEYSTORE_PASSWORD set${NC}"

if [ -z "$KEY_ALIAS" ]; then
    echo -e "${YELLOW}KEY_ALIAS not set. Please enter it:${NC}"
    read KEY_ALIAS
    export KEY_ALIAS
fi
echo -e "${GREEN}✓ KEY_ALIAS set${NC}"

if [ -z "$KEY_PASSWORD" ]; then
    echo -e "${YELLOW}KEY_PASSWORD not set. Please enter it:${NC}"
    read -s KEY_PASSWORD
    export KEY_PASSWORD
fi
echo -e "${GREEN}✓ KEY_PASSWORD set${NC}"

# Export all required environment variables
export KEYSTORE_FILE="release.keystore"
export PLAY_CREDENTIALS_FILE="play-credentials.json"
export PLAY_STORE_SERVICE_ACCOUNT_JSON="true"

echo -e "\n${YELLOW}Environment variables configured:${NC}"
echo "  KEYSTORE_FILE=$KEYSTORE_FILE"
echo "  PLAY_CREDENTIALS_FILE=$PLAY_CREDENTIALS_FILE"
echo "  PLAY_STORE_SERVICE_ACCOUNT_JSON=$PLAY_STORE_SERVICE_ACCOUNT_JSON"
echo "  KEYSTORE_PASSWORD=****"
echo "  KEY_ALIAS=$KEY_ALIAS"
echo "  KEY_PASSWORD=****"

# Parse command line arguments
TRACK="${1:-internal}"
DRY_RUN="${2:-true}"

echo -e "\n${YELLOW}Track: ${TRACK}${NC}"

# Grant execute permission
chmod +x gradlew

if [ "$DRY_RUN" = "true" ]; then
    echo -e "\n${YELLOW}Running in DRY RUN mode (build only, no publish)${NC}"
    echo -e "${YELLOW}To actually publish, run: $0 ${TRACK} false${NC}\n"
    
    # Just build the bundle to validate configuration
    ./gradlew bundleRelease -x lintVitalRelease --no-daemon
    
    if [ $? -eq 0 ]; then
        echo -e "\n${GREEN}=== Build successful! ===${NC}"
        echo -e "AAB location: app/build/outputs/bundle/release/app-release.aab"
        echo -e "\nTo publish to Play Store, run:"
        echo -e "  $0 ${TRACK} false"
    fi
else
    echo -e "\n${YELLOW}Running PUBLISH mode - will upload to Play Store ${TRACK} track${NC}"
    echo -e "${RED}Press Ctrl+C within 5 seconds to cancel...${NC}"
    sleep 5
    
    ./gradlew publishBundle --track=$TRACK -x lintVitalRelease --no-daemon
    
    if [ $? -eq 0 ]; then
        echo -e "\n${GREEN}=== Successfully published to ${TRACK} track! ===${NC}"
    fi
fi

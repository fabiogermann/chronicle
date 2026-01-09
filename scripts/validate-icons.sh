#!/opt/homebrew/bin/bash

# Chronicle App Icon Validator
# Validates Android app icons meet dimension requirements and provides diagnostics

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RES_DIR="$PROJECT_ROOT/app/src/main/res"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Error/success counters
ERRORS=0
WARNINGS=0
SUCCESS=0

echo "========================================"
echo "Chronicle App Icon Validator"
echo "========================================"
echo ""

# Define expected dimensions for adaptive icons (foreground, background, monochrome)
declare -A ADAPTIVE_SIZES=(
    ["mdpi"]="108"
    ["hdpi"]="162"
    ["xhdpi"]="216"
    ["xxhdpi"]="324"
    ["xxxhdpi"]="432"
)

# Define expected dimensions for legacy icons
declare -A LEGACY_SIZES=(
    ["mdpi"]="48"
    ["hdpi"]="72"
    ["xhdpi"]="96"
    ["xxhdpi"]="144"
    ["xxxhdpi"]="192"
)

# Function to check if a file exists
check_file_exists() {
    local file="$1"
    if [ -f "$file" ]; then
        return 0
    else
        return 1
    fi
}

# Function to get image dimensions using sips (macOS)
get_dimensions() {
    local file="$1"
    if command -v sips &> /dev/null; then
        local width=$(sips -g pixelWidth "$file" 2>/dev/null | grep "pixelWidth:" | awk '{print $2}')
        local height=$(sips -g pixelHeight "$file" 2>/dev/null | grep "pixelHeight:" | awk '{print $2}')
        echo "${width}x${height}"
    elif command -v identify &> /dev/null; then
        # ImageMagick fallback
        identify -format "%wx%h" "$file" 2>/dev/null
    else
        echo "unknown"
    fi
}

# Function to check icon dimensions
check_icon() {
    local density="$1"
    local icon_type="$2"
    local expected_size="$3"
    local file_path="$4"
    
    if ! check_file_exists "$file_path"; then
        echo -e "${RED}✗${NC} Missing: mipmap-${density}/${icon_type}"
        ((ERRORS++))
        return 1
    fi
    
    local dimensions=$(get_dimensions "$file_path")
    local expected="${expected_size}x${expected_size}"
    
    if [ "$dimensions" = "$expected" ]; then
        echo -e "${GREEN}✓${NC} mipmap-${density}/${icon_type}: ${dimensions}"
        ((SUCCESS++))
        return 0
    elif [ "$dimensions" = "unknown" ]; then
        echo -e "${YELLOW}?${NC} mipmap-${density}/${icon_type}: Unable to determine dimensions"
        ((WARNINGS++))
        return 1
    else
        echo -e "${RED}✗${NC} mipmap-${density}/${icon_type}: ${dimensions} (expected ${expected})"
        ((ERRORS++))
        return 1
    fi
}

# Check adaptive icon configuration
echo -e "${BLUE}Checking Adaptive Icon XML:${NC}"
ADAPTIVE_XML="$RES_DIR/mipmap-anydpi-v26/ic_launcher.xml"
if check_file_exists "$ADAPTIVE_XML"; then
    echo -e "${GREEN}✓${NC} ic_launcher.xml exists"
    
    # Check if it references the correct drawables
    if grep -q "@mipmap/ic_launcher_background" "$ADAPTIVE_XML" && \
       grep -q "@mipmap/ic_launcher_foreground" "$ADAPTIVE_XML"; then
        echo -e "${GREEN}✓${NC} Adaptive icon references correct layers"
        ((SUCCESS++))
    else
        echo -e "${RED}✗${NC} Adaptive icon missing background or foreground reference"
        ((ERRORS++))
    fi
    
    if grep -q "@mipmap/ic_launcher_monochrome" "$ADAPTIVE_XML"; then
        echo -e "${GREEN}✓${NC} Monochrome icon configured (Android 13+)"
        ((SUCCESS++))
    else
        echo -e "${YELLOW}⚠${NC} Monochrome icon not configured (optional for Android 13+)"
        ((WARNINGS++))
    fi
else
    echo -e "${RED}✗${NC} Missing adaptive icon configuration"
    ((ERRORS++))
fi
echo ""

# Check adaptive icon layers (foreground, background, monochrome)
echo -e "${BLUE}Checking Adaptive Icon Foreground Layers:${NC}"
for density in "${!ADAPTIVE_SIZES[@]}"; do
    size="${ADAPTIVE_SIZES[$density]}"
    file="$RES_DIR/mipmap-${density}/ic_launcher_foreground.png"
    check_icon "$density" "ic_launcher_foreground.png" "$size" "$file"
done
echo ""

echo -e "${BLUE}Checking Adaptive Icon Background Layers:${NC}"
for density in "${!ADAPTIVE_SIZES[@]}"; do
    size="${ADAPTIVE_SIZES[$density]}"
    file="$RES_DIR/mipmap-${density}/ic_launcher_background.png"
    check_icon "$density" "ic_launcher_background.png" "$size" "$file"
done
echo ""

echo -e "${BLUE}Checking Monochrome Icon Layers:${NC}"
for density in "${!ADAPTIVE_SIZES[@]}"; do
    size="${ADAPTIVE_SIZES[$density]}"
    file="$RES_DIR/mipmap-${density}/ic_launcher_monochrome.png"
    check_icon "$density" "ic_launcher_monochrome.png" "$size" "$file"
done
echo ""

# Check legacy icons
echo -e "${BLUE}Checking Legacy Icons (backward compatibility):${NC}"
for density in "${!LEGACY_SIZES[@]}"; do
    size="${LEGACY_SIZES[$density]}"
    file="$RES_DIR/mipmap-${density}/ic_launcher.png"
    check_icon "$density" "ic_launcher.png" "$size" "$file"
done
echo ""

# Check for round icons (optional)
echo -e "${BLUE}Checking Round Icons (optional):${NC}"
ROUND_FOUND=0
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    file="$RES_DIR/mipmap-${density}/ic_launcher_round.png"
    if check_file_exists "$file"; then
        echo -e "${GREEN}✓${NC} mipmap-${density}/ic_launcher_round.png exists"
        ROUND_FOUND=1
    fi
done
if [ $ROUND_FOUND -eq 0 ]; then
    echo -e "${YELLOW}⚠${NC} No round icons found (optional)"
fi
echo ""

# Advanced diagnostics: Check if icon content is properly sized
echo -e "${BLUE}Advanced Diagnostics:${NC}"

# For adaptive icons, the safe zone is 66dp centered within 108dp canvas
# This means 21dp padding on all sides
# We'll check the xxxhdpi version (432x432) - safe zone should be ~264x264 centered

XXXHDPI_FOREGROUND="$RES_DIR/mipmap-xxxhdpi/ic_launcher_foreground.png"
if check_file_exists "$XXXHDPI_FOREGROUND" && command -v identify &> /dev/null; then
    echo -e "${YELLOW}ℹ${NC} Analyzing icon artwork size (xxxhdpi foreground)..."
    
    # Use ImageMagick to analyze the non-transparent area
    BOUNDS=$(identify -format "%@" "$XXXHDPI_FOREGROUND" 2>/dev/null || echo "")
    
    if [ -n "$BOUNDS" ]; then
        echo -e "   Non-transparent bounds: $BOUNDS"
        
        # Extract width and height from bounds (format: WIDTHxHEIGHT+X+Y)
        CONTENT_SIZE=$(echo "$BOUNDS" | cut -d'+' -f1)
        CONTENT_WIDTH=$(echo "$CONTENT_SIZE" | cut -d'x' -f1)
        CONTENT_HEIGHT=$(echo "$CONTENT_SIZE" | cut -d'x' -f2)
        
        # Safe zone for xxxhdpi: 264x264 (66dp * 4)
        SAFE_ZONE_MIN=264
        CANVAS_SIZE=432
        
        if [ "$CONTENT_WIDTH" -lt "$SAFE_ZONE_MIN" ] || [ "$CONTENT_HEIGHT" -lt "$SAFE_ZONE_MIN" ]; then
            echo -e "${RED}✗${NC} Icon artwork (${CONTENT_SIZE}) is smaller than safe zone (${SAFE_ZONE_MIN}x${SAFE_ZONE_MIN})"
            echo -e "${RED}   THIS IS LIKELY THE CAUSE OF THE SMALL ICON ISSUE!${NC}"
            echo -e "   Recommendation: Scale up the artwork to fill at least 66% of the canvas"
            ((ERRORS++))
        elif [ "$CONTENT_WIDTH" -lt 350 ] || [ "$CONTENT_HEIGHT" -lt 350 ]; then
            echo -e "${YELLOW}⚠${NC} Icon artwork (${CONTENT_SIZE}) is usable but could be larger"
            echo -e "   Recommendation: Consider scaling to ~350x350 for better visual impact"
            ((WARNINGS++))
        else
            echo -e "${GREEN}✓${NC} Icon artwork size (${CONTENT_SIZE}) is appropriate"
            ((SUCCESS++))
        fi
    else
        echo -e "${YELLOW}⚠${NC} Could not analyze artwork bounds (ImageMagick required)"
        ((WARNINGS++))
    fi
elif ! command -v identify &> /dev/null; then
    echo -e "${YELLOW}⚠${NC} ImageMagick not installed - skipping artwork size analysis"
    echo -e "   Install with: brew install imagemagick"
    ((WARNINGS++))
fi
echo ""

# Summary
echo "========================================"
echo "Summary:"
echo "========================================"
echo -e "${GREEN}✓ Passed:${NC} $SUCCESS"
echo -e "${YELLOW}⚠ Warnings:${NC} $WARNINGS"
echo -e "${RED}✗ Errors:${NC} $ERRORS"
echo ""

if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}Validation FAILED - please fix the errors above${NC}"
    exit 1
elif [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}Validation PASSED with warnings${NC}"
    exit 0
else
    echo -e "${GREEN}Validation PASSED - all checks OK!${NC}"
    exit 0
fi

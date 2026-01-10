#!/bin/bash

# Play Store Graphics Generator for Chronicle Epilogue
# This script generates all required graphics for Google Play Store listing
#
# Requirements:
#   - ImageMagick (for image processing)
#   - Inkscape (for SVG to PNG conversion)
#
# Usage: ./scripts/generate-playstore-graphics.sh

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Directories (PROJECT_ROOT needs to be defined before the config section above)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Play Store metadata configuration files
# These files contain the app name and description used in the Play Store listing
PLAYSTORE_CONFIG_DIR="$PROJECT_ROOT/playstore/default"
TITLE_FILE="$PLAYSTORE_CONFIG_DIR/title.txt"
SHORT_DESC_FILE="$PLAYSTORE_CONFIG_DIR/short-description.txt"

# Default fallback values if config files are missing
DEFAULT_APP_NAME="Chronicle"
DEFAULT_APP_DESCRIPTION="Audiobook player for Plex"

IMAGES_DIR="$PROJECT_ROOT/images"
SCREENSHOTS_DIR="$IMAGES_DIR/screenshots"
LOGO_DIR="$IMAGES_DIR/logo"
PLAYSTORE_DIR="$PROJECT_ROOT/playstore/graphics"
TEMP_DIR="$PROJECT_ROOT/.tmp-graphics"

# Output directories
FEATURE_GRAPHIC_DIR="$PLAYSTORE_DIR/featureGraphic"
PHONE_SCREENSHOTS_DIR="$PLAYSTORE_DIR/phoneScreenshots"

# Print functions
print_header() {
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_info() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_step() {
    echo -e "${BLUE}→${NC} $1"
}

# Load Play Store metadata from config files
load_metadata() {
    print_header "Loading Play Store Metadata"
    
    # Read app name from title.txt
    if [ -f "$TITLE_FILE" ]; then
        APP_NAME=$(cat "$TITLE_FILE" | tr -d '\n\r')
        print_info "Loaded app name from playstore/default/title.txt: \"$APP_NAME\""
    else
        APP_NAME="$DEFAULT_APP_NAME"
        print_warning "Title file not found at $TITLE_FILE"
        print_warning "Using default app name: \"$APP_NAME\""
    fi
    
    # Read description from short-description.txt
    if [ -f "$SHORT_DESC_FILE" ]; then
        APP_DESCRIPTION=$(cat "$SHORT_DESC_FILE" | tr -d '\n\r')
        print_info "Loaded description from playstore/default/short-description.txt: \"$APP_DESCRIPTION\""
    else
        APP_DESCRIPTION="$DEFAULT_APP_DESCRIPTION"
        print_warning "Short description file not found at $SHORT_DESC_FILE"
        print_warning "Using default description: \"$APP_DESCRIPTION\""
    fi
    
    echo ""
}

# Check dependencies
check_dependencies() {
    print_header "Checking Dependencies"
    
    local missing_deps=0
    
    if command -v convert &> /dev/null; then
        print_info "ImageMagick is installed ($(convert -version | head -n1 | cut -d' ' -f3))"
    else
        print_error "ImageMagick is not installed"
        missing_deps=1
    fi
    
    if command -v inkscape &> /dev/null; then
        print_info "Inkscape is installed ($(inkscape --version | cut -d' ' -f2))"
    else
        print_warning "Inkscape is not installed (optional, needed for SVG templates)"
    fi
    
    if [ $missing_deps -eq 1 ]; then
        echo ""
        print_error "Missing required dependencies. Please install them first."
        echo "  macOS:   brew install imagemagick inkscape"
        echo "  Ubuntu:  sudo apt-get install imagemagick inkscape"
        exit 1
    fi
    
    echo ""
}

# Create directories
create_directories() {
    print_header "Preparing Directories"
    
    mkdir -p "$TEMP_DIR"
    mkdir -p "$FEATURE_GRAPHIC_DIR"
    mkdir -p "$PHONE_SCREENSHOTS_DIR"
    
    print_info "Created output directories"
    echo ""
}

# Generate feature graphic (1024x500)
generate_feature_graphic() {
    print_header "Generating Feature Graphic (1024x500)"
    
    local output="$FEATURE_GRAPHIC_DIR/feature-graphic.png"
    local logo="$LOGO_DIR/chronicle_square.png"
    
    # Background color from existing feature graphic
    local bg_color="#282c34"
    local text_color="#ffffff"
    local subtitle_color="#b0b0b0"
    local accent_color="#00b8d4"
    
    if [ ! -f "$logo" ]; then
        print_error "Logo not found at $logo"
        return 1
    fi
    
    print_step "Creating feature graphic with app name and description..."
    print_step "  Using: \"$APP_NAME\""
    print_step "  Using: \"$APP_DESCRIPTION\""
    
    # Create feature graphic with text and logo using metadata from config files
    convert -size 1024x500 "xc:$bg_color" \
        \( "$logo" -resize 200x200 \) \
        -gravity center -geometry +0-80 -composite \
        \( -size 800x80 -background none -fill "$text_color" \
           -font "Lato-Bold" -pointsize 60 -gravity center \
           label:"$APP_NAME" \) \
        -gravity center -geometry +0+60 -composite \
        \( -size 800x50 -background none -fill "$subtitle_color" \
           -font "Lato" -pointsize 28 -gravity center \
           label:"$APP_DESCRIPTION" \) \
        -gravity center -geometry +0+130 -composite \
        "$output" 2>/dev/null || \
    convert -size 1024x500 "xc:$bg_color" \
        \( "$logo" -resize 200x200 \) \
        -gravity center -geometry +0-80 -composite \
        \( -size 800x80 -background none -fill "$text_color" \
           -pointsize 60 -gravity center \
           label:"$APP_NAME" \) \
        -gravity center -geometry +0+60 -composite \
        \( -size 800x50 -background none -fill "$subtitle_color" \
           -pointsize 28 -gravity center \
           label:"$APP_DESCRIPTION" \) \
        -gravity center -geometry +0+130 -composite \
        "$output"
    
    print_info "Generated feature graphic: $output"
    echo ""
}

# Generate phone screenshots with frames
generate_phone_screenshots() {
    print_header "Generating Phone Screenshots"
    
    # Screenshot mappings using parallel arrays (bash 3.x compatible)
    # Note: declare -A associative arrays require bash 4.0+, macOS has bash 3.2
    # Each screenshot has a title (short, bold) and subtitle (descriptive)
    local screenshot_files=("home.png" "library.png" "audiobook.png" "currentlyplaying.png" "search.png" "settings.png")
    local screenshot_titles=("Browse" "Library" "Details" "Listen" "Search" "Settings")
    local screenshot_subtitles=("Your audiobook collection" "All your favorites" "Chapters and progress" "Beautiful player interface" "Find any audiobook" "Customize your experience")
    
    # Counter for naming output files
    local counter=1
    
    # Total number of screenshots
    local total=${#screenshot_files[@]}
    
    # Check if frame template exists
    local frame_template="$IMAGES_DIR/store_frame_template.svg"
    local has_inkscape=0
    local has_frame=0
    
    if command -v inkscape &> /dev/null; then
        has_inkscape=1
    fi
    
    if [ -f "$frame_template" ]; then
        has_frame=1
    fi
    
    for ((i=0; i<total; i++)); do
        local screenshot="${screenshot_files[$i]}"
        local title_text="${screenshot_titles[$i]}"
        local subtitle_text="${screenshot_subtitles[$i]}"
        local input_file="$SCREENSHOTS_DIR/$screenshot"
        local base_name="${screenshot%.png}"
        local output_file="$PHONE_SCREENSHOTS_DIR/${counter}-${base_name}.png"
        
        if [ ! -f "$input_file" ]; then
            print_warning "Screenshot not found: $input_file (skipping)"
            continue
        fi
        
        print_step "Processing $screenshot..."
        
        if [ "$has_inkscape" -eq 1 ] && [ "$has_frame" -eq 1 ]; then
            # Use Inkscape to create framed screenshot with SVG template
            print_step "  Using phone frame template with Inkscape..."
            
            # Create temporary SVG with this screenshot embedded
            local temp_svg="$TEMP_DIR/${base_name}_framed.svg"
            local temp_framed_png="$TEMP_DIR/${base_name}_framed.png"
            
            # Get absolute path for the screenshot (Inkscape needs this)
            local abs_screenshot_path="$(cd "$(dirname "$input_file")" && pwd)/$(basename "$input_file")"
            
            # Create modified SVG that references the actual screenshot file
            # The SVG template has an image placeholder that we replace with our screenshot
            # clipPath852 defines the rounded phone screen area
            create_framed_svg "$abs_screenshot_path" "$temp_svg" "$title_text" "$subtitle_text"
            
            # Render the SVG to PNG using Inkscape
            inkscape "$temp_svg" \
                --export-filename="$temp_framed_png" \
                --export-width=1080 \
                --export-height=1920 2>/dev/null
            
            # If Inkscape succeeded, copy the result
            if [ -f "$temp_framed_png" ]; then
                cp "$temp_framed_png" "$output_file"
            else
                print_warning "  Inkscape rendering failed, falling back to ImageMagick..."
                create_framed_screenshot_imagemagick "$input_file" "$output_file" "$title_text" "$subtitle_text"
            fi
        else
            # Fallback: Use pure ImageMagick to create phone frame effect
            print_step "  Creating framed screenshot with ImageMagick..."
            create_framed_screenshot_imagemagick "$input_file" "$output_file" "$title_text" "$subtitle_text"
        fi
        
        print_info "  Generated: ${counter}-${base_name}.png"
        counter=$((counter + 1))
    done
    
    echo ""
}

# Create an SVG file by modifying the actual store_frame_template.svg
# Arguments: $1 = screenshot path, $2 = output SVG path, $3 = title text, $4 = subtitle text
create_framed_svg() {
    local screenshot_path="$1"
    local output_svg="$2"
    local title_text="$3"
    local subtitle_text="$4"
    
    local frame_template="$IMAGES_DIR/store_frame_template.svg"
    
    # Copy the original template and modify it:
    # 1. Replace the first base64 image data with reference to actual screenshot file
    # 2. Replace text content with title and subtitle
    
    # The template SVG has:
    # - An <image> element with clip-path="url(#clipPath852)" containing base64 data
    # - Two <text> elements with "Listen" content (title and subtitle)
    # - Proper phone frame styling with gradients an rounded corners
    
    # Read the template and perform replacements
    # First, extract everything before and after the image data
    
    # Use Python for reliable SVG manipulation (handles complex base64 strings)
    python3 << PYEOF
import re
import sys

# Read the template file
with open("$frame_template", 'r') as f:
    svg_content = f.read()

# Replace the first base64 image data with file reference
# Pattern matches: xlink:href="data:image/png;base64,..."
# We need to replace the base64 data while keeping the image element structure

# Find and replace base64 image data with file reference
# The template has multiple images - we want to replace the first one (the screenshot area)
pattern = r'(<image[^>]*clip-path="url\(#clipPath852\)"[^>]*xlink:href=")data:image/png;base64,[^"]*(")'
replacement = r'\1file://$screenshot_path\2'
svg_content = re.sub(pattern, replacement, svg_content, count=1)

# If the pattern didn't match, try alternative pattern (clip-path might be after xlink:href)
if 'file://$screenshot_path' not in svg_content:
    pattern = r'(<image[^>]*xlink:href=")data:image/png;base64,[^"]*("[^>]*clip-path="url\(#clipPath852\)")'
    svg_content = re.sub(pattern, replacement, svg_content, count=1)

# Also replace text elements
# First text "Listen" becomes the title
# Second text "Listen" becomes the subtitle
text_count = [0]
def replace_text(match):
    text_count[0] += 1
    if text_count[0] == 1:
        return match.group(1) + "$title_text" + match.group(2)
    elif text_count[0] == 2:
        return match.group(1) + "$subtitle_text" + match.group(2)
    return match.group(0)

pattern = r'(<tspan[^>]*>)Listen(</tspan>)'
svg_content = re.sub(pattern, replace_text, svg_content)

# Write the modified SVG
with open("$output_svg", 'w') as f:
    f.write(svg_content)

print("SVG modified successfully")
PYEOF
}

# Create framed screenshot using pure ImageMagick (fallback method)
# Arguments: $1 = input screenshot, $2 = output file, $3 = title text, $4 = subtitle text
create_framed_screenshot_imagemagick() {
    local input_file="$1"
    local output_file="$2"
    local title_text="$3"
    local subtitle_text="$4"
    
    # Final output: 1080x1920 (9:16 aspect ratio for Play Store)
    # Layout:
    #   - Top area: Title (large, bold) + Subtitle (smaller) on gradient
    #   - Middle: Phone frame with screenshot inside
    #   - Gradient background throughout
    
    local canvas_width=1080
    local canvas_height=1920
    local phone_width=820
    local phone_height=1450
    local screen_width=780
    local screen_height=1380
    local corner_radius=40
    local screen_corner_radius=20
    
    # Create the gradient background
    convert -size ${canvas_width}x${canvas_height} \
        gradient:"#00b8d4-#8cfff2" \
        "$TEMP_DIR/bg_gradient.png" 2>/dev/null || \
    convert -size ${canvas_width}x${canvas_height} \
        "xc:#00b8d4" \
        "$TEMP_DIR/bg_gradient.png"
    
    # Create phone frame (dark rounded rectangle)
    convert -size ${phone_width}x${phone_height} xc:none \
        -fill "#1a1a1a" \
        -draw "roundrectangle 0,0 $((phone_width-1)),$((phone_height-1)) $corner_radius,$corner_radius" \
        "$TEMP_DIR/phone_frame.png"
    
    # Create inner bezel (slightly lighter)
    convert -size $((phone_width-16))x$((phone_height-16)) xc:none \
        -fill "#2d2d2d" \
        -draw "roundrectangle 0,0 $((phone_width-17)),$((phone_height-17)) $((corner_radius-5)),$((corner_radius-5))" \
        "$TEMP_DIR/phone_bezel.png"
    
    # Create screen mask (rounded rectangle for clipping screenshot)
    convert -size ${screen_width}x${screen_height} xc:none \
        -fill white \
        -draw "roundrectangle 0,0 $((screen_width-1)),$((screen_height-1)) $screen_corner_radius,$screen_corner_radius" \
        "$TEMP_DIR/screen_mask.png"
    
    # Resize screenshot to fit screen area and apply rounded corner mask
    convert "$input_file" \
        -resize ${screen_width}x${screen_height}^ \
        -gravity center \
        -extent ${screen_width}x${screen_height} \
        "$TEMP_DIR/screenshot_resized.png"
    
    # Apply rounded corners to screenshot
    convert "$TEMP_DIR/screenshot_resized.png" \
        "$TEMP_DIR/screen_mask.png" \
        -alpha off -compose CopyOpacity -composite \
        "$TEMP_DIR/screenshot_masked.png"
    
    # Compose phone frame with bezel
    convert "$TEMP_DIR/phone_frame.png" \
        "$TEMP_DIR/phone_bezel.png" \
        -gravity center -composite \
        "$TEMP_DIR/phone_with_bezel.png"
    
    # Add screenshot to phone frame
    convert "$TEMP_DIR/phone_with_bezel.png" \
        "$TEMP_DIR/screenshot_masked.png" \
        -gravity center -geometry +0+10 -composite \
        "$TEMP_DIR/phone_complete.png"
    
    # Create shadow for phone
    convert "$TEMP_DIR/phone_complete.png" \
        \( +clone -background black -shadow 60x15+0+15 \) \
        +swap -background none -layers merge +repage \
        "$TEMP_DIR/phone_with_shadow.png"
    
    # Compose everything: gradient + phone with shadow + title + subtitle
    convert "$TEMP_DIR/bg_gradient.png" \
        "$TEMP_DIR/phone_with_shadow.png" \
        -gravity center -geometry +0+100 -composite \
        \( -size ${canvas_width}x80 xc:none \
           -fill white \
           -font "Helvetica-Bold" -pointsize 72 \
           -gravity center -annotate +0+0 "$title_text" \) \
        -gravity north -geometry +0+80 -composite \
        \( -size ${canvas_width}x60 xc:none \
           -fill white \
           -font "Helvetica" -pointsize 36 \
           -gravity center -annotate +0+0 "$subtitle_text" \) \
        -gravity north -geometry +0+160 -composite \
        "$output_file" 2>/dev/null || \
    convert "$TEMP_DIR/bg_gradient.png" \
        "$TEMP_DIR/phone_with_shadow.png" \
        -gravity center -geometry +0+100 -composite \
        \( -size ${canvas_width}x80 xc:none \
           -fill white \
           -pointsize 72 \
           -gravity center -annotate +0+0 "$title_text" \) \
        -gravity north -geometry +0+80 -composite \
        \( -size ${canvas_width}x60 xc:none \
           -fill white \
           -pointsize 36 \
           -gravity center -annotate +0+0 "$subtitle_text" \) \
        -gravity north -geometry +0+160 -composite \
        "$output_file"
}

# Cleanup
cleanup() {
    if [ -d "$TEMP_DIR" ]; then
        print_step "Cleaning up temporary files..."
        rm -rf "$TEMP_DIR"
        print_info "Cleanup complete"
    fi
}

# Generate summary
generate_summary() {
    print_header "Generation Complete!"
    
    echo -e "${GREEN}Generated Graphics:${NC}"
    echo ""
    
    if [ -f "$FEATURE_GRAPHIC_DIR/feature-graphic.png" ]; then
        local size=$(identify -format "%wx%h" "$FEATURE_GRAPHIC_DIR/feature-graphic.png" 2>/dev/null || echo "unknown")
        echo "  📱 Feature Graphic:  $size"
        echo "     → playstore/graphics/featureGraphic/feature-graphic.png"
        echo ""
    fi
    
    local screenshot_count=$(find "$PHONE_SCREENSHOTS_DIR" -name "*.png" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$screenshot_count" -gt 0 ]; then
        echo "  📸 Phone Screenshots: $screenshot_count files"
        echo "     → playstore/graphics/phoneScreenshots/"
        find "$PHONE_SCREENSHOTS_DIR" -name "*.png" | sort | while read -r file; do
            local filename=$(basename "$file")
            local size=$(identify -format "%wx%h" "$file" 2>/dev/null || echo "unknown")
            echo "        - $filename ($size)"
        done
        echo ""
    fi
    
    echo -e "${BLUE}Next Steps:${NC}"
    echo "  1. Review generated graphics in playstore/graphics/"
    echo "  2. Upload to Google Play Console"
    echo "  3. Update screenshots by replacing files in images/screenshots/"
    echo ""
}

# Main execution
main() {
    print_header "Chronicle Play Store Graphics Generator"
    echo ""
    
    load_metadata
    check_dependencies
    create_directories
    generate_feature_graphic
    generate_phone_screenshots
    cleanup
    generate_summary
}

# Run main function
main "$@"

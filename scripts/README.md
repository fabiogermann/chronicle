# Play Store Graphics Generator

This directory contains scripts for generating Google Play Store graphics for Chronicle Epilogue.

## Overview

The [`generate-playstore-graphics.sh`](./generate-playstore-graphics.sh) script automates the creation of:

- **Feature Graphic** (1024×500 px) - Displayed at the top of your Play Store listing
- **Phone Screenshots** (1080px max width) - Gallery images showing app features

## Prerequisites

The script requires the following tools to be installed:

### Required
- **ImageMagick** - Image processing and manipulation

### Optional
- **Inkscape** - SVG to PNG conversion (for using phone frame templates)

## Installation

### macOS

Using Homebrew:

```bash
brew install imagemagick inkscape
```

### Ubuntu/Debian Linux

```bash
sudo apt-get update
sudo apt-get install imagemagick inkscape
```

### Arch Linux

```bash
sudo pacman -S imagemagick inkscape
```

### Fedora

```bash
sudo dnf install ImageMagick inkscape
```

### Verify Installation

```bash
# Check ImageMagick
convert -version

# Check Inkscape
inkscape --version
```

## Capturing Screenshots

Before running the generation script, you need raw screenshots from your Android device.

### Method 1: Using ADB (Android Debug Bridge)

1. **Install ADB**:
   - macOS: `brew install android-platform-tools`
   - Ubuntu: `sudo apt-get install android-tools-adb`

2. **Enable USB Debugging** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go to Settings → Developer Options
   - Enable "USB Debugging"

3. **Connect your device** via USB and verify:
   ```bash
   adb devices
   ```

4. **Capture screenshots**:
   ```bash
   # Navigate to the desired screen in the app
   # Then run this command to capture:
   adb exec-out screencap -p > images/screenshots/home.png
   
   # Capture all required screens:
   adb exec-out screencap -p > images/screenshots/library.png
   adb exec-out screencap -p > images/screenshots/audiobook.png
   adb exec-out screencap -p > images/screenshots/currentlyplaying.png
   adb exec-out screencap -p > images/screenshots/search.png
   adb exec-out screencap -p > images/screenshots/settings.png
   ```

5. **Alternative method** (pull from device):
   ```bash
   # Take screenshot on device (Power + Volume Down)
   # Then pull the latest screenshot:
   adb pull /sdcard/Pictures/Screenshots/Screenshot_*.png images/screenshots/
   ```

### Method 2: Using Android Emulator

1. **Launch emulator** from Android Studio
2. **Navigate** through the app screens
3. **Use emulator's screenshot tool** (camera icon in emulator toolbar)
4. **Save screenshots** to `images/screenshots/` directory

### Required Screenshots

Ensure you have these screenshots in `images/screenshots/`:

- `home.png` - Home/Browse screen
- `library.png` - Library listing
- `audiobook.png` - Audiobook details with chapters
- `currentlyplaying.png` - Now Playing screen
- `search.png` - Search interface
- `settings.png` - Settings screen

## Running the Script

### Basic Usage

```bash
# Make script executable (first time only)
chmod +x scripts/generate-playstore-graphics.sh

# Run the script
./scripts/generate-playstore-graphics.sh
```

### What the Script Does

1. **Checks dependencies** - Verifies ImageMagick and Inkscape are installed
2. **Creates directories** - Sets up output folder structure
3. **Generates feature graphic** - Creates 1024×500 promotional banner
4. **Processes screenshots** - Adds frames and promotional text
5. **Outputs files** to `app/src/main/play/listings/en-US/graphics/` directory

### Output Structure

```
app/src/main/play/listings/en-US/graphics/
├── feature-graphic/
│   └── feature-graphic.png          # 1024×500 banner
└── phone-screenshots/
    ├── 1-home.png                   # Framed screenshots
    ├── 2-library.png
    ├── 3-audiobook.png
    ├── 4-currentlyplaying.png
    ├── 5-search.png
    └── 6-settings.png
```

## Customization

### Modifying Promotional Text

Edit the `screenshots` array in [`generate-playstore-graphics.sh`](./generate-playstore-graphics.sh:95):

```bash
declare -A screenshots=(
    ["home.png"]="Your Custom Text Here"
    ["library.png"]="Another Custom Text"
    # ...
)
```

### Changing Colors

Modify these variables in the script:

```bash
# Feature graphic colors
local bg_color="#282c34"         # Dark background
local text_color="#ffffff"        # White text
local subtitle_color="#b0b0b0"    # Gray subtitle
local accent_color="#00b8d4"      # Cyan accent

# Screenshot frame gradient
gradient:"#00b8d4-#8cfff2"        # Cyan gradient
```

### Using Custom Fonts

The script uses Lato fonts by default. To use different fonts:

1. Install the font on your system
2. Update the `-font` parameter in the script:
   ```bash
   -font "YourFontName-Bold"
   ```

## Manual Alternatives

If you prefer not to use the automated script, these online tools can create professional app screenshots:

### Phone Mockup Generators

1. **[MockUPhone](https://mockuphone.com/)**
   - Free web-based tool
   - Supports multiple device frames
   - Drag-and-drop interface

2. **[App Mockup](https://app-mockup.com/)**
   - Device frame templates
   - Background customization
   - Export to PNG

3. **[Screenshot.rocks](https://screenshot.rocks/)**
   - Modern, clean mockups
   - Custom backgrounds
   - No watermarks

4. **[Previewed](https://previewed.app/)**
   - High-quality mockups
   - Multiple devices
   - Free tier available

### Design Tools

1. **[Figma](https://figma.com/)**
   - Professional design tool
   - Free tier available
   - Community templates for app screenshots

2. **[Canva](https://canva.com/)**
   - Easy-to-use templates
   - Play Store screenshot templates
   - Free version available

3. **[Shots by Rotato](https://shots.so/)**
   - 3D device mockups
   - Browser-based
   - Free for basic use

### Using Manual Tools

1. **Upload** your raw screenshots to the tool
2. **Select** device frame (e.g., Google Pixel, Samsung Galaxy)
3. **Add text overlays** with promotional messages
4. **Customize** backgrounds and colors
5. **Export** as PNG files
6. **Download** and place in `app/src/main/play/listings/en-US/graphics/phone-screenshots/`

## Google Play Store Requirements

### Feature Graphic
- **Dimensions**: 1024 × 500 pixels
- **Format**: PNG or JPEG
- **Max file size**: 1 MB
- **Note**: Must not contain device frames or promotional text like "Free" or "Top Rated"

### Phone Screenshots
- **Max dimensions**: 3840 × 3840 pixels
- **Min dimensions**: 320 pixels (shortest side)
- **Format**: PNG or JPEG
- **Max file size**: 8 MB per screenshot
- **Quantity**: Minimum 2, maximum 8 screenshots
- **Note**: Screenshots are displayed in the order they are uploaded

### Best Practices

- Use high-quality, clear screenshots showing key features
- Include promotional text overlays to highlight features
- Show diverse app functionality
- Use consistent styling across all screenshots
- Consider adding device frames for a polished look
- Test how screenshots appear on different screen sizes

## Uploading to Google Play Console

1. Log in to [Google Play Console](https://play.google.com/console)
2. Select your app
3. Go to **Store presence** → **Main store listing**
4. Scroll to **Graphics** section
5. Upload feature graphic
6. Upload phone screenshots in the desired order
7. Save changes

## Troubleshooting

### ImageMagick Errors

**Error**: `convert: not authorized`

**Solution**: Edit ImageMagick's policy file to allow PDF/PNG operations:

```bash
# Find policy file
sudo find /etc -name "policy.xml" 2>/dev/null

# Edit the file (usually /etc/ImageMagick-*/policy.xml)
# Change this line:
#   <policy domain="coder" rights="none" pattern="PNG" />
# To:
#   <policy domain="coder" rights="read|write" pattern="PNG" />
```

### Font Not Found

**Error**: `unable to read font`

**Solution**: Use system fonts or specify full path:

```bash
# List available fonts
convert -list font | grep -i "family"

# Or use system font directories
-font "/System/Library/Fonts/Helvetica.ttc"
```

### ADB Device Not Found

**Solution**:
```bash
# Restart ADB server
adb kill-server
adb start-server

# Check connection
adb devices
```

### Script Permission Denied

**Solution**:
```bash
chmod +x scripts/generate-playstore-graphics.sh
```

## Additional Resources

- [Google Play Store Listing Guidelines](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Android Screenshot Guidelines](https://developer.android.com/distribute/marketing-tools/device-art-generator)
- [ImageMagick Documentation](https://imagemagick.org/index.php)
- [Inkscape Manual](https://inkscape.org/doc/)

## Contributing

To improve the script or add features:

1. Update the script with your changes
2. Test thoroughly on macOS and Linux
3. Update this README with new features or options
4. Submit a pull request

## License

This script is part of Chronicle Epilogue and follows the same license as the main project.

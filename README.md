# Ahoy Books - Audiobook Player

A feature-rich Android audiobook player with queue management, LibriVox integration, and smart file organization.

## Background
I wanted a simple, easy to use audio book app for Android- I didn't really like what was on offer so I built one! 

## Features

### Core Playback
- **Multiple Book Support**: Listen to multiple audiobooks simultaneously, each maintaining its own playback position
- **Position Memory**: Automatically saves your progress in each book, even when closing the app
- **Background Playback**: Continues playing in the background with lock screen controls
- **Queue Management**: Reorder tracks, move items up/down, and customize playback order
- **Sleep Timer**: Set a timer to automatically pause playback after a specified duration

### File Management
- **Smart File Sorting**: Automatically detects and orders files by chapter numbers, part numbers, and common naming patterns
- **Folder-Based Organization**: Each audiobook is a folder containing audio files
- **Custom Folder Selection**: Choose any folder on your device to store audiobooks
- **Automatic Scanning**: Scans for audiobook folders in your selected directory

### LibriVox Integration
- **Browse LibriVox**: Built-in web browser to explore and download audiobooks from LibriVox.org
- **Automatic Download & Extraction**: Downloads ZIP files and automatically extracts audio files to your audiobook folder
- **Seamless Integration**: Downloaded books appear in your library automatically

### User Interface
- **Modern Material Design**: Clean, sleek interface with intuitive controls
- **Queue Dialog**: Easy-to-use queue management with drag-and-drop reordering
- **Progress Tracking**: Visual indicators showing current file and position

## Supported Audio Formats

- MP3
- M4A / M4B
- AAC
- OGG
- WAV
- FLAC
- OPUS

## Setup

### Initial Setup

1. **Grant Permissions**: When you first open the app, grant storage permissions
2. **Select Folder**: 
   - Use the menu (three dots) to browse and select your audiobook folder
   - Or use the default Music folder
3. **Add Audiobooks**: Place audiobook folders in your selected directory
   - Each audiobook should be in its own folder
   - The app will automatically detect and organize files

### Downloading from LibriVox

1. Open the menu (three dots) and select "Download from LibriVox"
2. Browse the LibriVox website in the built-in browser
3. Find an audiobook and click the download link
4. The app will automatically:
   - Download the ZIP file
   - Extract audio files
   - Save to your audiobook folder
   - Refresh your library

## Usage

### Playing Audiobooks

1. **Start Playback**: Tap on any book in the library to start playing
2. **Controls**:
   - Play/Pause: Center button
   - Previous/Next: Skip to previous/next track
   - Rewind/Forward: 10-second skip buttons
3. **Queue Management**: 
   - Tap the queue button to view and manage tracks
   - Drag tracks to reorder
   - Use up/down arrows for fine control

### Managing Queue

- **Reorder Tracks**: Long-press and drag tracks to new positions
- **Move One Position**: Use the up/down arrow buttons
- **Queue Persistence**: Your custom queue order is saved between sessions

### Sleep Timer

1. Tap the "Sleep Timer" button on the player screen
2. Enter the number of minutes (1-480)
3. Playback will automatically pause when the timer expires

## Building

### Prerequisites

- **Android Studio** (recommended) or Android SDK
- **JDK 8 or higher**
- **Gradle** (wrapper included)

### Using Android Studio

1. Install [Android Studio](https://developer.android.com/studio)
2. Open the project in Android Studio
3. Wait for Gradle sync to complete
4. Click **Run > Run 'app'** to build and install

### Command Line Build

1. **Set Android SDK location**:
   ```bash
   # Create local.properties file
   echo "sdk.dir=/path/to/your/android/sdk" > local.properties
   ```

2. **Build the app**:
   ```bash
   ./gradlew assembleDebug
   ```
   
   The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

3. **Install on device**:
   ```bash
   ./gradlew installDebug
   ```

### Common Android SDK Locations

- **Linux:** `~/Android/Sdk`
- **macOS:** `~/Library/Android/sdk`
- **Windows:** `C:\Users\<username>\AppData\Local\Android\Sdk`

## Permissions

- **READ_EXTERNAL_STORAGE** (Android 12 and below) or **READ_MEDIA_AUDIO** (Android 13+): Required to access audiobook files
- **INTERNET**: Required for LibriVox downloads and cover art search
- **ACCESS_NETWORK_STATE**: Required to check network connectivity
- **FOREGROUND_SERVICE** / **FOREGROUND_SERVICE_MEDIA_PLAYBACK**: Required for background playback
- **POST_NOTIFICATIONS**: Required for playback controls in notifications

## Technical Details

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Language**: Kotlin
- **Architecture**: Standard Android app with foreground service for playback

## Project Structure

```
app/src/main/
├── java/com/audiobookplayer/
│   ├── MainActivity.kt          # Main library view
│   ├── PlayerActivity.kt         # Playback interface
│   ├── AudioPlaybackService.kt   # Background playback service
│   ├── BookScanner.kt            # File scanning and organization
│   ├── LibriVoxWebActivity.kt    # LibriVox browser
│   └── ...                       # Supporting classes
└── res/
    ├── layout/                   # UI layouts
    ├── drawable/                 # Icons and graphics
    └── values/                   # Strings, colors, themes
```

## Troubleshooting

### Books Not Appearing
- Ensure audiobook folders are in your selected directory
- Check that folders contain supported audio files
- Use the refresh button in the menu to rescan

### Playback Issues
- Check that storage permissions are granted
- Verify audio files are not corrupted
- Try restarting the app

### LibriVox Downloads Not Working
- Ensure you have an active internet connection
- Check that the target folder is accessible (not a URI-based folder)
- Verify sufficient storage space

### Lock Screen Controls Not Showing
- Check notification permissions
- Ensure the app is running as a foreground service
- Verify notification channel settings

## License

This project is open source. See LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

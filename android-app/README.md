# WiFi Failover - Native Android App

A minimal, lightweight native Android app that automatically enables your phone's WiFi hotspot when your Mac loses internet connectivity.

## Overview

This app replaces the need for Tasker or Automate. It provides:
- **Periodic polling** of the Cloudflare Worker (default: every 2 minutes)
- **Automatic hotspot activation** when failover is triggered
- **Background execution** using WorkManager
- **Auto-start on boot** via BOOT_COMPLETED receiver
- **Simple settings UI** for configuration

## Architecture

```
┌─ Mac WiFi Failover Daemon ────────────────┐
│ (Detects connectivity loss)              │
│ POSTs to Cloudflare Worker               │
└────────────────────────────────────────────┘
                    ↓
┌─ Cloudflare Worker KV Store ──────────────┐
│ (Stores "hotspot_enabled" flag)          │
└────────────────────────────────────────────┘
                    ↓
┌─ Android App (this project) ───────────────┐
│ • WorkManager periodic task               │
│ • Polls /api/status every 2 minutes      │
│ • Enables hotspot if needed              │
│ • Auto-starts on boot                    │
└────────────────────────────────────────────┘
```

## Building

### Prerequisites
- Android Studio Flamingo or newer
- Kotlin 1.9+
- Android SDK 34+
- Minimum API 30 (Android 11)

### Steps

1. **Clone the repository**
```bash
git clone https://github.com/dhruv-anand-aintech/wifi-failover-utility.git
cd wifi-failover-utility/android-app
```

2. **Open in Android Studio**
- File → Open → Select `android-app` directory
- Wait for Gradle sync to complete

3. **Configure your Worker credentials**
- Run the app and enter:
  - Worker URL: `https://wifi-failover.youraccount.workers.dev`
  - Worker Secret: Your secret from setup
  - Hotspot SSID: Your phone's hotspot name (e.g., "Dhruv's Phone")

4. **Build & Install**
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Or run directly on device
./gradlew installDebug
```

## Installation

### From Source
1. Follow the building steps above
2. Run `./gradlew installDebug` to install on connected device
3. Open the app and configure settings

### Manual APK Installation
1. Build release APK: `./gradlew assembleRelease`
2. APK will be at: `app/build/outputs/apk/release/app-release.apk`
3. Transfer to phone and install
4. Configure settings when you first open the app

## Configuration

### First Launch
When you open the app for the first time, you'll need to enter:

1. **Worker URL** - From your Cloudflare Worker deployment
   - Example: `https://wifi-failover.dhruv-anand.workers.dev`

2. **Worker Secret** - The secret you generated during setup
   - Example: `yZ0NDAKbwd24B9A4hjJxw2PTO+onteuBbe8RvWmqajo=`

3. **Phone Hotspot SSID** - The name of your phone's hotspot
   - Example: `Dhruv's Phone`

4. **Polling Interval** - How often to check (default: 2 minutes)

### Permissions Required
The app will request:
- **INTERNET** - To communicate with your Worker
- **CHANGE_WIFI_STATE** - To enable/disable hotspot
- **ACCESS_WIFI_STATE** - To check WiFi status
- **RECEIVE_BOOT_COMPLETED** - To auto-start on boot
- **POST_NOTIFICATIONS** - To show status notifications

### Important
⚠️ On Android 12+, you may need to:
1. **Grant CHANGE_WIFI_STATE permission** in Settings → Apps → WiFi Failover → Permissions
2. **Disable battery optimization** for the app (Settings → Battery → Battery saver → App → WiFi Failover)
3. **Allow background execution** (Settings → Apps → WiFi Failover → Battery → Background restriction: Unrestricted)

## How It Works

### Startup
1. Phone boots → BOOT_COMPLETED receiver triggers
2. WorkManager schedules periodic task
3. App starts polling the Worker every 2 minutes

### Normal Operation
1. WorkManager wakes up every 2 minutes
2. WiFiFailoverWorker GETs `/api/status` from your Worker
3. If `hotspot_enabled = true`:
   - Enables phone hotspot
   - POSTs acknowledgment to Worker
4. If `hotspot_enabled = false`:
   - Disables hotspot (if it was on)

### Failover Flow
1. Mac loses WiFi → Mac daemon detects
2. Mac daemon POSTs to Worker: `{action: "enable"}`
3. Worker sets `hotspot_enabled = true` in KV
4. (Next polling cycle) Android app sees status
5. App enables hotspot
6. Mac daemon detects hotspot and connects
7. Mac posts to Worker: `{action: "disable"}`
8. Worker sets `hotspot_enabled = false`
9. (Next polling cycle) Android app disables hotspot

## Troubleshooting

### App won't enable hotspot
1. Check phone settings: Settings → Network & Internet → Hotspot
2. Try manually enabling hotspot first
3. Check app permissions are granted
4. Some Android versions require device admin or system app

**Note:** Non-system apps have limited ability to control hotspot on modern Android versions. If you're on Android 12+, consider using **Tasker** or **Automate** which have Device Admin capabilities.

### App not starting on boot
1. Check BOOT_COMPLETED permission is granted
2. Check app is not in battery optimization exclusion
3. Check WorkManager constraints (requires CONNECTED network type)

### Worker not responding
1. Test manually: `curl https://your-worker-url/health`
2. Verify Worker URL in app settings
3. Check internet connection on phone

### Can't save configuration
1. All three fields must be filled
2. Worker URL must start with `https://`

## Project Structure

```
src/main/
├── kotlin/
│   └── com/wififailover/app/
│       ├── WiFiFailoverApp.kt          # App entry point
│       ├── api/
│       │   └── WorkerApi.kt           # Retrofit API client
│       ├── data/
│       │   ├── models.kt              # Data classes
│       │   └── ConfigDataStore.kt     # DataStore config storage
│       ├── receiver/
│       │   └── BootCompleteReceiver.kt # Boot startup
│       ├── ui/
│       │   ├── MainActivity.kt         # Main activity
│       │   ├── screens/
│       │   │   └── ConfigurationScreen.kt
│       │   ├── theme/
│       │   │   ├── Theme.kt
│       │   │   └── Type.kt
│       │   └── viewmodel/
│       │       └── ConfigurationViewModel.kt
│       └── worker/
│           └── WiFiFailoverWorker.kt   # Background task
├── res/
│   ├── values/
│   │   ├── strings.xml
│   │   ├── colors.xml
│   │   └── styles.xml
│   └── ...
└── AndroidManifest.xml
```

## Development

### Dependencies
- **Jetpack Compose** - Modern UI framework
- **Hilt** - Dependency injection
- **WorkManager** - Background task scheduling
- **Retrofit** - HTTP client
- **DataStore** - Configuration storage
- **Kotlin Coroutines** - Async operations

### Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

### Code Style
- Kotlin style guide: [Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- Use `ktlint` for formatting:
```bash
./gradlew ktlint
./gradlew ktlintFormat
```

## Limitations

- **Android 12+ hotspot control:** Non-system apps have limited access to hotspot APIs
  - Workaround: Use Tasker/Automate with Device Admin, or root the device
- **Cannot directly connect to hotspot:** Still requires manual connection or Mac automation
- **Network-dependent:** Requires connected network to reach Worker (WorkManager constraint)
- **No kill switch on notification:** Close app or disable monitoring to stop

## Advantages Over Tasker/Automate

✅ Native app - Better integration
✅ Smaller footprint - ~2MB vs 50MB+
✅ Simpler configuration - Just 3 settings
✅ Better performance - Direct API calls
✅ Open source - You can modify it
✅ Auto-starts on boot - No manual trigger needed

## Disadvantages

❌ Requires Android 11+
❌ Limited hotspot control on Android 12+ (non-system app)
❌ No Device Admin (Tasker has this)
❌ Less flexibility than Tasker's scripting

## Security

- Configuration stored in **DataStore** (encrypted on modern Android)
- Worker secret handled in memory only
- No telemetry or data collection
- All code is open source

**Never:**
- Share your Worker secret
- Commit config files to git
- Send sensitive data over unencrypted networks

## Contributing

Found a bug or want to improve? Open a GitHub issue or pull request!

## License

MIT License - See LICENSE file

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Review app logs: `adb logcat | grep WiFiFailover`
3. Open a GitHub issue with:
   - Device model and Android version
   - Error messages from logs
   - Steps to reproduce

---

**Need Tasker/Automate instructions?** See the parent directory:
- [AUTOMATE_SETUP.txt](../AUTOMATE_SETUP.txt)
- [TASKER_SETUP.txt](../TASKER_SETUP.txt)

# WiFi Failover - Native Android App

A minimal, lightweight native Android app that automatically enables your phone's WiFi hotspot when your Mac loses internet connectivity.

## Overview

This is a native Android app that provides:
- **Periodic polling** of the Cloudflare Worker (default: every 5 seconds)
- **Automatic hotspot activation** when failover is triggered
- **Device Admin permission** for reliable hotspot control
- **Background execution** using WorkManager
- **Auto-start on boot** via BOOT_COMPLETED receiver
- **Simple settings UI** for easy configuration

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
│ • Polls /api/status every 5 seconds      │
│ • Enables hotspot if needed              │
│ • Auto-starts on boot                    │
│ • Device Admin for reliable control      │
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

4. **Polling Interval** - How often to check (default: 5 seconds)

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
3. App starts polling the Worker every 5 seconds

### Normal Operation
1. WorkManager wakes up every 5 seconds
2. WiFiFailoverWorker GETs `/api/status` from your Worker
3. If `daemon_status = "offline"`:
   - Enables phone hotspot
   - POSTs acknowledgment to Worker
4. If `daemon_status = "online"` or `"paused"`:
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
3. Check app permissions are granted: Settings → Apps → WiFi Failover → Permissions
4. Grant Device Admin permission: Settings → Apps → Special app access → Device admin apps → WiFi Failover (toggle ON)
5. On Android 12+, disable battery optimization for the app
6. Check app isn't in the battery saver exclusion list

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

## Advantages

✅ Native app - Better integration than 3rd party automation
✅ Lightweight - ~10MB total
✅ Simple configuration - Just 3 settings
✅ Fast polling - 5 second response time
✅ Device Admin - Reliable hotspot control
✅ Open source - You can modify it
✅ Auto-starts on boot - No manual setup needed
✅ No 3rd party dependencies - Just Android native APIs

## Limitations

- **Requires Android 11+** - Won't work on older devices
- **Network-dependent** - Requires internet to reach Worker (WorkManager constraint)
- **Hotspot must be configured** - Can enable but not configure hotspot name/password
- **Battery usage** - ~1-2% per hour due to 5-second polling
- **No automatic WiFi reconnection** - Mac handles the WiFi connection, not the app

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

## More Information

For complete setup instructions including Cloudflare Worker deployment, see:
- [Complete Setup Guide](../COMPLETE_SETUP_GUIDE.md)
- [README](../README.md)

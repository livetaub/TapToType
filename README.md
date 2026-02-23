# TapToType — Bluetooth HID Keyboard for Windows

Turn your Android phone into a Bluetooth keyboard for your Windows 10/11 PC.  
No companion app needed on the PC — Windows recognizes the phone as a standard keyboard.

---

## 📋 Requirements

- **Phone**: Android 9.0 (API 28) or higher with Bluetooth support
- **PC**: Windows 10 or Windows 11 with Bluetooth
- **Android Studio**: Arctic Fox (2020.3.1) or newer

> **Important**: The `BluetoothHidDevice` API was added in Android API 28 (Pie).  
> Some phone manufacturers may not fully support the HID Device profile — you may need to test on your specific device.

---

## 🚀 Setup Instructions

### 1. Open in Android Studio

1. Open Android Studio
2. Select **File → Open** and navigate to this project folder
3. Wait for Gradle sync to complete
4. Build the project (**Build → Make Project** or `Ctrl+F9`)

### 2. Install on Your Phone

1. Enable **USB debugging** on your phone (Settings → Developer Options)
2. Connect your phone via USB
3. Click **Run** (▶) in Android Studio or `Shift+F10`
4. Select your phone from the device list

### 3. Pair with Windows

#### Step A: Make your phone discoverable

1. Open the **TapToType** app on your phone
2. Tap **"Make Discoverable"** — this puts your phone into Bluetooth discoverable mode for 2 minutes

#### Step B: Pair from Windows

1. On your Windows PC, go to **Settings → Bluetooth & Devices → Add device**
2. Select **Bluetooth**
3. Your phone should appear as **"TapToType"** or your phone's Bluetooth name
4. Click on it to pair
5. Accept the pairing prompt on both your phone and PC

#### Step C: Connect in the app

1. After pairing, tap **"Connect to Device"** in the app
2. Select your Windows PC from the list of paired devices
3. The status should change to **"Connected as Keyboard to [PC Name]"**

### 4. Start Typing!

- **Live Input Mode** (toggle ON): Every character you type is instantly sent to the active text field on Windows
- **Send Button Mode** (toggle OFF): Type a full message, then tap **Send** to transmit it all at once

---

## 📁 Project Structure

```
app/src/main/
├── AndroidManifest.xml          # Permissions and app config
├── java/com/taptotype/
│   ├── MainActivity.kt          # UI, permissions, mode switching
│   ├── BluetoothHidService.kt   # HID device registration & key sending
│   └── HidKeyMapper.kt          # Character → HID key code mapping
└── res/
    ├── layout/activity_main.xml  # Main UI layout
    ├── drawable/                  # Icons, backgrounds, buttons
    └── values/                   # Colors, strings, themes
```

---

## ⌨️ Supported Keys

| Category | Keys |
|----------|------|
| Letters | a-z, A-Z |
| Numbers | 0-9 |
| Symbols | ! @ # $ % ^ & * ( ) |
| Punctuation | . , ; : ' " ` ~ - _ = + [ ] { } \ | / ? < > |
| Whitespace | Space, Enter, Tab |
| Control | Backspace |

---

## 🔧 Troubleshooting

### "Cannot connect" or connection drops immediately
- Make sure your phone supports the Bluetooth HID Device profile (some OEMs disable it)
- Try unpairing and re-pairing the devices
- Restart Bluetooth on both devices

### Windows doesn't see the phone during pairing
- Tap **"Make Discoverable"** first in the app
- Make sure Bluetooth is enabled on both devices
- Get within 1-2 meters of the PC

### Keys are garbled or wrong characters appear
- The key mapping uses US keyboard layout (QWERTY)
- If your Windows keyboard layout is different, characters may not match

### "Bluetooth permissions denied"
- Go to **Settings → Apps → TapToType → Permissions** and grant all Bluetooth permissions manually

### HID profile not available
- Not all Android devices support `BluetoothHidDevice`. Check with your manufacturer.
- Root-only devices may have the profile disabled at the system level.

---

## 📱 Permissions Used

| Permission | Purpose |
|------------|---------|
| `BLUETOOTH_CONNECT` | Connect to paired Bluetooth devices (Android 12+) |
| `BLUETOOTH_ADVERTISE` | Make phone discoverable to Windows (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for nearby Bluetooth devices (Android 12+) |
| `BLUETOOTH` | Legacy Bluetooth access (Android 11 and below) |
| `BLUETOOTH_ADMIN` | Legacy Bluetooth admin (Android 11 and below) |
| `ACCESS_FINE_LOCATION` | Required for Bluetooth scan on older Android versions |

---

## ⚠️ Constraints & Limitations

- **Keyboard only** — no mouse, touchpad, or gamepad functionality
- **No WiFi required** — pure Bluetooth connection
- **No companion app on PC** — Windows treats the phone as a standard keyboard
- US keyboard layout mapping only
- Requires Android 9+ (API 28) for `BluetoothHidDevice` API
- Device manufacturer must support the Bluetooth HID Device profile

---

## License

MIT License — use freely.

# MultiSensor Data Collection

[![Android Target SDK](https://img.shields.io/badge/Target%20SDK-36-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue.svg)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Version-1.4.0-teal.svg)](https://github.com/rafaelpezzuto/multi-sensor-data-collection/releases/tag/v1.4.0)

**MultiSensor Data Collection** is an open-source Android application designed for high-precision, multi-sensor dataset acquisition. It seamlessly gathers and synchronizes data from various mobile sensors—including video camera, microphone, accelerometer, gyroscope, magnetometer, gravity, light, geolocation (GPS), battery consumption, and network signals—creating structured local datasets formatted for urban informatics, computer vision, and machine learning research.

---

## 🌟 Key Features

- 📹 **Synchronized Video & Audio Recording**: Record high-definition video (SD, HD, FHD, 4K) using Jetpack CameraX with custom frame rates and audio configurations.
- 🔍 **Camera Pinch-to-Zoom**: Dynamic gesture zoom control with min/max bounds and smooth zoom ratio feedback.
- 🧭 **Multi-Sensor Logging**: High-frequency logging for motion sensors (accelerometer, gyroscope, magnetometer, gravity), light, pressure, and step counters.
- 📍 **Geolocation & Interactive Map**: High-accuracy GPS tracking (`gps.csv`) paired with an in-app interactive map (OpenStreetMap / OSMDroid) featuring route polylines, start/end markers, and timestamp/accuracy info.
- 📁 **Data History & In-App Preview**:
  - **Saved Data List**: Browse all local collections (ZIP archives or folders) with file counts, sizes, and timestamps.
  - **Video/Audio Player**: Full-screen media player with playback controls.
  - **CSV Table Viewer**: Scrollable table inspector with highlighted headers for sensor CSV files.
  - **Pretty-Printed JSON Viewer**: Formatted inspector for `metadata.json`.
- 💾 **Real-Time Storage & Dynamic Time Estimation**: Live display of available disk space and dynamic estimated recording time based on active mode (Audio+Video or Audio) and camera resolution.
- 🔋 **Battery & Network Monitoring**: Periodic logging of battery consumption metrics and Wi-Fi / Cellular network scans.

---

## 📱 Screenshots

| ![Main Screen](https://github.com/user-attachments/assets/a4dc21fc-626e-49ce-a106-c1f6020453ed) | ![Camera Resolution Settings](https://github.com/user-attachments/assets/92e02ca8-d9c4-462d-b03c-7fdef09ea1d8) |
|:---:|:---:|
| **Main Recording Screen** | **Camera Resolution Settings** |

| ![Sensor Settings](https://github.com/user-attachments/assets/2bf08d1d-4bcf-4853-9fdf-9ecbfcba1b76) | ![General Settings](https://github.com/user-attachments/assets/b96b5071-264e-4f0b-9632-9cc0476b282b) |
|:---:|:---:|
| **Sensor Sampling Frequency** | **General Settings** |

---

## 📂 Dataset Structure

Each collection generates a synchronized dataset directory (or `.zip` archive) containing:

```text
MultiSensorDC/<dataset_name>/
├── video.mp4               # High-definition video recording
├── audio.m4a               # Audio recording (when audio-only mode selected)
├── metadata.json           # Device info, screen dimensions, sensor list, start/stop UTC times
├── accelerometer.csv       # timestamp_utc, timestamp_nanos, x, y, z
├── gyroscope.csv           # timestamp_utc, timestamp_nanos, x, y, z
├── magnetometer.csv        # timestamp_utc, timestamp_nanos, x, y, z
├── gravity.csv             # timestamp_utc, timestamp_nanos, x, y, z
├── gps.csv                 # datetime_utc, gps_interval, accuracy, latitude, longitude
├── battery.csv             # timestamp_utc, battery_level, temperature, voltage
└── network.csv             # timestamp_utc, wifi_ssid, bssid, rssi, cellular_info
```

---

## ⚙️ Building & Running

### Prerequisites
- **Android Studio**: 2026.1+ or newer
- **JDK**: 21
- **Android SDK**: Target SDK 36 (Android 15+), Min SDK 26 (Android 8.0+)
- **Gradle**: 9.5+ with Version Catalog (`gradle/libs.versions.toml`)

### Gradle Commands

```bash
# Build Debug APK
./gradlew app:assembleDebug

# Run Unit Tests
./gradlew app:testDebugUnitTest

# Build Release App Bundle (AAB)
./gradlew app:bundleRelease
```

---

## 🔬 About SideSeeing

This application is part of the **SideSeeing Project**, led by researchers at the **Institute of Mathematics and Statistics of the University of São Paulo (IME-USP)**.

The SideSeeing project aims to develop methods based on Computer Vision, Sensor Fusion, and Machine Learning for **Urban Informatics** applications, focusing on urban accessibility and infrastructure analysis.

For more information, visit the official website: [SideSeeing](https://sites.usp.br/sideseeing)

---

## 👥 Authors & Contributors

- **Alyssa Florence** / IME-USP
- **Rafael Jeferson Pezzuto Damaceno** ([@rafaelpezzuto](https://github.com/rafaelpezzuto)) / IME-USP — *rafael.pezzuto@gmail.com*
- **Roberto Marcondes Cesar Jr.** / IME-USP

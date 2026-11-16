# camera-kmp

A Kotlin Multiplatform camera app (Android first) that exposes every camera option the phone really supports, tuned for the **Samsung Galaxy S25**.

## What it does

- **One camera screen** with four actions: Photo (HEIC / JPEG / JPEG_R / HEIC Ultra HDR, whichever the device supports), RAW (lossless DNG), Video, and Low-bitrate video.
- **Settings only show what works.** On first launch the app scans Camera2, the camera extensions and every video encoder, then *tests* each encoder × resolution × frame rate × bitrate combination by configuring the encoder. Only combinations that pass are offered, so a chosen video setting cannot fail at record time. Results are cached per firmware build; "Re-test device" in Settings runs the scan again.
- **HDR on or off per photo.** On uses the Camera2 HDR extension (Samsung's own processing); off is a single normal capture. HEIC in HDR mode is encoded once from the extension's YUV output.
- **No re-encoding.** Compressed photos are saved byte-for-byte as the camera produced them; RAW goes through `DngCreator`; video is encoded once by the selected encoder.
- **Every detail.** The Details screen lists every capture-result key (including vendor tags and per-lens results), all EXIF tags, and for video the container/track metadata plus the measured real bitrate. Settings → All capabilities shows the full device dump.
- **Pro controls** (manual ISO/shutter, manual focus, noise reduction, sharpening, OIS, white balance, video stabilization) appear only when the camera reports them.

## Layout

- `composeApp/src/commonMain` – shared models and Compose UI.
- `composeApp/src/androidMain` – Camera2 engine (`AndroidCameraController`), capability scan (`CapabilityScanner`), encoder validation (`VideoValidator`), MediaCodec recorder (`VideoRecorder`), saving (`MediaStoreSaver`).

## Build

```
./gradlew :composeApp:assembleDebug
```

CI (GitHub Actions) builds the debug APK on every pull request and uploads it as the `camera-kmp-debug-apk` artifact.

Requires Android 12 (API 31) or newer.

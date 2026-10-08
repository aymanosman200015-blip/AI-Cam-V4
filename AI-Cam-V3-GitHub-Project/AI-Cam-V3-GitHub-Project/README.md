# AI Cam V3 — S / G / iPhone-style computational camera

V3 targets Galaxy Note10+ Snapdragon and other CameraX-compatible Android phones.

## V3 additions
- Camera2 capability discovery for rear/front cameras.
- Focal-length-aware lens selection using the camera's reported lens characteristics.
- Pro capture controls: ISO, shutter speed, EV, and white-balance presets when supported by the selected camera pipeline.
- Vendor CameraX Extensions are used automatically when the device exposes HDR, Night, or Bokeh for the selected camera. Otherwise the app falls back to its own multi-frame pipeline.
- HDR fallback: 3-frame exposure merge.
- Night fallback: 6-frame temporal median + shadow lift.
- Portrait fallback: synthetic depth-style blur; vendor Bokeh is preferred when available.
- S/G/iPhone are original color-science profiles, not proprietary Samsung/Google/Apple code.
- Rear/front camera switching and reported focal-length information.
- Photo, HDR, Video, Portrait, Night, Pro.
- GitHub Actions builds a debug APK.

## Important
CameraX vendor extensions are device-dependent. Android's official documentation notes that HDR/Night/Bokeh availability varies by device, and extensions apply to preview and still-image capture, not video. The app checks support at runtime and falls back when needed.

RAW/DNG is not falsely advertised as enabled in V3: true RAW capture requires a dedicated Camera2 RAW capture pipeline and sensor-specific validation. It is planned as a separate V4 feature.

## Build on GitHub
1. Create a new GitHub repository.
2. Upload all files in this folder.
3. Push to `main`.
4. Open **Actions** → **Build AI Cam V3 APK**.
5. Download **AI-Cam-V3-debug-apk**.

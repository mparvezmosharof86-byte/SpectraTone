# SpectraTone - Master Project Blueprint
Developer: Nova Studio 24
Package: com.novastudio24.spectratone

## Architecture & Tech Stack:
- Frontend: Kotlin (Jetpack Compose, Android SDK 34)
- Native Core Engine: C++ (Android NDK r26b, CMake 3.22, SIMD, 2D Laplacian Convolution)
- AI Microservice: Python (FastAPI + RMBG-1.4 / rembg)
- CI/CD & Releases: GitHub Actions with direct APK output

## Implemented Features:
1. Native C++ Adjustments (Brightness, Contrast, Saturation, Warmth, Tint, Vignette)
2. C++ High-Pass Sharpness (DSLR Micro-contrast kernel)
3. 8-Color Selective HSL Mixer (Red to Magenta Hue/Sat/Lum shifts)
4. One-Tap Cinematic Presets (Teal & Orange, Vintage, Film Noir B&W, Cyberpunk)
5. Geometry Tools: Rotate 90°, Horizontal Mirror Flip, Center Ratio Crop (1:1, 4:5, 16:9)
6. Dual-Buffer Rendering: 60 FPS Preview Engine + Full-Resolution Master Exporter
7. AI Suite Integration: One-tap Background Removal with OkHttp client

# PINA VR — Cardboard Mixed Reality OS

**Mixed Reality como padrão, VR como modo imersivo. Tudo 100% 3D spatial. Zero 2D.**
**Camera2 API + Compile com GitHub Actions**

PINA VR é uma plataforma completa de Cardboard VR para Android. Não é um app 2D. É um OS espacial com Camera2 API nativa.

[![Build APK](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/build.yml/badge.svg)](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/build.yml)

## Features Core

- **Cardboard Stereo Nativo**: renderização estéreo com distorção barrel, IPD ajustável, 60/90/120 FPS target
- **Camera2 API (NOVO)**:
  - Traseira: 1920x1080 60fps para passthrough MR, controle manual AF, AE, FPS range, baixa latência, YUV_420_888
  - Frontal: 640x480 60fps para hand tracking, foco otimizado para mãos (30cm), alta velocidade
  - Classes: `Camera2Manager.kt` (controle total Camera2), `PassthroughCameraService.kt`, `HandTrackingService.kt` com MediaPipe
  - Sem CameraX - puro Camera2 para performance máxima
- **Giroscópio + Acelerômetro - Melhor Possível**:
  - Sensor Fusion com Complementary Filter + Madgwick AHRS + Kalman para drift correction
  - 1000Hz de interpolação via predição, 200Hz nativo Android via RotationVector
  - Calibração automática de bias, low-pass adaptativo
- **Mixed Reality (default)**: passthrough da câmera traseira Camera2 + WebRTC fallback, mapeado em esfera invertida
- **Modo VR**: void espacial com skybox
- **Hand Tracking Real**:
  - MediaPipe Hands (Tasks Vision) WASM + Nativo Android
  - **One Euro Filter** por landmark (minCutoff 1.2, beta 0.007, dCutoff 1.0)
  - **Kalman Filter** 3D para predição de velocidade e redução de jitter
  - Depth 6DOF: estimativa de Z via tamanho da mão + focal length
  - Touch Direto: detecção de pinch + colisão 6DOF (não é touch de celular)
- **Spatial UI 100% 3D**: nenhum elemento DOM 2D na experiência. Botões são meshes com SDF, raycast de dedo
- **Navegador Espacial 3D + Multitarefa**:
  - Janelas curvas 3D (cylindrical, 120° FOV cada)
  - CSS3DRenderer + WebGL compositing
  - Até 5 janelas simultâneas em arco
  - Atalhos: Discord, YouTube, totalmente dentro do navegador espacial
- **Jogos**: Pina Saber (Beat Saber clone completo com sabres presos às mãos)
- **Dev API Android**: `window.PinaVR` - API completa para devs criarem apps espaciais
- **GitHub Actions**: build automático de APK debug/release + web artifact

## Arquitetura

```
web/
  src/core/         -> SensorFusion, XRManager, HandTracking, DepthTouch, MR
  src/filters/      -> OneEuro, Kalman (otimizados)
  src/browser/      -> SpatialBrowser multitask
  src/games/        -> Pina Saber
  src/api/          -> PinaAPI + NativeBridge
android/
  app/src/main/java/com/pinavr/os/
    MainActivity.kt          -> WebView imersivo + Camera2 API + sensores
    sensors/SensorFusion.kt  -> Fusão nativa gyro+accel+mag 200Hz + Kalman
    camera/
      Camera2Manager.kt      -> Controle total Camera2 API (NOVO)
      PassthroughCameraService.kt -> Traseira 1080p60 MR (NOVO)
      HandTrackingService.kt -> Frontal 480p60 + MediaPipe (Camera2 API)
  gradle/wrapper/            -> Gradle wrapper para CI
.github/workflows/
  build.yml                  -> GitHub Actions: build web + APK (NOVO)
```

## Camera2 API Detalhes

### Camera2Manager.kt
- `getCameraId(facing)`: acha câmera por LENS_FACING_BACK/FRONT
- `getOptimalSize()`: escolhe tamanho mais próximo do target (1920x1080 traseira, 640x480 frontal)
- `getHighFpsRange()`: pega FPS range com 60fps se disponível
- `startRearCamera()`: YUV_420_888, TEMPLATE_RECORD, AF_CONTINUOUS_VIDEO, AE 60fps, estabilização OFF para baixa latência
- `startFrontCamera()`: YUV_420_888, TEMPLATE_PREVIEW, AF_CONTINUOUS_PICTURE, foco mãos, 60fps
- `ImageReader` com 2-3 buffers, `acquireLatestImage()` para menor latência

### PassthroughCameraService.kt
- Usa Camera2Manager traseira
- Converte YUV_420_888 -> NV21 -> Bitmap se necessário
- `onFrameYuv` e `onFrameBitmap` callbacks
- Para MR passthrough nativo (JS usa getUserMedia como fallback, mas nativo está pronto)

### HandTrackingService.kt
- Camera2 frontal + MediaPipe HandLandmarker
- YUV -> Bitmap -> `BitmapImageBuilder` -> `detectAsync`
- JSON com 21 keypoints por mão + handedness
- Envia para WebView via `PinaNativeBridge.onHandData`

## GitHub Actions - Compile Automático

Workflow `.github/workflows/build.yml`:

1. **build-web**: Node 20, npm install, vite build (fallback para estático), upload artifact `pina-vr-web`
2. **build-android**: Java 17, Android SDK, Gradle 8.5, copia web para `assets/pina/`, `gradlew assembleDebug` + `assembleRelease`, upload APKs `pina-vr-debug-apk`
3. **build-all**: summary

Badge: [![Build](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/build.yml/badge.svg)](https://github.com/AkunDiscoRemake/zentra-xr/actions)

Para baixar APK: vá em Actions > último workflow > Artifacts > `pina-vr-debug-apk`

## Rodar

### Web (PWA - teste no celular com Cardboard)
```bash
cd web
python3 -m http.server 8000
# Abra https://[seu-ip]:8000 no Android Chrome, ative sensores
```

### Android Local
```bash
cd android
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Android via GitHub Actions
- Push para `main` ou `arena/**` dispara build
- APK fica em Artifacts por 30 dias
- Sem necessidade de Android Studio local

## Dev API

```js
// Criar janela 3D
const win = PinaVR.browser.createWindow({ url: 'https://discord.com/app', position: [0,1.5,-2], curved: true });

// Criar botão 3D espacial
const btn = PinaVR.ui.createButton({ label: 'PLAY', position: [0,1,-1], onTouch: () => {} });

// Hand tracking raw
PinaVR.hands.onHands((hands) => { /* hands[0].keypoints 21 + 6DOF */ });

// Sensor fusion
PinaVR.sensors.onOrientation((quat, accel) => {});

// Modo MR/VR
PinaVR.mr.setMode('mixed'); // 'mixed' | 'vr'

// Camera2 API nativa (quando no APK)
PinaNative.getCamera2Info(); // info das câmeras
PinaNative.startCamera2('front'); // inicia frontal 480p60
PinaNative.startCamera2('rear'); // inicia traseira 1080p60
```

Veja `web/DEV_API.md` para API completa.

## Build APK Manual com Camera2

Requisitos:
- Android Studio Hedgehog+
- SDK 34, NDK opcional
- `hand_landmarker.task` em `app/src/main/assets/` (baixe de https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task)

```bash
# Baixa modelo MediaPipe
wget https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -O android/app/src/main/assets/hand_landmarker.task

# Build
cd android
./gradlew assembleDebug
```

## Licença: MIT - Pina VR OS
